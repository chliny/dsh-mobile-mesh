package dev.dsh.mobile.mesh.ui.screens.connect

internal fun shouldReturnToConnectionsAfterSave(isNewConnection: Boolean, hasConnectionsDestination: Boolean): Boolean =
    isNewConnection && hasConnectionsDestination
