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
internal const val FOREGROUND_PROBE_TIMEOUT_MS = 5_000L
internal const val ZERO_TIER_FOREGROUND_PROBE_TIMEOUT_MS = 12_000L

internal fun foregroundProbeTimeoutMs(meshTransport: MeshTransport?): Long =
    if (meshTransport == MeshTransport.ZERO_TIER) ZERO_TIER_FOREGROUND_PROBE_TIMEOUT_MS
    else FOREGROUND_PROBE_TIMEOUT_MS

internal fun shouldPublishConnectedAfterForegroundProbe(
    needsProbe: Boolean,
    appInForeground: Boolean,
    carrierOpen: Boolean,
    result: RpcResult<*>?,
): Boolean = !needsProbe || (appInForeground && foregroundProbeReachedHost(carrierOpen, result))

internal fun isConnectionStateAuthoritative(
    phase: ConnectionPhase,
    generationPublished: Boolean,
    probePending: Boolean,
): Boolean = phase == ConnectionPhase.CONNECTED && generationPublished && !probePending

internal fun mayPublishAfterForegroundProbe(
    appInForeground: Boolean,
    lifecycleEpochMatches: Boolean,
    generationMatches: Boolean,
): Boolean = appInForeground && lifecycleEpochMatches && generationMatches

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
