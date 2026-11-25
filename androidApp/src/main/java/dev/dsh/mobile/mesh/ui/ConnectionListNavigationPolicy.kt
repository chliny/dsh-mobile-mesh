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
 *
 * [recoveryInFlight] covers the attempt that has no explicit selection behind it: recovering an
 * established connection. Back during that attempt's scrim opens this list, so until the recovery
 * settles every page behind it is one the scrim would cover again — settings included. Leaving
 * would only bounce the user between the list and the same scrim. Callers pass it only for an
 * established connection, so the first-connect escape hatch to settings above is untouched.
 */
internal fun shouldAllowConnectionListBack(
    connecting: Boolean,
    origin: ConnectionListOrigin,
    recoveryInFlight: Boolean = false,
): Boolean = !recoveryInFlight && (!connecting || origin == ConnectionListOrigin.SETTINGS)
