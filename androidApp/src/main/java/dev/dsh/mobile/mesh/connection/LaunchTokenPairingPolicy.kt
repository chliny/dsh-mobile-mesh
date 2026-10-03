package dev.dsh.mobile.mesh.connection

/** Reuse a saved session for ordinary connects, but an explicitly submitted token replaces a stale session. */
internal fun shouldPairLaunchToken(
    hasSession: Boolean,
    token: String?,
    explicitlySubmitted: Boolean = false,
): Boolean = !token.isNullOrBlank() && (!hasSession || explicitlySubmitted)
