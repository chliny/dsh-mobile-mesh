package dev.dsh.mobile.mesh.ui.screens.connect

internal fun shouldResetBeforeSelectingHost(connecting: Boolean): Boolean = connecting

internal fun shouldOpenSessionsForCurrentHost(
    hostId: String,
    connectedHostId: String?,
    connectionPhase: dev.dsh.mobile.mesh.connection.ConnectionPhase,
): Boolean = connectionPhase == dev.dsh.mobile.mesh.connection.ConnectionPhase.CONNECTED &&
    hostId == connectedHostId
