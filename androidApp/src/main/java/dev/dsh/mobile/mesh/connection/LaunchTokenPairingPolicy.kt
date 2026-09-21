package dev.dsh.mobile.mesh.connection

/** A remembered launch token is only needed when this host has no persisted browser session. */
internal fun shouldPairLaunchToken(
    hasSession: Boolean,
    token: String?,
): Boolean = !hasSession && !token.isNullOrBlank()
