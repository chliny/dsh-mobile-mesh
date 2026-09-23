package dev.dsh.mobile.mesh.ui.screens.connect

import dev.dsh.mobile.mesh.connection.ConnectionPhase

internal fun isEditingConnectedHost(editingHostId: String?, connectedHostId: String?): Boolean =
    editingHostId != null && editingHostId == connectedHostId

internal fun shouldLockConnectionEditor(
    isEditingCurrentConnection: Boolean,
    isEditingAttemptedConnection: Boolean,
    phase: ConnectionPhase,
): Boolean = isEditingAttemptedConnection ||
    (isEditingCurrentConnection && phase != ConnectionPhase.DISCONNECTED)

internal fun shouldShowConnectAction(editingHostId: String?, connectedHostId: String?): Boolean =
    editingHostId == null

internal fun shouldEnableConnectAction(fieldsEnabled: Boolean, formValid: Boolean): Boolean =
    fieldsEnabled && formValid

internal fun shouldAllowConnectionMutation(
    editingHostId: String?,
    connectedHostId: String?,
    connecting: Boolean,
    phase: ConnectionPhase = if (connecting) ConnectionPhase.CONNECTING else ConnectionPhase.DISCONNECTED,
): Boolean = editingHostId != null && !connecting && phase != ConnectionPhase.RECONNECTING &&
    (editingHostId != connectedHostId || phase != ConnectionPhase.CONNECTED)

internal fun shouldEnableConnectionSave(
    editingHostId: String?,
    connectedHostId: String?,
    connecting: Boolean,
    phase: ConnectionPhase = if (connecting) ConnectionPhase.CONNECTING else ConnectionPhase.DISCONNECTED,
): Boolean = if (editingHostId == null) {
    !connecting && phase != ConnectionPhase.RECONNECTING
} else {
    shouldAllowConnectionMutation(editingHostId, connectedHostId, connecting, phase)
}
