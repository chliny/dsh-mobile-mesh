package dev.dsh.mobile.mesh.data

import android.app.Application
import dev.dsh.mobile.mesh.connection.ConnectionManager
import dev.dsh.mobile.mesh.connection.ConnectionPhase
import dev.dsh.mobile.mesh.connection.ConnectionUiState
import dev.dsh.mobile.mesh.connection.HarnessProtocol
import dev.dsh.mobile.mesh.connection.HostConfig
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class SessionStorePagingRoutesTest {
    private class FakeChannel(private val sink: WsChannelSink) :
        WsChannel("http://stub/api/remote.mux", OkHttpClient(), sink) {
        val streams = ConcurrentHashMap<String, String>()
        override fun start() = sink.onOpen()
        override fun send(text: String): Boolean {
            val frame = Json.parseToJsonElement(text).jsonObject
            if (frame["type"]?.jsonPrimitive?.content == "open") {
                streams[frame.getValue("endpoint").jsonPrimitive.content] = frame.getValue("streamId").jsonPrimitive.content
            }
            return true
        }
        override fun close() = Unit
        fun snapshot() {
            val stream = streams.getValue("session/follow")
            sink.onMessage("""{"type":"item","streamId":"$stream","value":{"type":"snapshot","header":{"version":2,"id":"s","createdAt":1,"isSeeded":false},"cursor":5,"records":[{"type":"event","event":{"type":"user/message","seq":5,"time":1,"data":{"content":[{"type":"text","text":"Current"}]}}}],"hasMore":true,"projections":{"asOfSeq":5,"values":{}}}}""")
        }
    }

    private class PagingTransport(private val eventsError: String?) : RpcTransport {
        val pagingCalls = CopyOnWriteArrayList<Pair<String, JsonObject>>()
        private var pageCount = 0
        override suspend fun post(path: String, body: String): RpcHttpResponse {
            val envelope = Json.parseToJsonElement(body).jsonObject
            val id = envelope.getValue("rpcId").jsonPrimitive.content
            val endpoint = path.removePrefix("/api/")
            if (endpoint == "session/events" || endpoint == "session/page") {
                pagingCalls += endpoint to envelope.getValue("payload").jsonObject.getValue("args").jsonObject
                if (endpoint == "session/events" && eventsError == "missing") {
                    throw RpcTransportException(404, "not found")
                }
                if (endpoint == "session/events" && eventsError == "denied") {
                    return RpcHttpResponse(200, """{"type":"server-response","rpcId":"$id","result":{"ok":false,"error":{"code":"remote-access/denied","message":"denied","details":{}}}}""")
                }
                val seq = 4 - pageCount++
                val more = seq == 4
                val value = """{"records":[{"type":"event","event":{"type":"user/message","seq":$seq,"time":1,"data":{"content":[{"type":"text","text":"Older $seq"}]}}}],"hasMore":$more,"cursor":5}"""
                return RpcHttpResponse(200, """{"type":"server-response","rpcId":"$id","result":{"ok":true,"value":$value}}""")
            }
            val value = when (endpoint) {
                "session/list" -> "{}"
                "agentPreset/list" -> "{}"
                else -> null
            }
            return if (value != null) RpcHttpResponse(200, """{"type":"server-response","rpcId":"$id","result":{"ok":true,"value":$value}}""")
            else RpcHttpResponse(200, """{"type":"server-response","rpcId":"$id","result":{"ok":false,"error":{"code":"gateway/invocation-unavailable","message":"missing","details":{"endpoint":"$endpoint"}}}}""")
        }
        override suspend fun <T> download(path: String, consume: (String?, String?, InputStream) -> T): T = error("unexpected download")
        override suspend fun upload(path: String, contentType: String, contentLength: Long, body: InputStream, onProgress: ((Long) -> Unit)?): RpcHttpResponse = error("unexpected upload")
    }

    private suspend fun exercise(error: String?, block: suspend (SessionStore, PagingTransport) -> Unit) {
        lateinit var channel: FakeChannel
        val mux = RemoteStreamMux { sink -> FakeChannel(sink).also { channel = it } }
        mux.start()
        mux.awaitOpen()
        val transport = PagingTransport(error)
        val manager = Mockito.mock(ConnectionManager::class.java)
        Mockito.`when`(manager.state).thenReturn(MutableStateFlow(ConnectionUiState(
            phase = ConnectionPhase.CONNECTED, host = HostConfig("host", "Host", "localhost", 3080),
        )))
        Mockito.`when`(manager.generation).thenReturn(HostGeneration(HostDescription("/home"), "g1", mux))
        Mockito.`when`(manager.connectedApi).thenReturn(DshApiClient(transport))
        Mockito.`when`(manager.connectedGenerations).thenReturn(MutableSharedFlow<HostGeneration>())
        Mockito.`when`(manager.eventFrames).thenReturn(MutableSharedFlow<RemoteEventFrame>())
        Mockito.`when`(manager.harnessProtocolChanges).thenReturn(MutableStateFlow<HarnessProtocol?>(null))
        val store = SessionStore(manager)
        try {
            store.openSession("s")
            withTimeout(5_000) { while (!channel.streams.containsKey("session/follow")) delay(10) }
            channel.snapshot()
            withTimeout(5_000) { while (store.currentConversation.value?.nodes?.none { it.seq == 5L } != false) delay(10) }
            block(store, transport)
        } finally {
            val scope = SessionStore::class.java.getDeclaredField("scope").apply { isAccessible = true }
            (scope.get(store) as CoroutineScope).cancel()
            mux.close()
        }
    }

    @Test
    fun `modern host pages twice through events and installs both pages`() = runBlocking {
        exercise(null) { store, transport ->
            assertTrue(store.loadOlder("s"))
            assertTrue(store.loadOlder("s"))
            assertEquals(listOf("session/events", "session/events"), transport.pagingCalls.map { it.first })
            val requests = transport.pagingCalls.map { it.second.getValue("request").jsonObject }
            assertEquals(listOf("5", "5"), requests.map { it.getValue("throughSeq").jsonPrimitive.content })
            assertEquals(listOf("5", "4"), requests.map { it.getValue("beforeSeq").jsonPrimitive.content })
            assertTrue(store.currentConversation.value!!.nodes.any { it.seq == 4L })
            assertTrue(store.currentConversation.value!!.nodes.any { it.seq == 3L })
            assertFalse(store.loadOlderFailed.value)
        }
    }

    @Test
    fun `missing events route falls back and caches page route for this connection`() = runBlocking {
        exercise("missing") { store, transport ->
            assertTrue(store.loadOlder("s"))
            assertTrue(store.loadOlder("s"))
            assertEquals(listOf("session/events", "session/page", "session/page"), transport.pagingCalls.map { it.first })
            assertTrue(store.currentConversation.value!!.nodes.any { it.seq == 4L })
            assertTrue(store.currentConversation.value!!.nodes.any { it.seq == 3L })
            assertFalse(store.loadOlderFailed.value)
        }
    }

    @Test
    fun `events failure other than missing route is surfaced without legacy fallback`() = runBlocking {
        exercise("denied") { store, transport ->
            assertFalse(store.loadOlder("s"))
            assertEquals(listOf("session/events"), transport.pagingCalls.map { it.first })
            assertTrue(store.loadOlderFailed.value)
            assertFalse(store.currentConversation.value!!.nodes.any { it.seq == 4L })
        }
    }
}
