package dev.dsh.mobile.mesh.data

import android.app.Application
import dev.dsh.mobile.mesh.connection.ConnectionManager
import dev.dsh.mobile.mesh.connection.ConnectionPhase
import dev.dsh.mobile.mesh.connection.ConnectionUiState
import dev.dsh.mobile.mesh.connection.HarnessProtocol
import dev.dsh.mobile.mesh.connection.HostConfig
import dev.dsh.mobile.mesh.core.wire.DshApiClient
import dev.dsh.mobile.mesh.core.wire.HostGeneration
import dev.dsh.mobile.mesh.core.wire.RpcResult
import dev.dsh.mobile.mesh.core.wire.dto.RemoteEventFrame
import dev.dsh.mobile.mesh.core.wire.dto.SessionCreateRequest
import dev.dsh.mobile.mesh.core.wire.dto.SessionCreateValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A blank session created for a workspace is recorded against that workspace, and the record is
 * dropped as soon as the session starts a turn.
 *
 * This is the boundary between the Host's session ids and the composer's workspace-scoped drafts:
 * if the returned session id is not recorded, the chat screen cannot find the draft it should
 * restore; if the record survives the first turn, a later visit would re-read a stale draft.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class NewSessionDraftScopeTest {
    @Test
    fun `created blank sessions are scoped per workspace and retired when they start`() = runBlocking {
        val manager = Mockito.mock(ConnectionManager::class.java)
        val api = Mockito.mock(DshApiClient::class.java)
        Mockito.`when`(manager.state).thenReturn(
            MutableStateFlow(
                ConnectionUiState(
                    phase = ConnectionPhase.CONNECTED,
                    host = HostConfig(id = "host-a", name = "Host A", host = "localhost", port = 3080),
                ),
            ),
        )
        Mockito.`when`(manager.generation).thenReturn(null)
        Mockito.`when`(manager.connectedApi).thenReturn(api)
        Mockito.`when`(manager.connectedGenerations).thenReturn(MutableSharedFlow<HostGeneration>())
        Mockito.`when`(manager.eventFrames).thenReturn(MutableSharedFlow<RemoteEventFrame>())
        Mockito.`when`(manager.harnessProtocolChanges).thenReturn(MutableStateFlow<HarnessProtocol?>(null))
        Mockito.`when`(api.sessionCreate(SessionCreateRequest(workspaceId = "workspace-a")))
            .thenReturn(RpcResult.Ok(SessionCreateValue(sessionId = "blank-workspace-a")))
        Mockito.`when`(api.sessionCreate(SessionCreateRequest(workspaceId = "workspace-b")))
            .thenReturn(RpcResult.Ok(SessionCreateValue(sessionId = "blank-workspace-b")))
        Mockito.`when`(api.sessionCreate(SessionCreateRequest(workspaceId = "workspace-c")))
            .thenReturn(RpcResult.Ok(SessionCreateValue(sessionId = "blank-workspace-c")))
        val store = SessionStore(manager)
        try {
            store.createSession(workspaceId = "workspace-a")
            store.createSession(workspaceId = "workspace-b")
            store.createSession(workspaceId = "workspace-c", ownsNewSessionDraft = false)

            val scopes = store.newSessionDraftScopes.value
            assertEquals(NewSessionDraftScope("host-a", "workspace-a"), scopes["blank-workspace-a"])
            assertEquals(NewSessionDraftScope("host-a", "workspace-b"), scopes["blank-workspace-b"])
            assertNull(scopes["blank-workspace-c"])

            setBlank(store, "blank-workspace-a", blank = false)

            assertFalse(store.newSessionDraftScopes.value.containsKey("blank-workspace-a"))
            assertTrue(store.newSessionDraftScopes.value.containsKey("blank-workspace-b"))
        } finally {
            cancelStoreScope(store)
        }
    }

    private fun setBlank(store: SessionStore, sessionId: String, blank: Boolean) {
        val method = SessionStore::class.java.getDeclaredMethod(
            "setBlank",
            String::class.java,
            java.lang.Boolean.TYPE,
        )
        method.isAccessible = true
        method.invoke(store, sessionId, blank)
    }

    private fun cancelStoreScope(store: SessionStore) {
        val field = SessionStore::class.java.getDeclaredField("scope").apply { isAccessible = true }
        (field.get(store) as CoroutineScope).cancel()
    }
}
