package dev.dsh.mobile.mesh.ui

import dev.dsh.mobile.mesh.connection.ConnectionPhase

internal fun shouldShowConnectionRecoveryOverlay(
    hasConnected: Boolean,
    phase: ConnectionPhase,
    foregroundCheckPending: Boolean,
): Boolean = hasConnected && foregroundCheckPending

internal fun shouldRearmConnectionRecoveryOverlay(
    hasConnected: Boolean,
    recoveryInFlight: Boolean,
): Boolean = hasConnected && recoveryInFlight
