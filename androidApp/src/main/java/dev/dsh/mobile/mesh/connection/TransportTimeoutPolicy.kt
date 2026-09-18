package dev.dsh.mobile.mesh.connection

/** Transport startup budgets must cover the connector's own bounded membership waits. */
internal fun transportOperationTimeoutMs(
    config: HostConfig,
    authorizationResume: Boolean,
): Long = when {
    config.meshTransport == MeshTransport.ZERO_TIER -> ZERO_TIER_TRANSPORT_TIMEOUT_MS
    authorizationResume -> ConnectionTimeoutPolicy.authorizationResumeMs
    else -> ZERO_TIER_TRANSPORT_TIMEOUT_MS
}

internal const val ZERO_TIER_TRANSPORT_TIMEOUT_MS = 45_000L
