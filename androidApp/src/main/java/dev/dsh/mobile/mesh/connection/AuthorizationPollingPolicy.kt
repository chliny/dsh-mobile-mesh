package dev.dsh.mobile.mesh.connection

/** Stop automatic polling after a bounded window; the dialog remains retryable manually. */
internal fun shouldContinueAuthorizationPolling(
    elapsedMs: Long,
    authorizationPending: Boolean,
    connected: Boolean,
    failed: Boolean,
    maxDurationMs: Long,
): Boolean = authorizationPending && !connected && !failed && elapsedMs < maxDurationMs
