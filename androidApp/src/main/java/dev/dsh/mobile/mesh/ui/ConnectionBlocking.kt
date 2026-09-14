package dev.dsh.mobile.mesh.ui

import dev.dsh.mobile.mesh.connection.ConnectionPhase

/** Only an established connection entering a non-connected phase should block the main UI. */
internal fun shouldBlockForConnectionRecovery(
    hasConnected: Boolean,
    phase: ConnectionPhase,
    foregroundCheckPending: Boolean = false,
): Boolean = hasConnected && (phase != ConnectionPhase.CONNECTED || foregroundCheckPending)
