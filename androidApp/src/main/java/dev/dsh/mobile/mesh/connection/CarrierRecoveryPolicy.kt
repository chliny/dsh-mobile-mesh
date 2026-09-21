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
): Boolean = !recoveryInFlight && (sshEnabled || networkRecoveryPending)
