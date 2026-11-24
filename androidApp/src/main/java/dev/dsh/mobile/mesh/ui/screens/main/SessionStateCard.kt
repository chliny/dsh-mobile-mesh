package dev.dsh.mobile.mesh.ui.screens.main

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Passive Host status for one visible ordinary Session. The caller must remove this card as soon
 * as the connection is no longer authoritative, and pass the API bound to [generation].
 */
@Composable
internal fun SessionStateCard(
    sessionId: String,
    generation: HostGeneration,
    api: DshApiClient,
    refreshSignal: Int = 0,
) {
    key(sessionId, generation, api) {
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val request = remember(sessionId) { JsonObject(mapOf("request" to JsonObject(mapOf("sessionId" to JsonPrimitive(sessionId))))) }
        var state by remember { mutableStateOf<SessionPassiveState?>(null) }
        var unsupported by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf(StateDisplay.Loading) }
        var stateError by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(lifecycle, refreshSignal) {
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

        if (!unsupported) {
            DetailSection(stringResource(R.string.session_state_title)) {
                val current = state
                DetailRow(
                    stringResource(R.string.details_field_status),
                    stringResource(
                        when {
                            current == null -> R.string.session_state_loading
                            !current.active -> R.string.details_value_inactive
                            current.running -> R.string.session_state_running
                            else -> R.string.details_value_idle
                        },
                    ),
                )
                current?.let {
                    DetailRow(
                        stringResource(R.string.details_section_queue),
                        it.queue?.size?.toString()
                            ?: stringResource(R.string.session_state_queue_unavailable),
                    )
                    val pending = it.pendingInput.map { kind ->
                        stringResource(when (kind) {
                            "approval" -> R.string.session_state_pending_approval
                            "question" -> R.string.session_state_pending_question
                            else -> R.string.session_state_pending_other
                        })
                    }.joinToString(", ").ifEmpty { stringResource(R.string.session_state_pending_none) }
                    DetailRow(stringResource(R.string.details_field_pending_input), pending)
                }
                DetailRow(
                    stringResource(R.string.details_field_source),
                    stringResource(when (status) {
                        StateDisplay.Loading -> R.string.session_state_loading
                        StateDisplay.Live -> R.string.session_state_live
                        StateDisplay.Snapshot -> R.string.session_state_snapshot
                        StateDisplay.Stale -> R.string.session_state_stale
                        StateDisplay.Error -> R.string.session_state_error
                    }),
                    valueColor = if (status == StateDisplay.Error) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                stateError?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

private enum class StateDisplay { Loading, Live, Snapshot, Stale, Error }
