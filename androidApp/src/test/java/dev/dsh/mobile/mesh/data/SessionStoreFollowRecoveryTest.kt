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
import dev.dsh.mobile.mesh.core.wire.WsChannel
import dev.dsh.mobile.mesh.core.wire.WsChannelSink
import dev.dsh.mobile.mesh.core.wire.dto.HostDescription
import dev.dsh.mobile.mesh.core.wire.dto.RemoteEventFrame
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
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class SessionStoreFollowRecoveryTest {
    private class FakeChannel(private val sink: WsChannelSink) :
        WsChannel("http://stub/api/remote.mux", OkHttpClient(), sink) {
        val followIds = CopyOnWriteArrayList<String>()
        val followRequests = CopyOnWriteArrayList<JsonObject>()
        override fun start() = sink.onOpen()
        override fun send(text: String): Boolean {
            val message = Json.parseToJsonElement(text).jsonObject
            if (message["type"]?.jsonPrimitive?.content == "open" &&
                message["endpoint"]?.jsonPrimitive?.content == "session/follow"
            ) {
                followIds += message.getValue("streamId").jsonPrimitive.content
                val request = message["payload"]?.jsonObject?.get("args")?.jsonObject?.get("request") as? JsonObject
                request?.let(followRequests::add)
            }
            return true
        }
        override fun close() = Unit
        fun emit(text: String) = sink.onMessage(text)
    }

    private suspend fun awaitCondition(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(10)
    }

    @Test
    fun `follow reopens after falling behind and applies replacement snapshot`() = runBlocking {
        withStore { store, channel ->
            store.openSession("s")
            awaitCondition { channel.followIds.size == 1 }
            val first = channel.followIds[0]
            channel.emit(snapshot(first, 1, "before"))
            awaitCondition { store.currentConversation.value?.projections?.get("recoveryTest")?.jsonPrimitive?.content == "before" }
            channel.emit(error(first, "consumer_fell_behind"))
            awaitCondition { channel.followIds.size == 2 }
            channel.emit(snapshot(channel.followIds[1], 2, "after"))
            awaitCondition { store.currentConversation.value?.projections?.get("recoveryTest")?.jsonPrimitive?.content == "after" }
            assertEquals(2, channel.followIds.size)
        }
    }

    @Test
    fun `opening an ordinary transcript asks the host to activate it`() = runBlocking {
        // 0.2.2 made background promotion opt-in through `activate`; this app was built on the
        // older unconditional promotion, so an ordinary open must keep asking for it.
        withStore { store, channel ->
            store.openSession("s")
            awaitCondition { channel.followRequests.size == 1 }
            assertEquals(true, channel.followRequests.single()["activate"]?.jsonPrimitive?.content?.toBoolean())
        }
    }

    @Test
    fun `follow business failure is reported without retry`() = runBlocking {
        withStore { store, channel ->
            store.openSession("s")
            awaitCondition { channel.followIds.size == 1 }
            channel.emit(error(channel.followIds[0], "invalid_request"))
            awaitCondition { store.connectionError.value == "stream failed" }
            // A terminal rejection ends the follow loop. Waiting for that job to finish is the
            // deterministic proof that no retry is pending: any further attempt would have to come
            // from this coroutine.
            val followJob = followJobOf(store)
            withTimeout(5_000) { while (followJob != null && !followJob.isCompleted) delay(10) }
            assertEquals(1, channel.followIds.size)
            assertEquals("stream failed", store.connectionError.value)
        }
    }

    private fun followJobOf(store: SessionStore): kotlinx.coroutines.Job? =
        SessionStore::class.java.getDeclaredField("followJob").apply { isAccessible = true }.get(store)
            as kotlinx.coroutines.Job?

    private suspend fun withStore(block: suspend (SessionStore, FakeChannel) -> Unit) {
        lateinit var channel: FakeChannel
        val mux = RemoteStreamMux { sink -> FakeChannel(sink).also { channel = it } }
        mux.start()
        mux.awaitOpen()
        val manager = Mockito.mock(ConnectionManager::class.java)
        val api = Mockito.mock(DshApiClient::class.java)
        val generation = HostGeneration(HostDescription("/home"), "g1", mux)
        Mockito.`when`(manager.state).thenReturn(MutableStateFlow(ConnectionUiState(
            phase = ConnectionPhase.CONNECTING, host = HostConfig("host", "Host", "localhost", 3080),
        )))
        Mockito.`when`(manager.generation).thenReturn(generation)
        Mockito.`when`(manager.connectedApi).thenReturn(api)
        Mockito.`when`(manager.connectedGenerations).thenReturn(MutableSharedFlow<HostGeneration>())
        Mockito.`when`(manager.eventFrames).thenReturn(MutableSharedFlow<RemoteEventFrame>())
        Mockito.`when`(manager.harnessProtocolChanges).thenReturn(MutableStateFlow<HarnessProtocol?>(null))
        val store = SessionStore(manager)
        try {
            block(store, channel)
        } finally {
            val scope = SessionStore::class.java.getDeclaredField("scope").apply { isAccessible = true }
            (scope.get(store) as CoroutineScope).cancel()
            mux.close()
        }
    }

    private fun snapshot(id: String, cursor: Int, value: String) =
        """{"type":"item","streamId":"$id","value":{"type":"snapshot","header":{"version":2,"id":"s","createdAt":1,"isSeeded":false},"cursor":$cursor,"records":[],"hasMore":false,"projections":{"asOfSeq":$cursor,"values":{"recoveryTest":"$value"}}}}"""

    private fun error(id: String, code: String) =
        """{"type":"error","streamId":"$id","error":{"code":"$code","message":"stream failed"}}"""
}
