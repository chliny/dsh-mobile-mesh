package dev.dsh.mobile.mesh.ui.screens.connect

internal fun shouldSkipLegacySshRestore(meshTransportStoredValue: String?): Boolean =
    meshTransportStoredValue != null
