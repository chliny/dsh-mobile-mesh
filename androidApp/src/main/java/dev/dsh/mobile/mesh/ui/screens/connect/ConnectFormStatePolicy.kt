package dev.dsh.mobile.mesh.ui.screens.connect

internal fun isEditingConnectedHost(editingHostId: String?, connectedHostId: String?): Boolean =
    editingHostId != null && editingHostId == connectedHostId

internal fun shouldShowConnectAction(editingHostId: String?, connectedHostId: String?): Boolean =
    editingHostId == null

internal fun shouldEnableConnectAction(fieldsEnabled: Boolean, formValid: Boolean): Boolean =
    fieldsEnabled && formValid

internal fun shouldAllowConnectionMutation(
    editingHostId: String?,
    connectedHostId: String?,
    connecting: Boolean,
): Boolean = editingHostId != null && !connecting && editingHostId != connectedHostId

internal fun shouldEnableConnectionSave(
    editingHostId: String?,
    connectedHostId: String?,
    connecting: Boolean,
): Boolean = editingHostId == null || shouldAllowConnectionMutation(editingHostId, connectedHostId, connecting)
