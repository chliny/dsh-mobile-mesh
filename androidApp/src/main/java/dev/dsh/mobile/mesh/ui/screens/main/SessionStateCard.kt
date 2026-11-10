package dev.dsh.mobile.mesh.ui.screens.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.connection.missingRemoteEndpoint
import dev.dsh.mobile.mesh.connection.missingUnaryRemoteEndpoint
import dev.dsh.mobile.mesh.core.wire.DshApiClient
import dev.dsh.mobile.mesh.core.wire.HostGeneration
import dev.dsh.mobile.mesh.core.wire.RemoteStreamException
import dev.dsh.mobile.mesh.core.wire.RpcResult
import dev.dsh.mobile.mesh.core.wire.decodeFromJsonElement
import dev.dsh.mobile.mesh.core.wire.dto.SessionPassiveState
import dev.dsh.mobile.mesh.core.wire.dto.SessionWaitValue
import dev.dsh.mobile.mesh.ui.components.DsButton
import dev.dsh.mobile.mesh.ui.components.DsButtonSize
import dev.dsh.mobile.mesh.ui.components.DsButtonVariant
import dev.dsh.mobile.mesh.ui.theme.DsTheme
import dev.dsh.mobile.mesh.ui.theme.DsType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Passive Host status for one visible ordinary Session. The caller must remove this card as soon
 * as the connection is no longer authoritative, and pass the API bound to [generation].
 */
