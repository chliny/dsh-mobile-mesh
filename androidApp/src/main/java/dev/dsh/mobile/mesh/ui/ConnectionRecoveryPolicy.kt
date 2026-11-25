package dev.dsh.mobile.mesh.ui

import dev.dsh.mobile.mesh.connection.ConnectionPhase

internal fun shouldShowConnectionRecoveryOverlay(
    hasConnected: Boolean,
    phase: ConnectionPhase,
    foregroundCheckPending: Boolean,
    recoveryOverlayVisible: Boolean = false,
    showingConnectionList: Boolean = false,
): Boolean = !showingConnectionList && hasConnected && (
    phase != ConnectionPhase.CONNECTED || recoveryOverlayVisible || foregroundCheckPending
)

internal fun shouldRearmConnectionRecoveryOverlay(
    hasConnected: Boolean,
    recoveryInFlight: Boolean,
): Boolean = hasConnected && recoveryInFlight

/**
 * Whether an attempt to verify or rebuild an established connection is still running.
 *
 * The recovery scrim is this state's visible form. A page emitted while it runs is behind the
 * scrim, and the connection list that Back opens during the scrim stays the place to wait: the
 * attempt keeps retrying in the background, so leaving the list must remain impossible until it
 * settles. This deliberately excludes `DISCONNECTED`: an interrupted or paused attempt is no
 * longer connecting, and an authorization pause is resolved by its own prompt rather than a scrim.
 */
internal fun isConnectionRecoveryInFlight(
    phase: ConnectionPhase,
    foregroundCheckPending: Boolean,
    recoveryOverlayVisible: Boolean,
): Boolean = phase == ConnectionPhase.CONNECTING ||
    phase == ConnectionPhase.RECONNECTING ||
    foregroundCheckPending ||
    recoveryOverlayVisible
