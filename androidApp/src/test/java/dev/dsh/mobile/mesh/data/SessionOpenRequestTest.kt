package dev.dsh.mobile.mesh.data

import android.app.Application
import dev.dsh.mobile.mesh.connection.ConnectionManager
import dev.dsh.mobile.mesh.connection.ConnectionPhase
import dev.dsh.mobile.mesh.connection.ConnectionUiState
import dev.dsh.mobile.mesh.connection.HarnessProtocol
import dev.dsh.mobile.mesh.core.wire.DshApiClient
import dev.dsh.mobile.mesh.core.wire.HostGeneration
import dev.dsh.mobile.mesh.core.wire.RemoteStreamMux
import dev.dsh.mobile.mesh.core.wire.dto.HostDescription
import dev.dsh.mobile.mesh.core.wire.RpcResult
import dev.dsh.mobile.mesh.core.wire.dto.RemoteEventFrame
import dev.dsh.mobile.mesh.core.wire.dto.SessionProjectionsRequest
import dev.dsh.mobile.mesh.core.wire.dto.SessionProjectionsValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the real store's request ownership, not a separate model of its selection logic. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class SessionOpenRequestTest {
    @Test
    fun `late ready event cannot cancel a user selection already bound to that generation`(): Unit = runBlocking {
        val manager = Mockito.mock(ConnectionManager::class.java)
        val api = Mockito.mock(DshApiClient::class.java)
        val state = MutableStateFlow(ConnectionUiState(phase = ConnectionPhase.CONNECTING))
        val generations = MutableSharedFlow<HostGeneration>(extraBufferCapacity = 1)
        val protocol = MutableStateFlow<HarnessProtocol?>(HarnessProtocol.UNDETERMINED)
        val generation = HostGeneration(HostDescription("/home"), "G2", Mockito.mock(RemoteStreamMux::class.java))
        val publishedGeneration = AtomicReference<HostGeneration?>(null)
        val connected = AtomicBoolean(false)
        val pauseReady = AtomicBoolean(false)
        val readyEntered = CountDownLatch(1)
        val releaseReady = CountDownLatch(1)
        Mockito.`when`(manager.state).thenAnswer {
            if (pauseReady.compareAndSet(true, false)) {
                readyEntered.countDown()
                check(releaseReady.await(5, TimeUnit.SECONDS)) { "ready-event collector did not resume" }
            }
            state
        }
        Mockito.`when`(manager.connectedGenerations).thenReturn(generations)
        Mockito.`when`(manager.eventFrames).thenReturn(MutableSharedFlow<RemoteEventFrame>())
        Mockito.`when`(manager.harnessProtocolChanges).thenReturn(protocol)
        Mockito.`when`(manager.connectedApi).thenAnswer { if (connected.get()) api else null }
        Mockito.`when`(manager.generation).thenAnswer { publishedGeneration.get() }
        Mockito.`when`(manager.subagentCatalogProtocolUndetermined).thenAnswer {
            protocol.value == HarnessProtocol.UNDETERMINED
        }
        Mockito.`when`(manager.supportsSubagentCatalogProjection).thenAnswer {
            protocol.value == HarnessProtocol.PARENT_CATALOG
        }
        val parentRequest = SessionProjectionsRequest("parent")
        Mockito.`when`(api.sessionProjections(parentRequest)).thenReturn(RpcResult.Ok(
            SessionProjectionsValue(asOfSeq = 1, values = mapOf(
                "subagentCatalog" to Json.parseToJsonElement(
                    """[{"id":"new-child","createdAt":1,"mode":"continuable"}]""",
                ),
            )),
        ))
        val store = SessionStore(manager)
        try {
            store.openSession("old-session")
            withTimeout(5_000) { store.currentSessionId.first { it == "old-session" } }
            withTimeout(5_000) { generations.subscriptionCount.first { it > 0 } }
            connected.set(true)
            publishedGeneration.set(generation)
            pauseReady.set(true)
            generations.tryEmit(generation)
            check(readyEntered.await(5, TimeUnit.SECONDS)) { "ready event was not consumed" }

            // G2 is already published, but its event collector has not finished. The user
            // chooses B while its parent lookup waits for API identification.
            store.openSubagentSession("parent", "new-child")
            withTimeout(5_000) { protocol.subscriptionCount.first { it >= 2 } }
            releaseReady.countDown()
            withTimeout(5_000) { store.sessionsCurrentGenerationId.first { it == "G2" } }
            protocol.value = HarnessProtocol.PARENT_CATALOG
            withTimeout(5_000) { store.currentSessionId.first { it == "new-child" } }
            assertEquals("new-child", store.currentSessionId.value)
            assertNull(store.connectionError.value)
        } finally {
            releaseReady.countDown()
            val scope = SessionStore::class.java.getDeclaredField("scope").apply { isAccessible = true }
            (scope.get(store) as CoroutineScope).cancel()
        }
    }

    @Test
    fun `new selection wins when an older subagent open is waiting for API identification`(): Unit = runBlocking {
        val manager = Mockito.mock(ConnectionManager::class.java)
        val api = Mockito.mock(DshApiClient::class.java)
        val protocol = MutableStateFlow<HarnessProtocol?>(HarnessProtocol.UNDETERMINED)
        Mockito.`when`(manager.state).thenReturn(MutableStateFlow(ConnectionUiState(phase = ConnectionPhase.CONNECTING)))
        Mockito.`when`(manager.connectedGenerations).thenReturn(MutableSharedFlow<HostGeneration>())
        Mockito.`when`(manager.eventFrames).thenReturn(MutableSharedFlow<RemoteEventFrame>())
        Mockito.`when`(manager.harnessProtocolChanges).thenReturn(protocol)
        Mockito.`when`(manager.connectedApi).thenReturn(api)
        Mockito.`when`(manager.subagentCatalogProtocolUndetermined).thenAnswer {
            protocol.value == HarnessProtocol.UNDETERMINED
        }
        Mockito.`when`(manager.supportsSubagentCatalogProjection).thenAnswer {
            protocol.value == HarnessProtocol.PARENT_CATALOG
        }
        Mockito.`when`(manager.supportsPermissionPresetsCatalog).thenAnswer {
            protocol.value == HarnessProtocol.PARENT_CATALOG
        }
        val parentRequest = SessionProjectionsRequest("parent")
        Mockito.`when`(api.sessionProjections(parentRequest)).thenReturn(RpcResult.Ok(
            SessionProjectionsValue(asOfSeq = 1, values = mapOf(
                "subagentCatalog" to Json.parseToJsonElement(
                    """[{"id":"old-child","createdAt":1,"mode":"continuable"}]""",
                ),
            )),
        ))
        val store = SessionStore(manager)
        try {
            val oldClick = launch(start = CoroutineStart.UNDISPATCHED) {
                store.openSubagentSession("parent", "old-child")
            }
            // One subscriber observes protocol changes for the store; the second is the pending
            // subagent open. No sleep or assumptions about dispatcher scheduling are necessary.
            withTimeout(5_000) { protocol.subscriptionCount.first { it >= 2 } }
            // Join the store-owned request, not merely the public method's enqueueing caller.
            val opening = SessionStore::class.java.getDeclaredField("sessionSwitchJob").apply { isAccessible = true }
            val oldRequest = opening.get(store) as Job

            // A local/offline drawer selection must still supersede the suspended online request.
            // Restore the very same API before discovery finishes: an API-identity check alone
            // would therefore not prevent the original stale-open bug.
            Mockito.`when`(manager.connectedApi).thenReturn(null)
            store.openSession("new-choice")
            withTimeout(5_000) { store.currentSessionId.first { it == "new-choice" } }
            Mockito.`when`(manager.connectedApi).thenReturn(api)
            protocol.value = HarnessProtocol.PARENT_CATALOG
            withTimeout(5_000) {
                oldClick.join()
                oldRequest.join()
            }

            assertEquals("new-choice", store.currentSessionId.value)
            assertNull(store.connectionError.value)
            Mockito.verify(api, Mockito.never()).sessionProjections(parentRequest)
        } finally {
            // The application-scoped store normally lives for the process lifetime. Retire its
            // real observer jobs after this isolated test without changing production lifecycle.
            val scope = SessionStore::class.java.getDeclaredField("scope").apply { isAccessible = true }
            (scope.get(store) as CoroutineScope).cancel()
        }
    }
}
