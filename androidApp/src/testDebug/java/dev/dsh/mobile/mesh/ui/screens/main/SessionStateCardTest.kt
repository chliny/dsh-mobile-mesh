package dev.dsh.mobile.mesh.ui.screens.main

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.core.wire.DshApiClient
import dev.dsh.mobile.mesh.core.wire.HostGeneration
import dev.dsh.mobile.mesh.core.wire.RemoteStreamMux
import dev.dsh.mobile.mesh.core.wire.RpcHttpResponse
import dev.dsh.mobile.mesh.core.wire.RpcTransport
import dev.dsh.mobile.mesh.core.wire.RpcTransportException
import dev.dsh.mobile.mesh.core.wire.WsChannel
import dev.dsh.mobile.mesh.core.wire.WsChannelSink
import dev.dsh.mobile.mesh.core.wire.dto.HostDescription
import dev.dsh.mobile.mesh.ui.theme.DshTheme
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the visible card using a real unary client and a real logical stream mux. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class SessionStateCardTest {
    @get:Rule val compose = createComposeRule()
    private val muxes = mutableListOf<RemoteStreamMux>()
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    private class HostChannel(private val sink: WsChannelSink) :
        WsChannel("http://stub/api/remote.mux", OkHttpClient(), sink) {
        override fun start() = sink.onOpen()
        override fun close() = Unit
        override fun send(text: String): Boolean {
            val msg = Json.parseToJsonElement(text).jsonObject
            if (msg["type"]?.jsonPrimitive?.content == "open" &&
                msg["endpoint"]?.jsonPrimitive?.content == "session/observeState") {
                val id = msg.getValue("streamId").jsonPrimitive.content
                sink.onMessage("""{"type":"item","streamId":"$id","value":$LIVE_STATE}""")
            }
            return true
        }
    }

    private class HostTransport : RpcTransport {
        val paths = CopyOnWriteArrayList<String>()
        val requests = CopyOnWriteArrayList<String>()
        var stateFailure: String? = null
        var waitFailure = false
        var waitReason = "input-required"
        override suspend fun post(path: String, body: String): RpcHttpResponse {
            paths += path
            requests += body
            val id = Json.parseToJsonElement(body).jsonObject.getValue("rpcId").jsonPrimitive.content
            if ((path == "/api/session/state" && stateFailure == "network") ||
                (path == "/api/session/wait" && waitFailure)) throw RpcTransportException(0, "offline")
            if (path == "/api/session/state" && stateFailure == "missing") throw RpcTransportException(404, "not found")
            if (path == "/api/session/state" && stateFailure == "forbidden") throw RpcTransportException(403, "forbidden")
            val result = when {
                path == "/api/session/state" -> """{"ok":true,"value":$INITIAL_STATE}"""
                path == "/api/session/wait" ->
                    """{"ok":true,"value":{"reason":"$waitReason","state":$LIVE_STATE,"output":{"records":[],"truncated":true}}}"""
                else -> error("unexpected endpoint $path")
            }
            return RpcHttpResponse(200, """{"type":"server-response","rpcId":"$id","result":$result}""")
        }
        override suspend fun <T> download(path: String, consume: (String?, String?, InputStream) -> T): T = error("unexpected download")
        override suspend fun upload(path: String, contentType: String, contentLength: Long, body: InputStream, onProgress: ((Long) -> Unit)?): RpcHttpResponse = error("unexpected upload")
    }

    private fun show(host: HostTransport) {
        val mux = RemoteStreamMux { HostChannel(it) }
        muxes += mux
        mux.start()
        runBlocking { mux.awaitOpen() }
        val generation = HostGeneration(HostDescription("/workspace"), "generation", mux)
        val api = DshApiClient(host)
        compose.setContent { DshTheme { SessionStateCard("session-1", generation, api) } }
    }

    @After fun close() { muxes.forEach { it.close() } }

    @Test fun `host state stream and explicit bounded wait drive the card`() {
        val host = HostTransport()
        show(host)
        compose.waitUntil(5_000) { host.paths.contains("/api/session/state") }
        compose.onNodeWithText(context.getString(R.string.session_state_queue_count, 1)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.session_state_pending_sample, context.getString(R.string.session_state_pending_question))).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.session_wait_action)).performClick()
        compose.waitUntil(5_000) { host.paths.contains("/api/session/wait") }
        compose.onNodeWithText(context.getString(R.string.session_wait_input_required)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.session_wait_truncated)).assertIsDisplayed()
        val call = Json.parseToJsonElement(host.requests[host.paths.indexOf("/api/session/wait")]).jsonObject
        val request = call.getValue("payload").jsonObject.getValue("args").jsonObject.getValue("request").jsonObject
        assertEquals("session-1", request.getValue("sessionId").jsonPrimitive.content)
        assertEquals("20000", request.getValue("timeoutMs").jsonPrimitive.content)
        assertEquals(1, host.paths.count { it == "/api/session/wait" })
    }

    @Test fun `host timeout reports that waiting ended without stopping the session`() {
        val host = HostTransport().apply { waitReason = "timeout" }
        show(host)
        compose.waitUntil(5_000) { host.paths.contains("/api/session/state") }
        compose.onNodeWithText(context.getString(R.string.session_wait_action)).performClick()
        compose.waitUntil(5_000) { host.paths.contains("/api/session/wait") }
        compose.onNodeWithText(context.getString(R.string.session_wait_timeout)).assertIsDisplayed()
        assertEquals(1, host.paths.count { it == "/api/session/wait" })
        // Waiting never cancels the Session's execution.
        assertTrue(host.paths.none { it.contains("cancel") })
    }

    @Test fun `wait network failure is displayed and never retried automatically`() {
        val host = HostTransport().apply { waitFailure = true }
        show(host)
        compose.waitUntil(5_000) { host.paths.contains("/api/session/state") }
        compose.onNodeWithText(context.getString(R.string.session_wait_action)).performClick()
        compose.waitUntil(5_000) { host.paths.contains("/api/session/wait") }
        compose.onNodeWithText(context.getString(R.string.session_wait_timeout)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.session_wait_error, "offline")).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.session_wait_action)).assertIsDisplayed()
        assertEquals(1, host.paths.count { it == "/api/session/wait" })
    }

    @Test fun `missing state route hides card`() {
        val missing = HostTransport().apply { stateFailure = "missing" }
        show(missing)
        compose.waitUntil(5_000) { missing.paths.isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.session_state_title)).assertDoesNotExist()
    }

    @Test fun `forbidden is not mistaken for an old host`() {
        val host = HostTransport().apply { stateFailure = "forbidden" }
        show(host)
        compose.waitUntil(5_000) { host.paths.isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.session_state_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.session_state_error)).assertIsDisplayed()
    }

    @Test fun `network failure is not mistaken for an old host`() {
        val host = HostTransport().apply { stateFailure = "network" }
        show(host)
        compose.waitUntil(5_000) { host.paths.isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.session_state_error)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.session_state_refresh)).assertIsDisplayed()
    }

    companion object {
        private const val INITIAL_STATE = """{"cursor":1,"active":true,"running":true,"queue":[],"pendingInput":[]}"""
        private const val LIVE_STATE = """{"cursor":2,"active":true,"running":false,"queue":[{}],"pendingInput":["question"]}"""
    }
}
