package dev.dsh.mobile.mesh.connection

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

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
    // A `$events` readiness failure has no usable published generation to probe. After the caller's
    // bounded retry threshold it is evidence of a black-holed carrier, unless it is an explicit
    // host-side semantic response such as authentication or trust rejection.
    is dev.dsh.mobile.mesh.core.wire.GenerationFailure.ReadyFailed -> when ((failure.error.details as? JsonObject)?.get("transport")?.jsonPrimitive?.content) {
        dev.dsh.mobile.mesh.core.wire.TransportFailure.UNAUTHENTICATED.name,
        dev.dsh.mobile.mesh.core.wire.TransportFailure.TRUST_FENCE.name,
        dev.dsh.mobile.mesh.core.wire.TransportFailure.NOT_FOUND.name,
        dev.dsh.mobile.mesh.core.wire.TransportFailure.NOT_A_HARNESS.name,
        -> false
        else -> true
    }
    // A first socket-open deadline is frequently a transient mobile wake-up race. The caller pairs
    // this signal with the retry threshold above, so it renews the carrier only after persistence.
    is dev.dsh.mobile.mesh.core.wire.GenerationFailure.MuxTimedOut -> true
}