@Composable
internal fun SessionStateCard(sessionId: String, generation: HostGeneration, api: DshApiClient) {
    key(sessionId, generation, api) {
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val request = remember(sessionId) { JsonObject(mapOf("request" to JsonObject(mapOf("sessionId" to JsonPrimitive(sessionId))))) }
        val waitRequest = remember(sessionId) {
            JsonObject(mapOf("request" to JsonObject(mapOf("sessionId" to JsonPrimitive(sessionId), "timeoutMs" to JsonPrimitive(20_000)))))
        }
        val waitRequests = remember { Channel<Unit>(Channel.RENDEZVOUS) }
        var refresh by remember { mutableIntStateOf(0) }
        var state by remember { mutableStateOf<SessionPassiveState?>(null) }
        var unsupported by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf(StateDisplay.Loading) }
        var stateError by remember { mutableStateOf<String?>(null) }
        var waitActive by remember { mutableStateOf(false) }
        var waitResult by remember { mutableStateOf<SessionWaitValue?>(null) }
        var waitError by remember { mutableStateOf<String?>(null) }
        var waitUnsupported by remember { mutableStateOf(false) }
        var started by remember { mutableStateOf(false) }

        LaunchedEffect(lifecycle, refresh) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                state = null
                unsupported = false
                status = StateDisplay.Loading
                stateError = null
                try {
                    when (val initial = api.call("session/state", request, SessionPassiveState.serializer())) {
                        is RpcResult.Err -> {
                            if (missingUnaryRemoteEndpoint(initial.error.code, initial.error.details, "session/state")) unsupported = true
                            else {
                                stateError = initial.error.message
                                status = StateDisplay.Error
                            }
                            return@repeatOnLifecycle
                        }
                        is RpcResult.Ok -> {
                            state = initial.value
                            status = StateDisplay.Snapshot
                        }
                    }
                    try {
                        generation.mux.openStream("session/observeState", request).collect { value ->
                            state = decodeFromJsonElement(SessionPassiveState.serializer(), value)
                            status = StateDisplay.Live
                            stateError = null
                        }
                        status = StateDisplay.Stale
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: RemoteStreamException) {
                        if (!error.carrier && missingRemoteEndpoint(error.error.code, error.error.details, "session/observeState")) {
                            status = StateDisplay.Snapshot
                        } else {
                            status = StateDisplay.Stale
                            stateError = error.error.message
                        }
                    } catch (error: Exception) {
                        status = StateDisplay.Stale
                        stateError = error.message
                    }
                } catch (cancelled: CancellationException) {
                    // A stopped screen must not present its last sample as live on return.
                    state = null
                    status = StateDisplay.Loading
                    throw cancelled
                } catch (error: Exception) {
                    stateError = error.message
                    status = StateDisplay.Error
                }
            }
        }

        LaunchedEffect(lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                started = true
                try {
                    for (ignored in waitRequests) {
                        try {
                            when (val result = api.call("session/wait", waitRequest, SessionWaitValue.serializer())) {
                                is RpcResult.Ok -> {
                                    waitResult = result.value
                                    waitError = null
                                }
                                is RpcResult.Err -> {
                                    if (missingUnaryRemoteEndpoint(result.error.code, result.error.details, "session/wait")) {
                                        waitUnsupported = true
                                    } else waitError = result.error.message
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            waitError = error.message ?: error.javaClass.simpleName
                        } finally {
                            waitActive = false
                        }
                    }
                } finally {
                    started = false
                    waitActive = false
                    waitResult = null
                    waitError = null
                }
            }
        }

        if (!unsupported) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.session_state_title), style = DsType.small13, color = DsTheme.colors.labelPrimary)
                state?.let { current ->
                    Text(stringResource(if (current.active) R.string.session_state_active else R.string.session_state_inactive), style = DsType.caption11)
                    Text(stringResource(if (current.running) R.string.session_state_running else R.string.session_state_not_running), style = DsType.caption11)
                    val queued = current.queue
                    Text(
                        if (queued == null) stringResource(R.string.session_state_queue_unavailable)
                        else stringResource(R.string.session_state_queue_count, queued.size),
                        style = DsType.caption11,
                    )
                    val pending = current.pendingInput.map {
                        stringResource(when (it) {
                            "approval" -> R.string.session_state_pending_approval
                            "question" -> R.string.session_state_pending_question
                            else -> R.string.session_state_pending_other
                        })
                    }.joinToString(", ").ifEmpty { stringResource(R.string.session_state_pending_none) }
                    Text(stringResource(R.string.session_state_pending_sample, pending), style = DsType.caption11)
                }
                Text(stringResource(when (status) {
                    StateDisplay.Loading -> R.string.session_state_loading
                    StateDisplay.Live -> R.string.session_state_live
                    StateDisplay.Snapshot -> R.string.session_state_snapshot
                    StateDisplay.Stale -> R.string.session_state_stale
                    StateDisplay.Error -> R.string.session_state_error
                }), style = DsType.caption11)
                stateError?.let { Text(it, style = DsType.caption11, color = DsTheme.colors.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DsButton(
                        text = stringResource(R.string.session_state_refresh),
                        onClick = { refresh++ },
                        variant = DsButtonVariant.Outline,
                        size = DsButtonSize.Small,
                    )
                    if (state != null && !waitUnsupported) {
                        DsButton(
                            text = stringResource(if (waitActive) R.string.session_wait_waiting else R.string.session_wait_action),
                            enabled = started && !waitActive,
                            onClick = {
                                waitResult = null
                                waitError = null
                                waitActive = true
                                if (waitRequests.trySend(Unit).isFailure) waitActive = false
                            },
                            variant = DsButtonVariant.Outline,
                            size = DsButtonSize.Small,
                        )
                    }
                }
                if (waitUnsupported) Text(stringResource(R.string.session_wait_unsupported), style = DsType.caption11)
                waitResult?.let { result ->
                    Text(stringResource(when (result.reason) {
                        "idle" -> R.string.session_wait_idle
                        "input-required" -> R.string.session_wait_input_required
                        "timeout" -> R.string.session_wait_timeout
                        else -> R.string.session_wait_unknown
                    }), style = DsType.caption11)
                    if (result.output.truncated) Text(stringResource(R.string.session_wait_truncated), style = DsType.caption11)
                }
                waitError?.let {
                    Text(stringResource(R.string.session_wait_error, it), style = DsType.caption11, color = DsTheme.colors.error)
                }
            }
        }
    }
}

private enum class StateDisplay { Loading, Live, Snapshot, Stale, Error }
