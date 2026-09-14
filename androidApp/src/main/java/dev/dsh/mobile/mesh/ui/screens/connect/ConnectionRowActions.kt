package dev.dsh.mobile.mesh.ui.screens.connect

internal enum class ConnectionRowAction {
    UPDATE_TOKEN,
    EDIT,
    DELETE,
}

internal fun connectionRowActionTitles(): List<ConnectionRowAction> =
    listOf(ConnectionRowAction.UPDATE_TOKEN, ConnectionRowAction.EDIT, ConnectionRowAction.DELETE)
