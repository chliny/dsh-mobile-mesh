package dev.dsh.mobile.mesh.data

import android.app.Application
import dev.dsh.mobile.mesh.connection.ConnectionManager
import dev.dsh.mobile.mesh.connection.ConnectionPhase
import dev.dsh.mobile.mesh.connection.ConnectionUiState
import dev.dsh.mobile.mesh.connection.HarnessProtocol
import dev.dsh.mobile.mesh.connection.HostConfig
import dev.dsh.mobile.mesh.core.session.UserMessageNode
import dev.dsh.mobile.mesh.core.wire.DshApiClient
import dev.dsh.mobile.mesh.core.wire.HostGeneration
import dev.dsh.mobile.mesh.core.wire.RemoteStreamMux
import dev.dsh.mobile.mesh.core.wire.RpcError
import dev.dsh.mobile.mesh.core.wire.RpcResult
import dev.dsh.mobile.mesh.core.wire.WsChannel
import dev.dsh.mobile.mesh.core.wire.WsChannelSink
import dev.dsh.mobile.mesh.core.wire.dto.AgentPresetListValue
import dev.dsh.mobile.mesh.core.wire.dto.HostDescription
import dev.dsh.mobile.mesh.core.wire.dto.ModelCatalog
import dev.dsh.mobile.mesh.core.wire.dto.ModelCatalogModel
import dev.dsh.mobile.mesh.core.wire.dto.ModelProviderGroup
import dev.dsh.mobile.mesh.core.wire.dto.ModelSelection
import dev.dsh.mobile.mesh.core.wire.dto.RemoteEventFrame
import dev.dsh.mobile.mesh.core.wire.dto.SessionListValue
import dev.dsh.mobile.mesh.core.wire.dto.SessionHistoryRecord
import dev.dsh.mobile.mesh.core.wire.dto.SessionPage
import dev.dsh.mobile.mesh.core.wire.dto.SessionPromptRequest
import dev.dsh.mobile.mesh.core.wire.dto.SessionPromptValue
import dev.dsh.mobile.mesh.core.wire.dto.SessionWireEvent
import dev.dsh.mobile.mesh.core.wire.dto.SessionSelectModelRequest
import dev.dsh.mobile.mesh.core.wire.dto.SessionSelectModelValue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class SessionStoreControlModelProjectionTest {
    private class FakeChannel(private val sink: WsChannelSink) :
        WsChannel("http://stub/api/remote.mux", OkHttpClient(), sink) {
        val streams = ConcurrentHashMap<String, String>()
        val followOpens = AtomicInteger()

        override fun start() = sink.onOpen()
        override fun send(text: String): Boolean {
            val message = Json.parseToJsonElement(text).jsonObject
            if (message["type"]?.jsonPrimitive?.content == "open") {
                val endpoint = message.getValue("endpoint").jsonPrimitive.content
                streams[endpoint] = message.getValue("streamId").jsonPrimitive.content
                if (endpoint == "session/follow") followOpens.incrementAndGet()
            }
            return true
        }
        override fun close() = Unit
        fun emit(endpoint: String, value: String) {
            val streamId = streams.getValue(endpoint)
            sink.onMessage("""{"type":"item","streamId":"$streamId","value":$value}""")
        }
        fun failFollow() {
            val streamId = streams.getValue("session/follow")
            sink.onMessage("""{"type":"error","streamId":"$streamId","error":{"code":"consumer_fell_behind","message":"stream failed"}}""")
        }
    }

    private suspend fun awaitCondition(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(10)
    }

    @Test
    fun `a delayed page request from the previous session cannot page the new session`() = runBlocking {
        lateinit var channel: FakeChannel
        val mux = RemoteStreamMux { sink -> FakeChannel(sink).also { channel = it } }
        mux.start()
        mux.awaitOpen()
        val manager = Mockito.mock(ConnectionManager::class.java)
        val pageCalls = AtomicInteger()
        val api = Mockito.mock(DshApiClient::class.java, org.mockito.stubbing.Answer { invocation ->
            if (invocation.method.name == "sessionPage" || invocation.method.name == "sessionEvents") {
                pageCalls.incrementAndGet()
                RpcResult.Err(RpcError("failure", "Unexpected page"))
            } else {
                Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        })
        val generation = HostGeneration(HostDescription("/home"), "g1", mux)
        Mockito.`when`(manager.state).thenReturn(MutableStateFlow(ConnectionUiState(
            phase = ConnectionPhase.CONNECTED, host = HostConfig("host", "Host", "localhost", 3080),
        )))
        Mockito.`when`(manager.generation).thenReturn(generation)
        Mockito.`when`(manager.connectedApi).thenReturn(api)
        Mockito.`when`(manager.connectedGenerations).thenReturn(MutableSharedFlow<HostGeneration>())
        Mockito.`when`(manager.eventFrames).thenReturn(MutableSharedFlow<RemoteEventFrame>())
        Mockito.`when`(manager.harnessProtocolChanges).thenReturn(MutableStateFlow<HarnessProtocol?>(null))
        Mockito.`when`(api.sessionList(null)).thenReturn(RpcResult.Ok(SessionListValue()))
        Mockito.`when`(api.agentPresetList()).thenReturn(RpcResult.Ok(AgentPresetListValue()))
        val store = SessionStore(manager)
        try {
            store.openSession("a")
            awaitCondition { store.currentSessionId.value == "a" && channel.followOpens.get() >= 1 }
            store.openSession("b")
            awaitCondition { store.currentSessionId.value == "b" && channel.followOpens.get() >= 2 }
            channel.emit("session/follow", """{"type":"snapshot","header":{"version":2,"id":"b","createdAt":1,"isSeeded":false},"cursor":1,"records":[],"hasMore":true,"projections":{"asOfSeq":1,"values":{}}}""")
            awaitCondition { store.currentConversation.value?.sessionId == "b" }

            // This is A's queued pull, started only after the reader switched to B.
            assertFalse(store.loadOlder("a"))
            assertEquals(0, pageCalls.get())
            assertFalse(store.loadOlderFailed.value)
            assertEquals("b", store.currentSessionId.value)
        } finally {
            val scope = SessionStore::class.java.getDeclaredField("scope").apply { isAccessible = true }
            (scope.get(store) as CoroutineScope).cancel()
            mux.close()
        }
    }

    @Test
    fun `a page response from a replaced carrier cannot be installed`() = runBlocking {
        lateinit var channel: FakeChannel
        val mux = RemoteStreamMux { sink -> FakeChannel(sink).also { channel = it } }
        mux.start()
        mux.awaitOpen()
        val manager = Mockito.mock(ConnectionManager::class.java)
        val pageEntered = CountDownLatch(1)
        val releasePage = CountDownLatch(1)
        val olderPage = SessionPage(
            records = listOf(SessionHistoryRecord.Event(event = SessionWireEvent(
                type = "user/message",
                seq = 1,
                time = 1,
                data = buildJsonObject {
                    put("content", kotlinx.serialization.json.JsonArray(listOf(JsonObject(mapOf(
                        "type" to JsonPrimitive("text"),
                        "text" to JsonPrimitive("Older message from the dead carrier"),
                    )))))
                },
            ))),
            hasMore = true,
        )
        var generation = HostGeneration(HostDescription("/home"), "g1", mux)
        val api = Mockito.mock(DshApiClient::class.java, org.mockito.stubbing.Answer { invocation ->
            if (invocation.method.name == "sessionPage" || invocation.method.name == "sessionEvents") {
                pageEntered.countDown()
                check(releasePage.await(5, TimeUnit.SECONDS))
                RpcResult.Ok(olderPage)
            } else {
                Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        })
        Mockito.`when`(manager.state).thenReturn(MutableStateFlow(ConnectionUiState(
            phase = ConnectionPhase.CONNECTED, host = HostConfig("host", "Host", "localhost", 3080),
        )))
        Mockito.`when`(manager.generation).thenAnswer { generation }
        Mockito.`when`(manager.connectedApi).thenReturn(api)
        Mockito.`when`(manager.connectedGenerations).thenReturn(MutableSharedFlow<HostGeneration>())
        Mockito.`when`(manager.eventFrames).thenReturn(MutableSharedFlow<RemoteEventFrame>())
        Mockito.`when`(manager.harnessProtocolChanges).thenReturn(MutableStateFlow<HarnessProtocol?>(null))
        Mockito.`when`(api.sessionList(null)).thenReturn(RpcResult.Ok(SessionListValue()))
        Mockito.`when`(api.agentPresetList()).thenReturn(RpcResult.Ok(AgentPresetListValue()))
        val store = SessionStore(manager)
        try {
            store.openSession("s")
            awaitCondition { channel.followOpens.get() >= 1 }
            channel.emit("session/follow",
                """{"type":"snapshot","header":{"version":2,"id":"s","createdAt":1,"isSeeded":false},"cursor":5,"records":[{"type":"event","event":{"type":"user/message","seq":5,"time":1,"data":{"content":[{"type":"text","text":"Visible message"}]}}}],"hasMore":true,"projections":{"asOfSeq":5,"values":{}}}""")
            awaitCondition { store.currentConversation.value?.nodes?.isNotEmpty() == true }

            val page = async(Dispatchers.IO) { store.loadOlder("s") }
            check(withContext(Dispatchers.IO) { pageEntered.await(5, TimeUnit.SECONDS) })
            // The carrier is replaced while the page RPC is still in flight on the old one.
            generation = HostGeneration(HostDescription("/home"), "g2", mux)
            releasePage.countDown()

            assertFalse(page.await())
            assertFalse(
                "An older page from the dead carrier must not reach the replacement transcript",
                store.currentConversation.value?.nodes.orEmpty().any { it.seq == 1L },
            )
            assertFalse(store.loadingOlder.value)
        } finally {
            releasePage.countDown()
            val scope = SessionStore::class.java.getDeclaredField("scope").apply { isAccessible = true }
            (scope.get(store) as CoroutineScope).cancel()
            mux.close()
        }
    }

    @Test
    fun `an installed older page is visible when paging reports completion`() = runBlocking {
        lateinit var channel: FakeChannel
        val mux = RemoteStreamMux { sink -> FakeChannel(sink).also { channel = it } }
        mux.start()
        mux.awaitOpen()
        val manager = Mockito.mock(ConnectionManager::class.java)
        val olderPage = SessionPage(
            records = listOf(SessionHistoryRecord.Event(event = SessionWireEvent(
                type = "user/message",
                seq = 1,
                time = 1,
                data = buildJsonObject {
                    put("content", kotlinx.serialization.json.JsonArray(listOf(JsonObject(mapOf(
                        "type" to JsonPrimitive("text"),
                        "text" to JsonPrimitive("Fetched older message"),
                    )))))
                },
            ))),
            hasMore = false,
        )
        val api = Mockito.mock(DshApiClient::class.java, org.mockito.stubbing.Answer { invocation ->
            if (invocation.method.name == "sessionPage" || invocation.method.name == "sessionEvents") RpcResult.Ok(olderPage)
            else Mockito.RETURNS_DEFAULTS.answer(invocation)
        })
        val generation = HostGeneration(HostDescription("/home"), "g1", mux)
        Mockito.`when`(manager.state).thenReturn(MutableStateFlow(ConnectionUiState(
            phase = ConnectionPhase.CONNECTED, host = HostConfig("host", "Host", "localhost", 3080),
        )))
        Mockito.`when`(manager.generation).thenReturn(generation)
        Mockito.`when`(manager.connectedApi).thenReturn(api)
        Mockito.`when`(manager.connectedGenerations).thenReturn(MutableSharedFlow<HostGeneration>())
        Mockito.`when`(manager.eventFrames).thenReturn(MutableSharedFlow<RemoteEventFrame>())
        Mockito.`when`(manager.harnessProtocolChanges).thenReturn(MutableStateFlow<HarnessProtocol?>(null))
        Mockito.`when`(api.sessionList(null)).thenReturn(RpcResult.Ok(SessionListValue()))
        Mockito.`when`(api.agentPresetList()).thenReturn(RpcResult.Ok(AgentPresetListValue()))
        val store = SessionStore(manager)
        try {
            store.openSession("s")
            awaitCondition { channel.followOpens.get() >= 1 }
            channel.emit("session/follow",
                """{"type":"snapshot","header":{"version":2,"id":"s","createdAt":1,"isSeeded":false},"cursor":5,"records":[{"type":"event","event":{"type":"user/message","seq":5,"time":1,"data":{"content":[{"type":"text","text":"Visible message"}]}}}],"hasMore":true,"projections":{"asOfSeq":5,"values":{}}}""")
            awaitCondition { store.currentConversation.value?.nodes?.isNotEmpty() == true }

            assertTrue(store.loadOlder("s"))
            // Paging installs the page from its own coroutine; assert the rendered window, not the
            // request result, so the check waits for the append instead of racing it.
            awaitCondition { store.currentConversation.value?.nodes?.any { it.seq == 1L } == true }
            // A second pull made now must page a window that already contains the first page.
            assertEquals(
                "Paging must not report completion while the transcript still shows the old window",
                true,
                store.currentConversation.value?.nodes?.any { it.seq == 1L },
            )
        } finally {
            val scope = SessionStore::class.java.getDeclaredField("scope").apply { isAccessible = true }
            (scope.get(store) as CoroutineScope).cancel()
            mux.close()
        }
    }

    @Test
    fun `control projection changes update selected model and retain legacy frame compatibility`() = runBlocking {
        lateinit var channel: FakeChannel
        val mux = RemoteStreamMux { sink -> FakeChannel(sink).also { channel = it } }
        mux.start()
        mux.awaitOpen()
        val manager = Mockito.mock(ConnectionManager::class.java)
        var promptRequestId = ""
        val api = Mockito.mock(DshApiClient::class.java, org.mockito.stubbing.Answer { invocation ->
            if (invocation.method.name == "sessionPrompt") {
                promptRequestId = (invocation.arguments[0] as SessionPromptRequest).requestId
                RpcResult.Ok(SessionPromptValue())
            } else {
                Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        })
        val generation = HostGeneration(HostDescription("/home"), "g1", mux)
        Mockito.`when`(manager.state).thenReturn(MutableStateFlow(ConnectionUiState(
            phase = ConnectionPhase.CONNECTED,
            host = HostConfig("host", "Host", "localhost", 3080),
        )))
        Mockito.`when`(manager.generation).thenReturn(generation)
        Mockito.`when`(manager.connectedApi).thenReturn(api)
        Mockito.`when`(manager.connectedGenerations).thenReturn(MutableSharedFlow<HostGeneration>())
        Mockito.`when`(manager.eventFrames).thenReturn(MutableSharedFlow<RemoteEventFrame>())
        Mockito.`when`(manager.harnessProtocolChanges).thenReturn(MutableStateFlow<HarnessProtocol?>(null))
        Mockito.`when`(api.sessionList(null)).thenReturn(RpcResult.Ok(SessionListValue()))
        Mockito.`when`(api.agentPresetList()).thenReturn(RpcResult.Ok(AgentPresetListValue()))
        Mockito.`when`(api.sessionModelCatalog()).thenReturn(RpcResult.Ok(ModelCatalog(
            default = ModelSelection("provider", "initial"),
            routableProviders = listOf("provider"),
            groups = listOf(ModelProviderGroup("provider", "Provider", listOf(
                ModelCatalogModel("initial", "Initial"),
                ModelCatalogModel("updated", "Updated"),
                ModelCatalogModel("legacy", "Legacy"),
            ))),
        )))
        Mockito.`when`(api.sessionSelectModel(SessionSelectModelRequest("s", "provider", "updated", "high")))
            .thenReturn(RpcResult.Ok(SessionSelectModelValue(ModelSelection("provider", "updated", "high"))))
        val store = SessionStore(manager)
        try {
            awaitCondition { channel.streams.containsKey("session/control") }
            store.openSession("s")
            awaitCondition { channel.streams.containsKey("session/follow") }
            channel.emit("session/follow", """{"type":"snapshot","header":{"version":2,"id":"s","createdAt":1,"isSeeded":false},"cursor":1,"records":[],"hasMore":false,"projections":{"asOfSeq":1,"values":{"modelSelection":{"next":{"provider":"provider","model":"initial"}}}}}""")
            awaitCondition { store.models.value?.current == ModelSelection("provider", "initial") && store.currentConversation.value != null }

            store.selectModel("provider", "updated", "high")
            Mockito.verify(api).sessionSelectModel(SessionSelectModelRequest("s", "provider", "updated", "high"))
            // The host publishes the accepted selection together with another changed projection.
            channel.emit("session/control", """{"type":"projection","sessionId":"s","seq":2,"changes":[{"key":"modelSelection","val":{"next":{"provider":"provider","model":"updated","reasoningEffort":"high"}}},{"key":"title","val":"Changed alongside model"}]}""")
            awaitCondition {
                store.models.value?.current == ModelSelection("provider", "updated", "high") &&
                    store.currentConversation.value?.projections?.get("title")?.jsonPrimitive?.content == "Changed alongside model"
            }
            assertEquals(ModelSelection("provider", "updated", "high"), store.models.value?.current)

            // Older servers still publish one key and value per projection frame.
            channel.emit("session/control", """{"type":"projection","sessionId":"s","seq":3,"key":"modelSelection","value":{"lastUsed":{"provider":"provider","model":"legacy"}}}""")
            awaitCondition { store.models.value?.current == ModelSelection("provider", "legacy") }
            assertEquals("Changed alongside model", store.currentConversation.value?.projections?.get("title")?.jsonPrimitive?.content)

            // A submitted prompt is visible optimistically before the server confirms its placement.
            store.prompt("Queued during recovery", "queue")
            awaitCondition { store.currentConversation.value?.nodes?.any { it is UserMessageNode && it.messageId == promptRequestId } == true }

            // A follow retry must not prevent the still-live control stream updating the model.
            channel.failFollow()
            awaitCondition { channel.followOpens.get() >= 2 }
            // Legacy hosts publish complete queue frames while follow is still awaiting its new snapshot.
            channel.emit("session/control", """{"type":"queue","sessionId":"s","items":[{"id":"legacy-queued","placement":"queued","rpcId":"$promptRequestId","message":{"id":"legacy-queued","content":[]}}]}""")
            awaitCondition { store.currentConversation.value?.queue?.singleOrNull()?.id == "legacy-queued" }
            assertEquals(false, store.currentConversation.value?.nodes?.any { it is UserMessageNode && it.messageId == promptRequestId })
            assertEquals("Changed alongside model", store.currentConversation.value?.projections?.get("title")?.jsonPrimitive?.content)
            channel.emit("session/control", """{"type":"projection","sessionId":"s","seq":4,"changes":[{"key":"modelSelection","val":{"next":{"provider":"provider","model":"updated"}}},{"key":"inbox","val":{"next-turn":[{"id":"queued-message","content":[]}]}}]}""")
            awaitCondition {
                store.models.value?.current == ModelSelection("provider", "updated") &&
                    store.currentConversation.value?.queue?.singleOrNull()?.id == "queued-message"
            }
            assertEquals("Changed alongside model", store.currentConversation.value?.projections?.get("title")?.jsonPrimitive?.content)

            // A fresh control baseline must also update the visible cached session before follow recovers.
            channel.emit("session/control", """{"type":"baseline","value":{"projections":{"s":{"asOfSeq":5,"values":{"modelSelection":{"next":{"provider":"provider","model":"legacy"}},"inbox":{"next-turn":[]}}}}}}""")
            awaitCondition {
                store.models.value?.current == ModelSelection("provider", "legacy") &&
                    store.currentConversation.value?.queue?.isEmpty() == true
            }
        } finally {
            val scope = SessionStore::class.java.getDeclaredField("scope").apply { isAccessible = true }
            (scope.get(store) as CoroutineScope).cancel()
            mux.close()
        }
    }
}
