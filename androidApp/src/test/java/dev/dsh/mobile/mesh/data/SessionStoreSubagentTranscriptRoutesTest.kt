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
import dev.dsh.mobile.mesh.core.wire.RpcHttpResponse
import dev.dsh.mobile.mesh.core.wire.RpcTransport
import dev.dsh.mobile.mesh.core.wire.RpcTransportException
import dev.dsh.mobile.mesh.core.wire.WsChannel
import dev.dsh.mobile.mesh.core.wire.WsChannelSink
import dev.dsh.mobile.mesh.core.wire.dto.HostDescription
import dev.dsh.mobile.mesh.core.wire.dto.RemoteEventFrame
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class SessionStoreSubagentTranscriptRoutesTest {
    private class FakeChannel(private val sink: WsChannelSink) :
        WsChannel("http://stub/api/remote.mux", OkHttpClient(), sink) {
        val follows = CopyOnWriteArrayList<Pair<String, JsonObject>>()
        override fun start() = sink.onOpen()
        override fun send(text: String): Boolean {
            val frame = Json.parseToJsonElement(text).jsonObject
            if (frame["type"]?.jsonPrimitive?.content == "open" &&
                frame["endpoint"]?.jsonPrimitive?.content == "session/follow") {
                follows += frame.getValue("streamId").jsonPrimitive.content to
                    frame.getValue("payload").jsonObject.getValue("args").jsonObject
            }
            return true
        }
        override fun close() = Unit
        fun snapshot(stream: String, text: String, catalog: String = "[]") {
            sink.onMessage("""{"type":"item","streamId":"$stream","value":{"type":"snapshot","header":{"version":2,"id":"parent","createdAt":1,"isSeeded":false},"cursor":5,"records":[{"type":"event","event":{"type":"user/message","seq":5,"time":1,"data":{"content":[{"type":"text","text":"$text"}]}}}],"hasMore":false,"projections":{"asOfSeq":5,"values":{"subagentCatalog":$catalog}}}}""")
        }
    }

    private class FakeTransport(private val eventsError: String?) : RpcTransport {
        val events = CopyOnWriteArrayList<JsonObject>()
        override suspend fun post(path: String, body: String): RpcHttpResponse {
            val envelope = Json.parseToJsonElement(body).jsonObject
            val id = envelope.getValue("rpcId").jsonPrimitive.content
            val endpoint = path.removePrefix("/api/")
            if (endpoint == "session/events") {
                events += envelope.getValue("payload").jsonObject.getValue("args").jsonObject
                if (eventsError == "missing") throw RpcTransportException(404, "not found")
                if (eventsError != null) return RpcHttpResponse(200, """{"type":"server-response","rpcId":"$id","result":{"ok":false,"error":{"code":"$eventsError","message":"child read failed","details":{}}}}""")
                val page = """{"records":[{"type":"event","event":{"type":"user/message","seq":9,"time":1,"data":{"content":[{"type":"text","text":"Child from events"}]}}}],"hasMore":false,"cursor":9}"""
                return RpcHttpResponse(200, """{"type":"server-response","rpcId":"$id","result":{"ok":true,"value":$page}}""")
            }
            val value = when (endpoint) {
                "session/list", "agentPreset/list" -> "{}"
                else -> null
            }
            return if (value != null) RpcHttpResponse(200, """{"type":"server-response","rpcId":"$id","result":{"ok":true,"value":$value}}""")
            else RpcHttpResponse(200, """{"type":"server-response","rpcId":"$id","result":{"ok":false,"error":{"code":"gateway/invocation-unavailable","message":"missing","details":{"endpoint":"$endpoint"}}}}""")
        }
        override suspend fun <T> download(path: String, consume: (String?, String?, InputStream) -> T): T = error("unexpected download")
        override suspend fun upload(path: String, contentType: String, contentLength: Long, body: InputStream, onProgress: ((Long) -> Unit)?): RpcHttpResponse = error("unexpected upload")
    }

    private suspend fun exercise(
        error: String?,
        apiChanged: Boolean = false,
        block: suspend (SessionStore, FakeTransport, FakeChannel) -> Unit,
    ) {
        lateinit var channel: FakeChannel
        val mux = RemoteStreamMux { sink -> FakeChannel(sink).also { channel = it } }
        mux.start()
        mux.awaitOpen()
        val transport = FakeTransport(error)
        val manager = Mockito.mock(ConnectionManager::class.java)
        Mockito.`when`(manager.state).thenReturn(MutableStateFlow(ConnectionUiState(
            phase = ConnectionPhase.CONNECTED, host = HostConfig("host", "Host", "localhost", 3080),
        )))
        Mockito.`when`(manager.generation).thenReturn(HostGeneration(HostDescription("/home"), "g1", mux))
        val api = DshApiClient(transport)
        Mockito.`when`(manager.connectedApi).thenReturn(if (apiChanged) DshApiClient(transport) else api)
        Mockito.`when`(manager.apiForEvent("g1")).thenReturn(api)
        Mockito.`when`(manager.connectedGenerations).thenReturn(MutableSharedFlow<HostGeneration>())
        Mockito.`when`(manager.eventFrames).thenReturn(MutableSharedFlow<RemoteEventFrame>())
        Mockito.`when`(manager.harnessProtocolChanges).thenReturn(MutableStateFlow<HarnessProtocol?>(HarnessProtocol.PARENT_CATALOG))
        Mockito.`when`(manager.supportsSubagentCatalogProjection).thenReturn(true)
        val store = SessionStore(manager)
        try {
            store.openSession("parent")
            withTimeout(5_000) { while (channel.follows.isEmpty()) delay(10) }
            channel.snapshot(channel.follows.first().first, "Parent", """[{"id":"child","createdAt":1,"mode":"continuable"}]""")
            withTimeout(5_000) { while (store.subagents.value.isEmpty()) delay(10) }
            block(store, transport, channel)
        } finally {
            val scope = SessionStore::class.java.getDeclaredField("scope").apply { isAccessible = true }
            (scope.get(store) as CoroutineScope).cancel()
            mux.close()
        }
    }

    private fun assertChildAddress(request: JsonObject) {
        val address = request.getValue("address").jsonObject
        assertEquals("subagent", address.getValue("kind").jsonPrimitive.content)
        assertEquals("parent", address.getValue("parentSessionId").jsonPrimitive.content)
        assertEquals("child", address.getValue("childSessionId").jsonPrimitive.content)
        assertEquals("continuable", address.getValue("mode").jsonPrimitive.content)
    }
    private fun text(store: SessionStore): String? = store.subagentConversation.value?.nodes
        ?.filterIsInstance<UserMessageNode>()?.firstOrNull()?.previewText

    @Test
    fun `modern host reads child tail without parent cursor`() = runBlocking {
        exercise(null) { store, transport, channel ->
            withTimeout(5_000) { store.openSubagentTranscript("child") }
            assertEquals(1, transport.events.size)
            val request = transport.events.single().getValue("request").jsonObject
            assertChildAddress(request)
            assertFalse(request.containsKey("throughSeq"))
            assertEquals("Child from events", text(store))
            assertEquals(1, channel.follows.size)
        }
    }

    @Test
    fun `missing events route reads child follow first snapshot`() = runBlocking {
        exercise("missing") { store, transport, channel ->
            val opening = async { withTimeout(5_000) { withTimeout(5_000) { store.openSubagentTranscript("child") } } }
            withTimeout(5_000) { while (channel.follows.size < 2) delay(10) }
            val (stream, args) = channel.follows[1]
            assertChildAddress(args.getValue("request").jsonObject)
            channel.snapshot(stream, "Child from follow")
            withTimeout(5_000) { opening.await() }
            assertEquals(1, transport.events.size)
            assertEquals("Child from follow", text(store))
            assertEquals(2, channel.follows.size)
        }
    }

    @Test
    fun `changing host cannot send an old child address through the new client`() = runBlocking {
        exercise(null, apiChanged = true) { store, transport, channel ->
            withTimeout(5_000) { store.openSubagentTranscript("child") }
            assertTrue(transport.events.isEmpty())
            assertEquals(1, channel.follows.size)
            assertNull(store.subagentConversation.value)
        }
    }

    @Test
    fun `other events errors surface without following child`() = runBlocking {
        exercise("remote-access/denied") { store, transport, channel ->
            withTimeout(5_000) { store.openSubagentTranscript("child") }
            assertEquals(1, transport.events.size)
            assertEquals(1, channel.follows.size)
            assertNull(store.subagentConversation.value)
            assertTrue(store.connectionError.value?.contains("child read failed") == true)
        }
    }
}
