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
import dev.dsh.mobile.mesh.core.wire.RpcError
import dev.dsh.mobile.mesh.core.wire.RpcResult
import dev.dsh.mobile.mesh.core.wire.dto.HostDescription
import dev.dsh.mobile.mesh.core.wire.dto.RemoteEventFrame
import dev.dsh.mobile.mesh.core.wire.dto.SessionListValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class SessionStoreListRecoveryTest {
    @Test
    fun `late failed list cannot replace newer successful list`() = runBlocking {
        val manager = Mockito.mock(ConnectionManager::class.java)
        val api = Mockito.mock(DshApiClient::class.java)
        val generation = HostGeneration(HostDescription("/home"), "g1", Mockito.mock(RemoteStreamMux::class.java))
        Mockito.`when`(manager.state).thenReturn(MutableStateFlow(ConnectionUiState(
            phase = ConnectionPhase.CONNECTING, host = HostConfig("host", "Host", "localhost", 3080),
        )))
        Mockito.`when`(manager.generation).thenReturn(generation)
        Mockito.`when`(manager.connectedApi).thenReturn(api)
        Mockito.`when`(manager.connectedGenerations).thenReturn(MutableSharedFlow<HostGeneration>())
        Mockito.`when`(manager.eventFrames).thenReturn(MutableSharedFlow<RemoteEventFrame>())
        Mockito.`when`(manager.harnessProtocolChanges).thenReturn(MutableStateFlow<HarnessProtocol?>(null))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val response = Json.decodeFromString(SessionListValue.serializer(),
            """{"items":[{"sessionId":"s","updatedAt":1,"running":false,"blank":false,"projections":{"asOfSeq":0,"values":{"title":"server title"}}}]}""")
        Mockito.`when`(api.sessionList(null)).thenAnswer {
            if (calls.incrementAndGet() == 1) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                RpcResult.Err(RpcError("failure", "old failure"))
            } else RpcResult.Ok(response)
        }
        val store = SessionStore(manager)
        try {
            val old = async(Dispatchers.IO) { store.refreshSessions() }
            check(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            store.refreshSessions()
            release.countDown()
            old.await()
            assertEquals("server title", store.sessions.value.single().title)
            assertFalse(store.sessionsBaselineFailed.value)

            // Older hosts may omit the projection entirely on a subsequent list refresh.
            val withoutProjections = Json.decodeFromString(SessionListValue.serializer(),
                """{"items":[{"sessionId":"s","updatedAt":2,"running":false,"blank":true}]}""")
            Mockito.`when`(api.sessionList(null)).thenReturn(RpcResult.Ok(withoutProjections))
            store.refreshSessions()
            assertEquals(true, store.sessions.value.single().blank)
            assertEquals("server title", store.sessions.value.single().title)

            // A reconnect to the same host must accept its current title, not the old cache.
            val replacement = HostGeneration(HostDescription("/home"), "g2", Mockito.mock(RemoteStreamMux::class.java))
            Mockito.`when`(manager.generation).thenReturn(replacement)
            val updated = Json.decodeFromString(SessionListValue.serializer(),
                """{"items":[{"sessionId":"s","updatedAt":2,"running":false,"blank":false,"projections":{"asOfSeq":0,"values":{"title":"renamed elsewhere"}}}]}""")
            Mockito.`when`(api.sessionList(null)).thenReturn(RpcResult.Ok(updated))
            store.refreshSessions()
            assertEquals("renamed elsewhere", store.sessions.value.single().title)
        } finally {
            release.countDown()
            val scope = SessionStore::class.java.getDeclaredField("scope").apply { isAccessible = true }
            (scope.get(store) as CoroutineScope).cancel()
        }
    }
}
