package dev.dsh.mobile.mesh.ui.screens.connect

internal fun isEditingConnectedHost(editingHostId: String?, connectedHostId: String?): Boolean =
    editingHostId != null && editingHostId == connectedHostId
