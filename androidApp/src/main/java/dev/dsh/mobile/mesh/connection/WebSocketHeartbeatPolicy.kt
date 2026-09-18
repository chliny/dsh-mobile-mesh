package dev.dsh.mobile.mesh.connection

import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

/**
 * Heartbeat policy for a carrier whose RTT can change while a mobile userspace relay is active.
 * OkHttp uses the ping interval as its pong deadline, so this is intentionally conservative.
 */
internal fun webSocketPingIntervalMs(
    transport: MeshTransport?,
    initialLatencyMs: Long? = null,
): Long = adaptivePingIntervalMs(initialLatencyMs)

internal const val DEFAULT_ADAPTIVE_PING_INTERVAL_MS = 60_000L
internal const val ADAPTIVE_PING_MIN_INTERVAL_MS = 30_000L
internal const val ADAPTIVE_PING_MAX_INTERVAL_MS = 180_000L
internal const val ADAPTIVE_PING_SAFETY_MARGIN_MS = 30_000L

internal fun adaptivePingIntervalMs(initialLatencyMs: Long?): Long {
    val observed = initialLatencyMs?.coerceAtLeast(1L) ?: DEFAULT_ADAPTIVE_PING_INTERVAL_MS
    return (observed * 8L + ADAPTIVE_PING_SAFETY_MARGIN_MS)
        .coerceIn(ADAPTIVE_PING_MIN_INTERVAL_MS, ADAPTIVE_PING_MAX_INTERVAL_MS)
}

internal const val DEFAULT_PING_INTERVAL_MS = 10_000L

internal class AdaptiveWebSocketHeartbeat(
    initialIntervalMs: Long = DEFAULT_ADAPTIVE_PING_INTERVAL_MS,
) {
    private var intervalMs = initialIntervalMs.coerceIn(
        ADAPTIVE_PING_MIN_INTERVAL_MS,
        ADAPTIVE_PING_MAX_INTERVAL_MS,
    )
    private var lastPongAtNanos: Long? = null
    private var lastIntervalMs = intervalMs

    @Synchronized
    fun intervalMs(): Long = intervalMs

    /**
     * OkHttp calls this after a control pong. Consecutive pong spacing is interval + RTT, so the
     * first useful sample can be estimated without adding an application-level heartbeat frame.
     */
    @Synchronized
    fun recordPong(nowNanos: Long = System.nanoTime()) {
        val previous = lastPongAtNanos
        if (previous != null) {
            val spacingMs = TimeUnit.NANOSECONDS.toMillis((nowNanos - previous).coerceAtLeast(0L))
            val rttMs = (spacingMs - lastIntervalMs).coerceAtLeast(1L)
            val target = (rttMs * 8L).coerceIn(
                ADAPTIVE_PING_MIN_INTERVAL_MS,
                ADAPTIVE_PING_MAX_INTERVAL_MS,
            )
            intervalMs = ((intervalMs * 3L + target).toDouble() / 4.0).roundToLong()
                .coerceIn(ADAPTIVE_PING_MIN_INTERVAL_MS, ADAPTIVE_PING_MAX_INTERVAL_MS)
        }
        lastPongAtNanos = nowNanos
        lastIntervalMs = intervalMs
    }
}
