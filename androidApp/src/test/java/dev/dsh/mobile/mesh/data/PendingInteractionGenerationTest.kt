package dev.dsh.mobile.mesh.data

import android.app.Application
import dev.dsh.mobile.mesh.connection.ConnectionManager
import dev.dsh.mobile.mesh.connection.ConnectionPhase
import dev.dsh.mobile.mesh.connection.ConnectionUiState
import dev.dsh.mobile.mesh.connection.HostConfig
import dev.dsh.mobile.mesh.core.wire.DshApiClient
import dev.dsh.mobile.mesh.core.wire.HostGeneration
import dev.dsh.mobile.mesh.core.wire.RemoteStreamMux
import dev.dsh.mobile.mesh.core.wire.RpcResult
import dev.dsh.mobile.mesh.core.wire.WsChannel
import dev.dsh.mobile.mesh.core.wire.WsChannelSink
import dev.dsh.mobile.mesh.core.wire.dto.AgentPresetListValue
import dev.dsh.mobile.mesh.core.wire.dto.HostDescription
import dev.dsh.mobile.mesh.core.wire.dto.RemoteEventFrame
import dev.dsh.mobile.mesh.core.wire.dto.SessionListValue
import dev.dsh.mobile.mesh.core.wire.dto.SessionSummary
import dev.dsh.mobile.mesh.core.wire.dto.USER_QUESTIONS_REQUEST_EVENT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class PendingInteractionGenerationTest {
    private class FakeChannel(private val sink: WsChannelSink) :
        WsChannel("http://stub/api/remote.mux", OkHttpClient(), sink) {
        override fun start() = sink.onOpen()
        override fun send(text: String): Boolean = true
        override fun close() = Unit
    }

    private suspend fun awaitCondition(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(10)
    }

    /**
     * A question answered from the Web while the phone was away must stop badging the session.
     *
     * The withdrawal frame cannot reach a carrier that is already gone, so the only repair left is
     * the generation boundary: the panel dies with its generation, and the list badge has to die
     * with it instead of claiming work the client can no longer show or answer.
     */
    @Test
    fun `a question answered elsewhere stops badging the session once its generation ends`() = runBlocking {
        val mux = RemoteStreamMux { sink -> FakeChannel(sink) }
        mux.start()
        mux.awaitOpen()
        val manager = Mockito.mock(ConnectionManager::class.java)
        val api = Mockito.mock(DshApiClient::class.java)
        val state = MutableStateFlow(
            ConnectionUiState(
                phase = ConnectionPhase.CONNECTED,
                host = HostConfig("host", "Host", "localhost", 3080),
                hasConnected = true,
            ),
        )
        val frames = MutableSharedFlow<RemoteEventFrame>(extraBufferCapacity = 16)
        val generation = HostGeneration(HostDescription("/home"), "g1", mux)
        Mockito.`when`(manager.state).thenReturn(state)
        Mockito.`when`(manager.generation).thenReturn(generation)
        Mockito.`when`(manager.connectedApi).thenReturn(api)
        Mockito.`when`(manager.connectedGenerations).thenReturn(MutableSharedFlow())
        Mockito.`when`(manager.eventFrames).thenReturn(frames)
        Mockito.`when`(manager.harnessProtocolChanges).thenReturn(MutableStateFlow(null))
        Mockito.`when`(api.sessionList(null)).thenReturn(
            RpcResult.Ok(
                SessionListValue(
                    listOf(SessionSummary(sessionId = "s", updatedAt = 1, running = true, blank = false)),
                ),
            ),
        )
        Mockito.`when`(api.agentPresetList()).thenReturn(RpcResult.Ok(AgentPresetListValue()))
        val store = SessionStore(manager)
        fun badge() = store.sessions.value.firstOrNull { it.sessionId == "s" }?.pendingInteraction
        try {
            awaitCondition { store.sessions.value.any { it.sessionId == "s" } }
            awaitCondition { frames.subscriptionCount.value > 0 }
            val question = RemoteEventFrame.Waterfall(
                event = USER_QUESTIONS_REQUEST_EVENT,
                eventId = "question-1",
                agentId = "s",
                request = buildJsonObject {
                    put("questions", buildJsonArray {
                        add(buildJsonObject {
                            put("id", "q1")
                            put("question", "Ship it?")
                        })
                    })
                },
                clientId = "g1",
            )
            frames.emit(question)
            awaitCondition { badge() == "question" }

            // The answer happened elsewhere, so no withdrawal reaches this dead carrier.
            state.value = state.value.copy(phase = ConnectionPhase.RECONNECTING)
            awaitCondition { badge() == null }

            // A request that is genuinely still open is re-delivered by the replacement
            // generation's `$events` stream, so clearing here cannot lose it.
            frames.emit(question)
            awaitCondition { badge() == "question" }
        } finally {
            val scope = SessionStore::class.java.getDeclaredField("scope").apply { isAccessible = true }
            (scope.get(store) as CoroutineScope).cancel()
            mux.close()
        }
    }
}
