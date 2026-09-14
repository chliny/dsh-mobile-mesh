package dev.dsh.mobile.mesh.connection

import dev.dsh.mobile.mesh.core.wire.RpcResult
import dev.dsh.mobile.mesh.core.wire.TransportFailure
import dev.dsh.mobile.mesh.core.wire.TransportFailures

/**
 * Decides whether the foreground probe proved the current carrier is still usable.
 *
 * Any HTTP/protocol answer proves bytes crossed the relay. Only failures that mean no usable
 * exchange happened should rebuild mesh/SSH; auth, trust-fence, capability and business errors
 * belong to the application layer and must not tear down a healthy carrier.
 */
internal fun foregroundProbeReachedHost(carrierOpen: Boolean, result: RpcResult<*>?): Boolean {
    if (!carrierOpen || result == null) return false
    if (result is RpcResult.Ok) return true
    val failure = TransportFailures.of((result as RpcResult.Err).error) ?: return true
    return failure !in setOf(
        TransportFailure.REFUSED,
        TransportFailure.TIMEOUT,
        TransportFailure.DNS,
        TransportFailure.UNREACHABLE,
        TransportFailure.TLS,
        TransportFailure.OTHER,
    )
}
