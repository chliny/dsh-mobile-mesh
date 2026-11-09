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
import dev.dsh.mobile.mesh.core.wire.RpcResult
import dev.dsh.mobile.mesh.core.wire.dto.AgentPresetListValue
import dev.dsh.mobile.mesh.core.wire.dto.HostDescription
import dev.dsh.mobile.mesh.core.wire.dto.ModelCatalog
import dev.dsh.mobile.mesh.core.wire.dto.ModelSelection
import dev.dsh.mobile.mesh.core.wire.dto.RemoteEventFrame
import dev.dsh.mobile.mesh.core.wire.dto.SessionListValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.startCoroutine
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class SessionStoreBaselineGenerationRaceTest {
    @Test
    fun `fallback replaces suspended old baseline and ready preserves new baseline`() = runBlocking {
        val manager = Mockito.mock(ConnectionManager::class.java)
        val api = Mockito.mock(DshApiClient::class.java)
        val g1 = HostGeneration(HostDescription("/home"), "g1", Mockito.mock(RemoteStreamMux::class.java))
        val g2 = HostGeneration(HostDescription("/home"), "g2", Mockito.mock(RemoteStreamMux::class.java))
        val state = MutableStateFlow(ConnectionUiState(phase = ConnectionPhase.CONNECTING,
            host = HostConfig("host", "Host", "localhost", 3080)))
        val generations = MutableSharedFlow<HostGeneration>()
        Mockito.`when`(manager.state).thenReturn(state)
        Mockito.`when`(manager.generation).thenReturn(g1)
        Mockito.`when`(manager.connectedApi).thenReturn(api)
        Mockito.`when`(manager.connectedGenerations).thenReturn(generations)
        Mockito.`when`(manager.eventFrames).thenReturn(MutableSharedFlow<RemoteEventFrame>())
        Mockito.`when`(manager.harnessProtocolChanges).thenReturn(MutableStateFlow<HarnessProtocol?>(null))
        val oldEntered = CompletableDeferred<Unit>()
        val oldCancelled = CompletableDeferred<Unit>()
        val newEntered = CompletableDeferred<Unit>()
        val releaseNew = CompletableDeferred<Unit>()
        val newCancelled = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val response = Json.decodeFromString(SessionListValue.serializer(),
            """{"items":[{"sessionId":"s","updatedAt":1,"running":false,"blank":false,"projections":{"asOfSeq":0,"values":{"title":"new generation"}}}]}""")
        Mockito.`when`(api.sessionList(null)).thenAnswer { invocation ->
            val call = calls.incrementAndGet()
            @Suppress("UNCHECKED_CAST")
            val continuation = invocation.rawArguments.last() as Continuation<RpcResult<SessionListValue>>
            val request: suspend () -> RpcResult<SessionListValue> = {
                try {
                    if (call == 1) {
                        oldEntered.complete(Unit)
                        CompletableDeferred<Unit>().await()
                    } else {
                        newEntered.complete(Unit)
                        releaseNew.await()
                    }
                    RpcResult.Ok(response)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    if (call == 1) oldCancelled.complete(Unit) else newCancelled.complete(Unit)
                    throw e
                }
            }
            request.startCoroutine(continuation)
            COROUTINE_SUSPENDED
        }
        val store = SessionStore(manager)
        try {
            withTimeout(5_000) {
                generations.subscriptionCount.first { it > 0 }
                state.subscriptionCount.first { it > 0 }
                generations.emit(g1)
                oldEntered.await()
                Mockito.`when`(manager.generation).thenReturn(g2)
                state.value = state.value.copy(phase = ConnectionPhase.CONNECTED, hasConnected = true)
                oldCancelled.await()
                newEntered.await()
                generations.emit(g2)
                store.sessionsCurrentGenerationId.first { it == "g2" }
                assertFalse(newCancelled.isCompleted)
                releaseNew.complete(Unit)
                store.sessions.first { it.any { row -> row.title == "new generation" } }
                assertEquals(2, calls.get())
            }
        } finally {
            releaseNew.complete(Unit)
            val scope = SessionStore::class.java.getDeclaredField("scope").apply { isAccessible = true }
            (scope.get(store) as CoroutineScope).cancel()
        }
    }
}
