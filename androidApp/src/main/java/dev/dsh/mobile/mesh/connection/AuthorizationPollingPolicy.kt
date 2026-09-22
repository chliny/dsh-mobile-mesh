package dev.dsh.mobile.mesh.connection

/** Authorization completion is delivered by the native Tailscale IPN event, not UI polling. */
internal fun shouldContinueAuthorizationPolling(
    elapsedMs: Long,
    authorizationPending: Boolean,
    connected: Boolean,
    failed: Boolean,
    maxDurationMs: Long,
): Boolean = false
