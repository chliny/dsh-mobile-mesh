package dev.dsh.mobile.mesh.ui.screens.main

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.core.wire.RpcResult
import dev.dsh.mobile.mesh.core.wire.dto.TerminalCreateRequest
import dev.dsh.mobile.mesh.core.wire.dto.WebTerminalInfo
import dev.dsh.mobile.mesh.ui.rememberConnectionManager
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The first successful list or explicit request claims the initial PTY slot, exactly once. */
internal class InitialTerminalCreation {
    var pending = true
        private set

    fun listed(existingCount: Int): Boolean {
        if (!pending) return false
        pending = false
        return existingCount == 0
    }

    fun manual() { pending = false }
}

/** One session-owned Host PTY. Navigating away detaches it without terminating the shell. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun TerminalScreen(sessionId: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val manager = rememberConnectionManager()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var terminals by remember(sessionId) { mutableStateOf<List<WebTerminalInfo>>(emptyList()) }
    var selected by remember(sessionId) { mutableStateOf<String?>(null) }
    var status by remember(sessionId) { mutableStateOf("") }
    var maxDimensions by remember(sessionId) { mutableStateOf(500 to 200) }
    var ctrl by remember { mutableStateOf(false) }
    var shift by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var attachment by remember { mutableStateOf<String?>(null) }
    var inputQueue by remember { mutableStateOf<TerminalInputQueue?>(null) }
    val currentInputQueue by rememberUpdatedState(inputQueue)
    val currentAttachment by rememberUpdatedState(attachment)
    val currentSelected by rememberUpdatedState(selected)
    val currentMaxDimensions by rememberUpdatedState(maxDimensions)
    val currentCtrl by rememberUpdatedState(ctrl)
    val currentShift by rememberUpdatedState(shift)
    val currentAlt by rememberUpdatedState(alt)
    var webReady by remember { mutableStateOf(false) }
    var dimensions by remember { mutableStateOf(80 to 24) }
    var screen by remember { mutableStateOf<WebView?>(null) }
    val initialCreation = remember(sessionId) { InitialTerminalCreation() }
    var creating by remember(sessionId) { mutableStateOf(false) }
    var refreshVersion by remember(sessionId) { mutableStateOf(0) }
    suspend fun createTerminal(onCreated: suspend () -> Unit) {
        if (creating) return
        creating = true
        try {
            when (val result = manager.connectedApi?.terminalCreate(sessionId, TerminalCreateRequest(
                UUID.randomUUID().toString(), dimensions.first.coerceIn(2, maxDimensions.first), dimensions.second.coerceIn(1, maxDimensions.second),
            ))) {
                is RpcResult.Ok -> { selected = result.value.id; onCreated() }
                is RpcResult.Err -> status = result.error.message
                null -> status = context.getString(R.string.terminal_offline)
            }
        } finally { creating = false }
    }
    suspend fun refresh() {
        val version = ++refreshVersion
        when (val result = manager.connectedApi?.terminalList(sessionId)) {
            is RpcResult.Ok -> if (version == refreshVersion) {
                terminals = result.value
                if (selected !in terminals.map { it.id }) selected = terminals.firstOrNull()?.id
                if (initialCreation.listed(terminals.size) && !creating) createTerminal { refresh() }
            }
            is RpcResult.Err -> if (version == refreshVersion) status = result.error.message
            null -> if (version == refreshVersion) status = context.getString(R.string.terminal_offline)
        }
    }
    fun render(name: String, text: String) {
        // JSON string encoding is a valid JavaScript string literal (quotes, controls and emojis).
        val literal = JsonPrimitive(text).toString()
        screen?.evaluateJavascript("$name($literal)", null)
    }
    fun write(data: String) {
        if (inputQueue?.offer(data) == false) status = context.getString(R.string.terminal_input_full)
    }
    LaunchedEffect(sessionId) {
        suspend fun updateFromHost() {
            when (val env = manager.connectedApi?.terminalEnvironment(sessionId)) {
                is RpcResult.Ok -> maxDimensions = env.value.maxCols to env.value.maxRows
                else -> Unit
            }
            refresh()
        }
        updateFromHost()
        manager.connectedGenerations.collect { updateFromHost() }
    }
    LaunchedEffect(selected, webReady, screen, manager.generation) {
        val id = selected ?: return@LaunchedEffect
        if (!webReady) return@LaunchedEffect
        // Each attachment has its own control identity; the server supplies the baseline screen.
        while (isActive) {
            val generation = manager.generation
            if (generation == null || manager.connectedApi == null) {
                status = context.getString(R.string.terminal_offline)
                delay(1000)
                continue
            }
            val token = UUID.randomUUID().toString()
            val args = buildJsonObject {
                put("agentId", JsonPrimitive(sessionId))
                put("id", JsonPrimitive(id))
                put("attachmentId", JsonPrimitive(token))
            }
            val hold = generation.mux.openStream("terminal/retain", buildJsonObject {
                put("sessionId", JsonPrimitive(sessionId)); put("id", JsonPrimitive(id))
            })
            var holdJob: Job? = null
            var writerJob: Job? = null
            try {
                // A hold survives transient output follower disconnections until leaving this view.
                val retained = CompletableDeferred<Unit>()
                holdJob = launch {
                    try {
                        hold.collect { frame ->
                            if (frame.jsonObject["type"]?.jsonPrimitive?.content == "retained") retained.complete(Unit)
                        }
                        if (!retained.isCompleted) retained.completeExceptionally(IllegalStateException("Terminal hold ended"))
                    } catch (failure: Exception) {
                        if (!retained.isCompleted) retained.completeExceptionally(failure)
                    }
                }
                kotlinx.coroutines.withTimeout(10_000) { retained.await() }
                var sequence: Int? = null
                generation.mux.openStream("terminal/follow", args).collect { raw ->
                    val frame = raw.jsonObject
                    when (frame["type"]?.jsonPrimitive?.content) {
                        "snapshot" -> {
                            sequence = frame["sequence"]?.jsonPrimitive?.intOrNull
                            render("terminalReset", frame["screen"]?.jsonPrimitive?.content.orEmpty())
                            val info = frame["info"]?.jsonObject
                            if (info?.get("controllerId")?.jsonPrimitive?.content == token) {
                                val queue = TerminalInputQueue { data ->
                                    when (val result = manager.connectedApi?.terminalWrite(sessionId, id, token, data)) {
                                        is RpcResult.Err -> status = result.error.message
                                        null -> status = context.getString(R.string.terminal_offline)
                                        else -> Unit
                                    }
                                }
                                inputQueue = queue
                                attachment = token
                                scope.launch {
                                    when (val result = manager.connectedApi?.terminalResize(sessionId, id, token,
                                        dimensions.first.coerceIn(2, currentMaxDimensions.first),
                                        dimensions.second.coerceIn(1, currentMaxDimensions.second))) {
                                        is RpcResult.Err -> status = result.error.message
                                        else -> Unit
                                    }
                                }
                                writerJob = launch { queue.drain() }
                            }
                            status = ""
                        }
                        "output" -> {
                            val next = frame["sequence"]?.jsonPrimitive?.intOrNull
                            if (sequence == null || next != sequence!! + 1) error("Terminal output gap")
                            sequence = next
                            render("terminalWrite", frame["data"]?.jsonPrimitive?.content.orEmpty())
                        }
                        "state" -> {
                            if (frame["info"]?.jsonObject?.get("state")?.jsonPrimitive?.content != "running") refresh()
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { status = failure.message.orEmpty() }
            finally {
                inputQueue?.close()
                inputQueue = null
                attachment = null
                writerJob?.cancel()
                holdJob?.cancel()
            }
            delay(1000)
        }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.common_back)) }
            Text(stringResource(R.string.terminal_title), modifier = Modifier.weight(1f).padding(top = 12.dp))
            TextButton(onClick = {
                // An explicit request wins over an in-flight initial list; never auto-create a second PTY.
                initialCreation.manual()
                scope.launch { createTerminal { refresh() } }
            }, enabled = !creating && (!initialCreation.pending || status.isNotEmpty())) { Text("+") }
            TextButton(onClick = {
                val id = selected ?: return@TextButton
                scope.launch {
                    when (val result = manager.connectedApi?.terminalClose(sessionId, id)) {
                        is RpcResult.Ok -> { selected = null; refresh() }
                        is RpcResult.Err -> status = result.error.message
                        null -> status = context.getString(R.string.terminal_offline)
                    }
                }
            }, enabled = selected != null) { Text("×") }
        }
        if (terminals.size > 1) Row(Modifier.horizontalScroll(rememberScrollState())) {
            terminals.forEach { terminal ->
                TextButton(onClick = { selected = terminal.id }) { Text(terminal.title) }
            }
        }
        if (status.isNotEmpty()) Text(status)
        AndroidView(
            factory = {
                WebView(it).apply {
                    if (dev.dsh.mobile.mesh.BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
                    setBackgroundColor(android.graphics.Color.rgb(16, 20, 29))
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = true
                    settings.allowContentAccess = false
                    settings.domStorageEnabled = false
                    settings.allowFileAccessFromFileURLs = false
                    settings.allowUniversalAccessFromFileURLs = false
                    addJavascriptInterface(object {
                        @JavascriptInterface fun input(data: String) { post {
                            val modified = terminalTextInput(data, currentCtrl, currentShift, currentAlt)
                            if (currentInputQueue?.offer(modified) == false) status = context.getString(R.string.terminal_input_full)
                            ctrl = false; shift = false; alt = false
                        } }
                        @JavascriptInterface fun resize(cols: Int, rows: Int) {
                            post {
                                dimensions = cols to rows
                                val id = currentSelected
                                val token = currentAttachment
                                if (id != null && token != null) scope.launch {
                                    when (val result = manager.connectedApi?.terminalResize(sessionId, id, token,
                                        cols.coerceIn(2, currentMaxDimensions.first), rows.coerceIn(1, currentMaxDimensions.second))) {
                                        is RpcResult.Err -> status = result.error.message
                                        else -> Unit
                                    }
                                }
                            }
                        }
                    }, "AndroidTerminal")
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                            request.url.toString() != "file:///android_asset/terminal/index.html"

                        override fun onPageFinished(view: WebView, url: String) {
                            webReady = url == "file:///android_asset/terminal/index.html"
                        }
                    }
                    screen = this
                    loadUrl("file:///android_asset/terminal/index.html")
                }
            }, modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { screen?.evaluateJavascript("terminalFocus()", null) }) { Text("⌨") }
            Row { Checkbox(checked = ctrl, onCheckedChange = { ctrl = it }); Text("Ctrl") }
            Row { Checkbox(checked = shift, onCheckedChange = { shift = it }); Text("Shift") }
            Row { Checkbox(checked = alt, onCheckedChange = { alt = it }); Text("Alt") }
            listOf("Esc", "Tab", "Up", "Down", "Left", "Right", "Delete", "Insert", "Home", "End", "PageUp", "PageDown", "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12").forEach { key ->
                TextButton(onClick = {
                    write(terminalKey(key, ctrl, shift, alt))
                    ctrl = false; shift = false; alt = false
                }) { Text(key) }
            }
        }
    }
}
