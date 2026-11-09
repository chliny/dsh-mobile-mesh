package dev.dsh.mobile.mesh.data

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import dev.dsh.mobile.mesh.connection.AppSettings
import dev.dsh.mobile.mesh.connection.ConnectionManager
import dev.dsh.mobile.mesh.connection.ConnectionUiState
import dev.dsh.mobile.mesh.connection.HostConfig
import dev.dsh.mobile.mesh.connection.HostsStore
import dev.dsh.mobile.mesh.core.wire.DshApiClient
import dev.dsh.mobile.mesh.core.wire.HostGeneration
import dev.dsh.mobile.mesh.core.wire.RemoteStreamMux
import dev.dsh.mobile.mesh.core.wire.RpcResult
import dev.dsh.mobile.mesh.core.wire.dto.HostDescription
import dev.dsh.mobile.mesh.core.wire.dto.RemoteEventFrame
import dev.dsh.mobile.mesh.core.wire.dto.SessionListValue
import dev.dsh.mobile.mesh.core.wire.dto.SessionRenameRequest
import dev.dsh.mobile.mesh.core.wire.dto.SessionRenameValue
import dev.dsh.mobile.mesh.notify.DshNotifications
import dev.dsh.mobile.mesh.notify.NotificationObserver
import dev.dsh.mobile.mesh.ui.AppNavigationState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.startCoroutine

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class SessionAddedNotificationFlowTest {
    @Test
    fun `added session projection appears in list and completion notification`() = withFlow { flow ->
        flow.addSession("titled", "\"Release plan\"")
        assertEquals("Release plan", withTimeout(5_000) { flow.store.sessions.first { it.any { row -> row.sessionId == "titled" } } }.single().title)
        assertEquals("Session finished — Release plan", flow.finish("titled"))
    }

    @Test
    fun `late session list preserves title from a newer added event`() = withFlow { flow ->
        val oldEntered = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val currentEntered = CompletableDeferred<Unit>()
        val releaseCurrent = CompletableDeferred<Unit>()
        val oldList = Json.decodeFromString(SessionListValue.serializer(),
            """{"items":[{"sessionId":"racing","updatedAt":1,"running":false,"blank":false,"projections":{"asOfSeq":0,"values":{"title":"Old server title"}}},{"sessionId":"old-only","updatedAt":1,"running":false,"blank":false}]}""")
        val currentList = Json.decodeFromString(SessionListValue.serializer(),
            """{"items":[{"sessionId":"racing","updatedAt":2,"running":false,"blank":false,"projections":{"asOfSeq":0,"values":{"title":"New event title"}}},{"sessionId":"current-only","updatedAt":2,"running":false,"blank":false}]}""")
        val calls = AtomicInteger()
        Mockito.`when`(flow.api.sessionList(null)).thenAnswer { invocation ->
            val call = calls.incrementAndGet()
            @Suppress("UNCHECKED_CAST")
            val continuation = invocation.rawArguments.last() as Continuation<RpcResult<SessionListValue>>
            val request: suspend () -> RpcResult<SessionListValue> = {
                if (call == 1) {
                    oldEntered.complete(Unit)
                    releaseOld.await()
                    RpcResult.Ok(oldList)
                } else {
                    currentEntered.complete(Unit)
                    releaseCurrent.await()
                    RpcResult.Ok(currentList)
                }
            }
            request.startCoroutine(continuation)
            COROUTINE_SUSPENDED
        }

        val oldRequest = async { flow.store.refreshSessions() }
        try {
            withTimeout(5_000) { oldEntered.await() }
            flow.addSession("racing", "\"New event title\"")
            assertEquals("New event title", withTimeout(5_000) {
                flow.store.sessions.first { rows -> rows.any { it.sessionId == "racing" } }
            }.single().title)
            withTimeout(5_000) { currentEntered.await() }

            releaseOld.complete(Unit)
            oldRequest.await()
            val afterOld = flow.store.sessions.value
            assertEquals(setOf("racing", "old-only"), afterOld.map { it.sessionId }.toSet())
            assertEquals("New event title", afterOld.single { it.sessionId == "racing" }.title)

            releaseCurrent.complete(Unit)
            val applied = withTimeout(5_000) {
                flow.store.sessions.first { rows -> rows.any { it.sessionId == "current-only" } }
            }
            assertEquals(setOf("racing", "current-only"), applied.map { it.sessionId }.toSet())
            assertEquals("New event title", applied.single { it.sessionId == "racing" }.title)
            assertEquals(2, calls.get())
        } finally {
            releaseOld.complete(Unit)
            releaseCurrent.complete(Unit)
        }
    }

    @Test
    fun `late session list preserves an added session missing from its snapshot`() = withFlow { flow ->
        val oldEntered = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val currentEntered = CompletableDeferred<Unit>()
        val releaseCurrent = CompletableDeferred<Unit>()
        val oldList = Json.decodeFromString(SessionListValue.serializer(),
            """{"items":[{"sessionId":"old-only","updatedAt":1,"running":false,"blank":false}]}""")
        val currentList = Json.decodeFromString(SessionListValue.serializer(),
            """{"items":[{"sessionId":"new-during-list","updatedAt":2,"running":false,"blank":false,"projections":{"asOfSeq":0,"values":{"title":"New session title"}}},{"sessionId":"current-only","updatedAt":2,"running":false,"blank":false}]}""")
        val calls = AtomicInteger()
        Mockito.`when`(flow.api.sessionList(null)).thenAnswer { invocation ->
            val call = calls.incrementAndGet()
            @Suppress("UNCHECKED_CAST")
            val continuation = invocation.rawArguments.last() as Continuation<RpcResult<SessionListValue>>
            val request: suspend () -> RpcResult<SessionListValue> = {
                if (call == 1) {
                    oldEntered.complete(Unit)
                    releaseOld.await()
                    RpcResult.Ok(oldList)
                } else {
                    currentEntered.complete(Unit)
                    releaseCurrent.await()
                    RpcResult.Ok(currentList)
                }
            }
            request.startCoroutine(continuation)
            COROUTINE_SUSPENDED
        }

        val oldRequest = async { flow.store.refreshSessions() }
        try {
            withTimeout(5_000) { oldEntered.await() }
            flow.addSession("new-during-list", "\"New session title\"")
            withTimeout(5_000) {
                flow.store.sessions.first { rows -> rows.any { it.sessionId == "new-during-list" } }
            }
            withTimeout(5_000) { currentEntered.await() }

            releaseOld.complete(Unit)
            oldRequest.await()
            val afterOld = flow.store.sessions.value
            assertEquals(setOf("new-during-list", "old-only"), afterOld.map { it.sessionId }.toSet())
            assertEquals("New session title", afterOld.single { it.sessionId == "new-during-list" }.title)

            releaseCurrent.complete(Unit)
            val applied = withTimeout(5_000) {
                flow.store.sessions.first { rows -> rows.any { it.sessionId == "current-only" } }
            }
            assertEquals(setOf("new-during-list", "current-only"), applied.map { it.sessionId }.toSet())
            assertEquals("New session title", applied.single { it.sessionId == "new-during-list" }.title)
            assertEquals(2, calls.get())
        } finally {
            releaseOld.complete(Unit)
            releaseCurrent.complete(Unit)
        }
    }

    @Test
    fun `late session list cannot resurrect a removed session`() = withFlow { flow ->
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val listed = Json.decodeFromString(SessionListValue.serializer(),
            """{"items":[{"sessionId":"removed-during-list","updatedAt":1,"running":false,"blank":false},{"sessionId":"alive","updatedAt":1,"running":false,"blank":false}]}""")
        val current = Json.decodeFromString(SessionListValue.serializer(),
            """{"items":[{"sessionId":"removed-during-list","updatedAt":2,"running":false,"blank":false},{"sessionId":"alive","updatedAt":2,"running":false,"blank":false},{"sessionId":"current-only","updatedAt":2,"running":false,"blank":false}]}""")
        val calls = AtomicInteger()
        Mockito.`when`(flow.api.sessionList(null)).thenAnswer { invocation ->
            val call = calls.incrementAndGet()
            @Suppress("UNCHECKED_CAST")
            val continuation = invocation.rawArguments.last() as Continuation<RpcResult<SessionListValue>>
            val request: suspend () -> RpcResult<SessionListValue> = {
                if (call == 1) RpcResult.Ok(listed) else {
                    entered.complete(Unit)
                    release.await()
                    RpcResult.Ok(current)
                }
            }
            request.startCoroutine(continuation)
            COROUTINE_SUSPENDED
        }

        flow.store.refreshSessions()
        assertEquals(setOf("alive", "removed-during-list"), flow.store.sessions.value.map { it.sessionId }.toSet())
        val oldRequest = async { flow.store.refreshSessions() }
        try {
            withTimeout(5_000) { entered.await() }
            flow.removeSession("removed-during-list")
            withTimeout(5_000) { flow.store.sessions.first { it.none { row -> row.sessionId == "removed-during-list" } } }

            release.complete(Unit)
            oldRequest.await()
            val applied = withTimeout(5_000) {
                flow.store.sessions.first { rows -> rows.any { it.sessionId == "current-only" } }
            }
            assertTrue(applied.none { it.sessionId == "removed-during-list" })
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun `older list snapshot must not drop the tombstone a newer in-flight list still needs`() = withFlow { flow ->
        val olderEntered = CompletableDeferred<Unit>()
        val releaseOlder = CompletableDeferred<Unit>()
        val newerEntered = CompletableDeferred<Unit>()
        val releaseNewer = CompletableDeferred<Unit>()
        val initial = Json.decodeFromString(SessionListValue.serializer(),
            """{"items":[{"sessionId":"x","updatedAt":1,"running":false,"blank":false}]}""")
        val older = Json.decodeFromString(SessionListValue.serializer(),
            """{"items":[{"sessionId":"older-only","updatedAt":2,"running":false,"blank":false}]}""")
        val newer = Json.decodeFromString(SessionListValue.serializer(),
            """{"items":[{"sessionId":"x","updatedAt":3,"running":false,"blank":false},{"sessionId":"current-only","updatedAt":3,"running":false,"blank":false}]}""")
        val calls = AtomicInteger()
        Mockito.`when`(flow.api.sessionList(null)).thenAnswer { invocation ->
            val call = calls.incrementAndGet()
            @Suppress("UNCHECKED_CAST")
            val continuation = invocation.rawArguments.last() as Continuation<RpcResult<SessionListValue>>
            val request: suspend () -> RpcResult<SessionListValue> = {
                when (call) {
                    1 -> RpcResult.Ok(initial)
                    2 -> {
                        olderEntered.complete(Unit)
                        releaseOlder.await()
                        RpcResult.Ok(older)
                    }
                    else -> {
                        newerEntered.complete(Unit)
                        releaseNewer.await()
                        RpcResult.Ok(newer)
                    }
                }
            }
            request.startCoroutine(continuation)
            COROUTINE_SUSPENDED
        }

        flow.store.refreshSessions()
        assertEquals("x", flow.store.sessions.value.single().sessionId)
        val olderRequest = async { flow.store.refreshSessions() }
        val newerRequest = async { flow.store.refreshSessions() }
        try {
            withTimeout(5_000) { olderEntered.await() }
            withTimeout(5_000) { newerEntered.await() }
            flow.removeSession("x")
            withTimeout(5_000) { flow.store.sessions.first { it.isEmpty() } }

            releaseOlder.complete(Unit)
            olderRequest.await()
            assertEquals("older-only", flow.store.sessions.value.single().sessionId)

            releaseNewer.complete(Unit)
            newerRequest.await()
            val applied = withTimeout(5_000) {
                flow.store.sessions.first { rows -> rows.any { it.sessionId == "current-only" } }
            }
            assertTrue(applied.none { it.sessionId == "x" })
            assertTrue(applied.none { it.sessionId == "older-only" })
        } finally {
            releaseOlder.complete(Unit)
            releaseNewer.complete(Unit)
        }
    }

    @Test
    fun `renaming during a recovering session list keeps the accepted title`() = withFlow { flow ->
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val staleList = Json.decodeFromString(SessionListValue.serializer(),
            """{"items":[{"sessionId":"s","updatedAt":1,"running":false,"blank":false,"projections":{"asOfSeq":0,"values":{"title":"Old title"}}}]}""")
        val recoveryList = Json.decodeFromString(SessionListValue.serializer(),
            """{"items":[{"sessionId":"s","updatedAt":2,"running":false,"blank":false,"projections":{"asOfSeq":0,"values":{"title":"Old title"}}},{"sessionId":"recovery-only","updatedAt":2,"running":false,"blank":false}]}""")
        val calls = AtomicInteger()
        Mockito.`when`(flow.api.sessionList(null)).thenAnswer { invocation ->
            val call = calls.incrementAndGet()
            @Suppress("UNCHECKED_CAST")
            val continuation = invocation.rawArguments.last() as Continuation<RpcResult<SessionListValue>>
            val request: suspend () -> RpcResult<SessionListValue> = {
                if (call == 1) RpcResult.Ok(staleList) else {
                    entered.complete(Unit)
                    release.await()
                    RpcResult.Ok(recoveryList)
                }
            }
            request.startCoroutine(continuation)
            COROUTINE_SUSPENDED
        }
        Mockito.`when`(flow.api.sessionRename(SessionRenameRequest("s", "Renamed during list")))
            .thenReturn(RpcResult.Ok(SessionRenameValue(title = "Renamed during list", seq = 3)))

        flow.store.refreshSessions()
        assertEquals("Old title", flow.store.sessions.value.single().title)
        val recovery = async { flow.store.refreshSessions() }
        try {
            withTimeout(5_000) { entered.await() }
            flow.store.renameSession("s", "Renamed during list")
            assertEquals("Renamed during list", flow.store.sessions.value.single().title)

            release.complete(Unit)
            recovery.await()
            val applied = withTimeout(5_000) {
                flow.store.sessions.first { rows -> rows.any { it.sessionId == "recovery-only" } }
            }
            assertEquals("Renamed during list", applied.single { it.sessionId == "s" }.title)

            // A later authoritative list must be accepted when no newer rename races it.
            val laterList = Json.decodeFromString(SessionListValue.serializer(),
                """{"items":[{"sessionId":"s","updatedAt":3,"running":false,"blank":false,"projections":{"asOfSeq":0,"values":{"title":"Updated on server"}}}]}""")
            Mockito.`when`(flow.api.sessionList(null)).thenReturn(RpcResult.Ok(laterList))
            flow.store.refreshSessions()
            assertEquals("Updated on server", flow.store.sessions.value.single().title)
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun `session without title finishes with a clean notification`() = withFlow { flow ->
        flow.addSession("untitled", null)
        withTimeout(5_000) { flow.store.sessions.first { it.any { row -> row.sessionId == "untitled" } } }
        assertEquals("Session finished", flow.finish("untitled"))
    }

    private fun withFlow(block: suspend CoroutineScope.(Fixture) -> Unit) = runBlocking {
        val flow = Fixture()
        try {
            withTimeout(30_000) { block(flow) }
        } finally {
            flow.close()
        }
    }

    private class Fixture {
        private val context: Context = RuntimeEnvironment.getApplication()
        private val frames = MutableSharedFlow<RemoteEventFrame>(extraBufferCapacity = 16)
        private val connection = mock(ConnectionManager::class.java)
        val api = mock(DshApiClient::class.java)
        private val hosts = mock(HostsStore::class.java)
        private val navigation = mock(AppNavigationState::class.java)
        private val observer: NotificationObserver
        val store: SessionStore

        init {
            `when`(connection.generation).thenReturn(
                HostGeneration(HostDescription("/home"), "g1", mock(RemoteStreamMux::class.java)),
            )
            `when`(connection.connectedApi).thenReturn(api)
            `when`(connection.eventFrames).thenReturn(frames)
            `when`(connection.connectedGenerations).thenReturn(MutableSharedFlow())
            `when`(connection.harnessProtocolChanges).thenReturn(MutableStateFlow(null))
            `when`(connection.state).thenReturn(MutableStateFlow(ConnectionUiState(host = HostConfig("host", "Host", "localhost", 3080))))
            `when`(hosts.settings).thenReturn(MutableStateFlow(AppSettings()))
            store = SessionStore(connection)
            observer = NotificationObserver(store, connection, DshNotifications(context), hosts, navigation, context)
            try {
                observer.start()
            } catch (t: Throwable) {
                close()
                throw t
            }
        }

        fun close() {
            cancelScope(store)
            cancelScope(observer)
        }

        /** Both durable stores own an internal retry scope that outlives a test unless cancelled. */
        private fun cancelScope(target: Any) {
            val field = requireNotNull(target.javaClass.declaredFields.firstOrNull { CoroutineScope::class.java.isAssignableFrom(it.type) }) {
                "no CoroutineScope field on ${target.javaClass.name}"
            }.apply { isAccessible = true }
            (field.get(target) as CoroutineScope).cancel()
        }

        suspend fun addSession(id: String, titleJson: String?) {
            withTimeout(5_000) { frames.subscriptionCount.first { it >= 2 } }
            val projection = titleJson?.let { ",\"projections\":{\"asOfSeq\":0,\"values\":{\"title\":$it}}" }.orEmpty()
            val summary = Json.parseToJsonElement("""{"sessionId":"$id","updatedAt":1,"running":false,"blank":false$projection}""")
            frames.emit(RemoteEventFrame.Emit(event = "api-session/added", args = listOf(summary)))
        }

        suspend fun removeSession(id: String) {
            withTimeout(5_000) { frames.subscriptionCount.first { it >= 2 } }
            frames.emit(RemoteEventFrame.Emit(event = "api-session/removed", args = listOf(JsonPrimitive(id))))
        }

        suspend fun finish(id: String): String {
            frames.emit(RemoteEventFrame.Emit(event = "api-session/status", args = listOf(JsonPrimitive(id), JsonPrimitive(true))))
            frames.emit(RemoteEventFrame.Emit(event = "api-session/status", args = listOf(JsonPrimitive(id), JsonPrimitive(false))))
            val manager = context.getSystemService(NotificationManager::class.java)
            val notification = withTimeout(5_000) {
                while (shadowOf(manager).allNotifications.isEmpty()) delay(10)
                shadowOf(manager).allNotifications.single()
            }
            return notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        }
    }
}
