package dev.dsh.mobile.mesh.connection

import dev.dsh.mobile.mesh.core.wire.RpcResult
import dev.dsh.mobile.mesh.core.wire.TransportFailure
import dev.dsh.mobile.mesh.core.wire.TransportFailures

/**
 * Decides whether the foreground probe proved the current carrier is still usable.
 *
 * Any HTTP/protocol answer proves bytes crossed the relay. The caller must avoid probing a newly
 * published generation unless it was explicitly re-armed by a lifecycle/network boundary.
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
