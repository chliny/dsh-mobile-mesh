package dev.dsh.mobile.mesh.ui

import dev.dsh.mobile.mesh.connection.ConnectionPhase

internal fun shouldShowConnectionRecoveryOverlay(
    hasConnected: Boolean,
    phase: ConnectionPhase,
    foregroundCheckPending: Boolean,
    recoveryOverlayVisible: Boolean = false,
): Boolean = hasConnected && (
    phase != ConnectionPhase.CONNECTED || recoveryOverlayVisible || foregroundCheckPending
)

internal fun shouldRearmConnectionRecoveryOverlay(
    hasConnected: Boolean,
    recoveryInFlight: Boolean,
): Boolean = hasConnected && recoveryInFlight
