package dev.dsh.mobile.mesh.ui

import dev.dsh.mobile.mesh.connection.ConnectionPhase

internal fun shouldKeepSessionPageDuringRecovery(
    hasConnectedBefore: Boolean,
    activeHostId: String?,
    phase: ConnectionPhase,
): Boolean = hasConnectedBefore && activeHostId != null
