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
import dev.dsh.mobile.mesh.core.wire.RpcHttpResponse
import dev.dsh.mobile.mesh.core.wire.RpcResult
import dev.dsh.mobile.mesh.core.wire.RpcTransport
import dev.dsh.mobile.mesh.core.wire.RpcTransportException
import dev.dsh.mobile.mesh.core.wire.TransportFailure
import dev.dsh.mobile.mesh.core.wire.TransportFailures
import dev.dsh.mobile.mesh.core.wire.WsChannel
import dev.dsh.mobile.mesh.core.wire.WsChannelSink
import dev.dsh.mobile.mesh.core.wire.dto.HostDescription
import dev.dsh.mobile.mesh.core.wire.dto.RemoteEventFrame
import dev.dsh.mobile.mesh.core.wire.dto.ScheduleCatalogEntry
import dev.dsh.mobile.mesh.core.wire.dto.ScheduleEditResult
import dev.dsh.mobile.mesh.core.wire.dto.ScheduleTarget
import dev.dsh.mobile.mesh.core.wire.dto.SubagentInterruptValue
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Route negotiation for Host mutations whose wire form changed between releases.
 *
 * The app must keep an old Host working while talking to the newest one, and it may only switch
 * on structured missing-route evidence — a timeout or an access refusal is not proof that a
 * route is absent. Reminder tests drive a real [DshApiClient] over a scripted carrier so the
 * observable outcome is the actual HTTP route chosen; interrupt tests keep the mocked client
 * because the route difference is a call, not a payload.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class SessionStoreHostMutationRoutesTest {
    private lateinit var mux: RemoteStreamMux
    private lateinit var manager: ConnectionManager
    private val generationRef = AtomicReference<HostGeneration?>(null)
    private val apiRef = AtomicReference<DshApiClient?>(null)

    private class FakeChannel(private val sink: WsChannelSink) :
        WsChannel("http://stub/api/remote.mux", OkHttpClient(), sink) {
        override fun start() = sink.onOpen()
        override fun send(text: String): Boolean = true
        override fun close() = Unit
    }

    /** Carrier that scripts one response per Remote path and records the routes that ran. */
    private class RecordingTransport : RpcTransport {
        val paths = CopyOnWriteArrayList<String>()
        var editValue: String? = null
        var editError: String? = null
        var legacyUpdateValue: String? = null
        var legacyDeleteValue: String? = null
        var onPost: suspend (String) -> Unit = {}
        var conflictWithUpdatedHistoricalRow = false

        override suspend fun post(path: String, body: String): RpcHttpResponse {
            paths += path
            onPost(path)
            val envelope = Json.parseToJsonElement(body).jsonObject
            val rpcId = envelope.getValue("rpcId").jsonPrimitive.content
            val value = when (path) {
                "/api/schedule/edit" -> {
                    when (editError) {
                        // An unclaimed unary endpoint is answered with a plain HTTP 404 before dispatch.
                        "missing" -> throw RpcTransportException(404, "not found")
                        "forbidden" -> throw RpcTransportException(403, "forbidden")
                        "internal-failure" -> throw RpcTransportException(500, "boom")
                    }
                    if (conflictWithUpdatedHistoricalRow) {
                        val request = envelope.getValue("payload").jsonObject.getValue("args").jsonObject.getValue("request").jsonObject
                        if (request["expectedUpdatedAt"] == kotlinx.serialization.json.JsonNull) {
                            """{"id":"task-1","code":"schedule_conflict"}"""
                        } else editValue
                    } else editValue
                }
                "/api/schedule/update" -> legacyUpdateValue ?: throw RpcTransportException(404, "not found")
                "/api/schedule/delete" -> legacyDeleteValue ?: throw RpcTransportException(404, "not found")
                else -> throw RpcTransportException(404, "not found")
            } ?: throw RpcTransportException(404, "not found")
            return RpcHttpResponse(
                200,
                """{"type":"server-response","rpcId":"$rpcId","result":{"ok":true,"value":$value}}""",
            )
        }

        override suspend fun <T> download(
            path: String,
            consume: (String?, String?, InputStream) -> T,
        ): T = error("download not expected")

        override suspend fun upload(
            path: String,
            contentType: String,
            contentLength: Long,
            body: InputStream,
            onProgress: ((Long) -> Unit)?,
        ): RpcHttpResponse = error("upload not expected")

    }

    /** The error a unary call answers when the Host never claimed its endpoint. */
    private fun missingUnaryRoute(): RpcError = RpcError(
        code = "capability-unavailable",
        message = "carrier returned HTTP 404",
        details = TransportFailures.details(TransportFailure.NOT_FOUND, 404),
    )

    @Before
    fun setUp() {
        mux = RemoteStreamMux { sink -> FakeChannel(sink) }
        mux.start()
    }

    @After
    fun tearDown() {
        store?.let { instance ->
            val scope = SessionStore::class.java.getDeclaredField("scope").apply { isAccessible = true }
            (scope.get(instance) as CoroutineScope).cancel()
        }
        store = null
        mux.close()
    }

    private var store: SessionStore? = null

    private suspend fun newStore(api: DshApiClient): SessionStore {
        mux.awaitOpen()
        manager = Mockito.mock(ConnectionManager::class.java)
        Mockito.`when`(manager.state).thenReturn(
            MutableStateFlow(
                ConnectionUiState(
                    phase = ConnectionPhase.CONNECTING,
                    host = HostConfig("host", "Host", "localhost", 3080),
                ),
            ),
        )
        val generation = HostGeneration(HostDescription("/home"), "g1", mux)
        generationRef.set(generation)
        apiRef.set(api)
        // Answers read the current generation/API so a test can simulate a carrier replacement
        // without re-stubbing a mock that the store's coroutines are using concurrently.
        Mockito.`when`(manager.generation).thenAnswer { generationRef.get() }
        Mockito.`when`(manager.connectedApi).thenAnswer { apiRef.get() }
        Mockito.`when`(manager.connectedGenerations).thenReturn(MutableSharedFlow<HostGeneration>())
        Mockito.`when`(manager.eventFrames).thenReturn(MutableSharedFlow<RemoteEventFrame>())
        Mockito.`when`(manager.harnessProtocolChanges).thenReturn(MutableStateFlow<HarnessProtocol?>(null))
        return SessionStore(manager).also { store = it }
    }

    private suspend fun awaitCondition(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(10)
    }

    private fun scheduledTask() = ScheduleCatalogEntry(
        id = "task-1",
        kind = "daily",
        title = "Check",
        prompt = "Check the build",
        scheduledAt = "2026-11-10T00:00:00.000Z",
        status = "active",
        updatedAt = "2026-11-09T01:00:00.000Z",
        target = ScheduleTarget(kind = "session", sessionId = "s-1"),
    )

    // ------------------------------------------------------------------ reminder edits

    @Test
    fun `reminder update uses the modern edit route and reports a committed row`() = runBlocking<Unit> {
        val transport = RecordingTransport().apply { editValue = """{"id":"task-1","kind":"daily"}""" }
        val instance = newStore(DshApiClient(transport))

        val outcome = instance.updateScheduleTask(
            task = scheduledTask(),
            title = "Renamed",
            prompt = "Check the build",
            timingChange = null,
            legacyExpected = JsonObject(emptyMap()),
        )

        assertTrue(outcome.reload)
        assertNull(outcome.message)
        assertEquals(listOf("/api/schedule/edit"), transport.paths.toList())
    }

    @Test
    fun `reminder update falls back to the legacy route only on missing-route evidence`() = runBlocking<Unit> {
        val transport = RecordingTransport().apply {
            editError = "missing"
            legacyUpdateValue = """{"id":"task-1","updated":true,"record":{}}"""
        }
        val instance = newStore(DshApiClient(transport))

        val first = instance.updateScheduleTask(
            scheduledTask(), "Renamed", "Check the build", null, JsonObject(emptyMap()),
        )
        assertTrue(first.reload)

        // The generation remembered the legacy answer: a later edit must not probe again.
        instance.updateScheduleTask(
            scheduledTask(), "Renamed again", "Check the build", null, JsonObject(emptyMap()),
        )
        assertEquals(1, transport.paths.count { it == "/api/schedule/edit" })
        assertEquals(2, transport.paths.count { it == "/api/schedule/update" })
    }

    @Test
    fun `reminder edit conflict reloads the stale row instead of retrying`() = runBlocking<Unit> {
        val transport = RecordingTransport().apply { editValue = """{"id":"task-1","code":"schedule_conflict"}""" }
        val instance = newStore(DshApiClient(transport))

        val outcome = instance.updateScheduleTask(
            scheduledTask(), "Renamed", "Check the build", null, JsonObject(emptyMap()),
        )

        assertTrue(outcome.reload)
        assertEquals("schedule_conflict", outcome.message)
        assertEquals(1, transport.paths.count { it == "/api/schedule/edit" })
        assertEquals(0, transport.paths.count { it == "/api/schedule/update" })
    }

    @Test
    fun `reminder delete uses the edit delete action`() = runBlocking<Unit> {
        val transport = RecordingTransport().apply { editValue = """{"id":"task-1","deleted":true}""" }
        val instance = newStore(DshApiClient(transport))

        val outcome = instance.deleteScheduleTask(scheduledTask())

        assertTrue(outcome.reload)
        assertEquals(listOf("/api/schedule/edit"), transport.paths.toList())
        assertEquals(0, transport.paths.count { it == "/api/schedule/delete" })
    }

    @Test
    fun `reminder delete falls back to the legacy route on an older host`() = runBlocking<Unit> {
        val transport = RecordingTransport().apply {
            editError = "missing"
            legacyDeleteValue = """{"id":"task-1","deleted":true}"""
        }
        val instance = newStore(DshApiClient(transport))

        val outcome = instance.deleteScheduleTask(scheduledTask())

        assertTrue(outcome.reload)
        assertEquals(1, transport.paths.count { it == "/api/schedule/edit" })
        assertEquals(1, transport.paths.count { it == "/api/schedule/delete" })
    }

    @Test
    fun `an unrelated edit failure is reported without falling back`() = runBlocking<Unit> {
        val transport = RecordingTransport().apply { editError = "internal-failure" }
        val instance = newStore(DshApiClient(transport))

        val outcome = instance.updateScheduleTask(
            scheduledTask(), "Renamed", "Check the build", null, JsonObject(emptyMap()),
        )

        assertFalse(outcome.reload)
        assertEquals(1, transport.paths.count { it == "/api/schedule/edit" })
        assertEquals(0, transport.paths.count { it == "/api/schedule/update" })
    }

    @Test
    fun `an authentication refusal is not mistaken for a missing edit route`() = runBlocking<Unit> {
        val transport = RecordingTransport().apply { editError = "forbidden" }
        val instance = newStore(DshApiClient(transport))

        val outcome = instance.updateScheduleTask(
            scheduledTask(), "Renamed", "Check the build", null, JsonObject(emptyMap()),
        )

        assertFalse(outcome.reload)
        assertEquals(listOf("/api/schedule/edit"), transport.paths.toList())
        assertEquals(0, transport.paths.count { it == "/api/schedule/update" })
    }

    @Test
    fun `editing a historical task reports a concurrent update instead of overwriting it`() = runBlocking<Unit> {
        val transport = RecordingTransport().apply {
            editValue = """{"id":"task-1","kind":"daily"}"""
            conflictWithUpdatedHistoricalRow = true
        }
        val instance = newStore(DshApiClient(transport))
        val outcome = instance.updateScheduleTask(scheduledTask().copy(updatedAt = null), "Changed", "Check", null, JsonObject(emptyMap()))
        assertTrue(outcome.reload)
        assertEquals("schedule_conflict", outcome.message)
        assertEquals(listOf("/api/schedule/edit"), transport.paths.toList())
    }

    @Test
    fun `host switch during missing edit does not fall back or publish stale result`() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val old = RecordingTransport().apply {
            editError = "missing"
            legacyUpdateValue = """{"id":"task-1","updated":true,"record":{}}"""
            onPost = { if (it == "/api/schedule/edit") { entered.complete(Unit); release.await() } }
        }
        val instance = newStore(DshApiClient(old))
        val pending = async { instance.updateScheduleTask(scheduledTask(), "Changed", "Check", null, JsonObject(emptyMap()), "g1") }
        entered.await()
        val replacement = RecordingTransport()
        generationRef.set(HostGeneration(HostDescription("/other"), "g2", mux))
        apiRef.set(DshApiClient(replacement))
        release.complete(Unit)
        val result = pending.await()
        assertFalse(result.reload)
        assertNull(result.message)
        assertEquals(listOf("/api/schedule/edit"), old.paths.toList())
        assertTrue(replacement.paths.isEmpty())
    }

    @Test
    fun `stale delete never reaches replacement host`() = runBlocking<Unit> {
        val transport = RecordingTransport()
        val instance = newStore(DshApiClient(transport))
        generationRef.set(HostGeneration(HostDescription("/other"), "g2", mux))
        val result = instance.deleteScheduleTask(scheduledTask(), "g1")
        assertFalse(result.reload)
        assertTrue(transport.paths.isEmpty())
    }

    // ------------------------------------------------------------------ subagent interrupts

    private suspend fun openParent(instance: SessionStore) {
        instance.openSession("parent")
        awaitCondition { instance.currentSessionId.value == "parent" }
    }

    @Test
    fun `subagent interrupt uses the modern user route when the host answers it`() = runBlocking<Unit> {
        val api = Mockito.mock(DshApiClient::class.java)
        Mockito.`when`(api.subagentInterruptByUser("child"))
            .thenReturn(RpcResult.Ok(SubagentInterruptValue()))
        val instance = newStore(api)
        openParent(instance)

        instance.interruptSubagent("child")

        Mockito.verify(api, Mockito.never()).subagentInterrupt(Mockito.anyString(), Mockito.anyString())
    }

    @Test
    fun `subagent interrupt falls back to the parent route on a released host`() = runBlocking<Unit> {
        val api = Mockito.mock(DshApiClient::class.java)
        Mockito.`when`(api.subagentInterruptByUser("child"))
            .thenReturn(RpcResult.Err(missingUnaryRoute()))
        Mockito.`when`(api.subagentInterrupt("child", "parent"))
            .thenReturn(RpcResult.Ok(SubagentInterruptValue()))
        val instance = newStore(api)
        openParent(instance)

        instance.interruptSubagent("child")
        // The fallback answer is remembered for this generation.
        instance.interruptSubagent("child")

        Mockito.verify(api, Mockito.times(1)).subagentInterruptByUser("child")
        Mockito.verify(api, Mockito.times(2)).subagentInterrupt("child", "parent")
    }

    @Test
    fun `subagent interrupt reports an unrelated failure without falling back`() = runBlocking<Unit> {
        val api = Mockito.mock(DshApiClient::class.java)
        Mockito.`when`(api.subagentInterruptByUser("child"))
            .thenReturn(RpcResult.Err(RpcError("gateway/internal", "boom")))
        val instance = newStore(api)
        openParent(instance)

        instance.interruptSubagent("child")

        assertEquals("boom", instance.connectionError.value)
        Mockito.verify(api, Mockito.never()).subagentInterrupt(Mockito.anyString(), Mockito.anyString())
    }
}
