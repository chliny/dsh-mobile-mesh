package dev.dsh.mobile.mesh.ui.screens.connect

import dev.dsh.mobile.mesh.connection.ConnectionPhase

internal enum class ConnectionRowStatus {
    CONNECTING,
    CONNECTED,
    SAME_DEVICE,
    NONE,
}

internal fun connectionRowStatus(
    hostId: String,
    connectedHostId: String?,
    connectingHostId: String?,
    phase: ConnectionPhase,
    isLoopback: Boolean,
): ConnectionRowStatus = when {
    hostId == connectingHostId && phase != ConnectionPhase.CONNECTED -> ConnectionRowStatus.CONNECTING
    hostId == connectedHostId && phase == ConnectionPhase.CONNECTED -> ConnectionRowStatus.CONNECTED
    isLoopback -> ConnectionRowStatus.SAME_DEVICE
    else -> ConnectionRowStatus.NONE
}
