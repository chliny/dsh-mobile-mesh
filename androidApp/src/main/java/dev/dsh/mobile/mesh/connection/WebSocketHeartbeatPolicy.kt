package dev.dsh.mobile.mesh.connection

/**
 * WebSocket liveness budgets by carrier. ZeroTier can briefly delay a pong while its userspace
 * relay is moving through the mobile network; Tailscale keeps the shorter failure detection budget.
 */
internal fun webSocketPingIntervalMs(transport: MeshTransport?): Long = when (transport) {
    MeshTransport.ZERO_TIER -> ZERO_TIER_PING_INTERVAL_MS
    else -> DEFAULT_PING_INTERVAL_MS
}

internal const val DEFAULT_PING_INTERVAL_MS = 10_000L
internal const val ZERO_TIER_PING_INTERVAL_MS = 30_000L
