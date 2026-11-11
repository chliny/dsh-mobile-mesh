package dev.dsh.mobile.mesh.ui

internal enum class ConnectionListOrigin {
    SESSION,
    SETTINGS,
    CONNECT_FORM,
    STARTUP,
}

internal fun connectionListBackTarget(origin: ConnectionListOrigin): ConnectionListOrigin = origin

/**
 * Whether Back may leave the connection list while a connect/reconnect attempt is still owned by
 * it. The settings page is the one safe destination: the attempt keeps running in the background,
 * and the global recovery scrim — not this list — is what stops the user from walking on into the
 * session pages before the connection exists. Every other origin lands on a page that either is
 * the list itself or requires a live connection, so Back stays swallowed until the attempt ends.
 */
internal fun shouldAllowConnectionListBack(connecting: Boolean, origin: ConnectionListOrigin): Boolean =
    !connecting || origin == ConnectionListOrigin.SETTINGS
