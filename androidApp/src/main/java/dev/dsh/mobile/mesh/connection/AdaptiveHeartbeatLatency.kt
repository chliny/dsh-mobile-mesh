package dev.dsh.mobile.mesh.connection

import kotlin.math.max

/** Conservative first-connection budget used to size ZeroTier's WebSocket heartbeat. */
internal fun heartbeatLatencySample(startedAtNanos: Long, finishedAtNanos: Long): Long =
    max(1L, (finishedAtNanos - startedAtNanos) / 1_000_000L)
