package dev.dsh.mobile.mesh.connection

/**
 * A mux generation failure is not by itself proof that a ZeroTier-only relay is dead: the remote
 * harness or WebSocket may have failed while the local relay is still healthy. Rebuilding that
 * relay on every generation retry causes avoidable foreground flapping. SSH has an authoritative
 * relay-terminal callback, while a ZeroTier-only carrier is renewed on an explicit network event
 * or foreground liveness failure.
 */
internal fun shouldRenewCarrierAfterLoopFailure(
    sshEnabled: Boolean,
    networkRecoveryPending: Boolean,
    recoveryInFlight: Boolean,
    failedAttempt: Int,
): Boolean = !recoveryInFlight && (
    sshEnabled ||
        networkRecoveryPending ||
        failedAttempt >= ZERO_TIER_RENEW_AFTER_FAILURES
)

internal const val ZERO_TIER_RENEW_AFTER_FAILURES = 3

/** Authentication/protocol failures are host responses, not evidence that the mesh relay is stale. */
internal fun loopFailureCanRenewCarrier(failure: dev.dsh.mobile.mesh.core.wire.GenerationFailure): Boolean = when (failure) {
    is dev.dsh.mobile.mesh.core.wire.GenerationFailure.MuxFailed -> when (failure.kind) {
        dev.dsh.mobile.mesh.core.wire.TransportFailure.UNAUTHENTICATED,
        dev.dsh.mobile.mesh.core.wire.TransportFailure.TRUST_FENCE,
        dev.dsh.mobile.mesh.core.wire.TransportFailure.NOT_FOUND,
        dev.dsh.mobile.mesh.core.wire.TransportFailure.NOT_A_HARNESS,
        -> false
        else -> true
    }
    is dev.dsh.mobile.mesh.core.wire.GenerationFailure.ReadyFailed -> false
    // A first socket-open deadline is frequently a transient mobile wake-up race. The caller pairs
    // this signal with the retry threshold above, so it renews the carrier only after persistence.
    is dev.dsh.mobile.mesh.core.wire.GenerationFailure.MuxTimedOut -> true
}
