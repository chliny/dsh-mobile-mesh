package dev.dsh.mobile.mesh.ui.screens.connect

import dev.dsh.mobile.mesh.connection.ConnectionPhase

internal enum class ConnectionRowAction {
    DISCONNECT,
    UPDATE_TOKEN,
    EDIT,
    DELETE,
}

internal fun connectionRowActionTitles(): List<ConnectionRowAction> =
    listOf(ConnectionRowAction.DISCONNECT, ConnectionRowAction.UPDATE_TOKEN, ConnectionRowAction.EDIT, ConnectionRowAction.DELETE)

/** A row can stop the active connection attempt, but an idle remembered host cannot. */
internal fun canDisconnectConnectionRow(
    hostId: String,
    connectedHostId: String?,
    connectingHostId: String?,
    phase: ConnectionPhase,
): Boolean = when {
    hostId == connectedHostId -> phase == ConnectionPhase.CONNECTED || phase == ConnectionPhase.RECONNECTING
    hostId == connectingHostId -> phase == ConnectionPhase.CONNECTING || phase == ConnectionPhase.RECONNECTING
    else -> false
}
