package dev.dsh.mobile.mesh.connection

import java.io.Closeable

/**
 * Tracks sockets while a blocking native connect is in flight.
 *
 * Closing the registry fences late completions but does not call close concurrently with native
 * connect. Callers must use a finite native connect timeout, then call [finish]; timed-out or
 * cancelled sockets are closed when that bounded call returns. A successful finish transfers
 * ownership to the caller.
 */
internal class BoundedConnectRegistry<T>(private val closeResource: (T) -> Unit) : Closeable {
    private val lock = Any()
    private val connecting = linkedSetOf<T>()
    private var closed = false

    /** Register before native connect. Late registrations are rejected and closed immediately. */
    fun register(resource: T): Boolean {
        val accepted = synchronized(lock) {
            if (closed) false else connecting.add(resource)
        }
        if (!accepted) closeQuietly(resource)
        return accepted
    }

    /** Transfer a connected resource only while the registry is live; otherwise close it. */
    fun finish(resource: T, connected: Boolean, onClosed: ((Long) -> Unit)? = null): Boolean {
        val transferred = synchronized(lock) {
            connecting.remove(resource) && connected && !closed
        }
        if (!transferred) {
            val closeStartedAt = System.nanoTime()
            closeQuietly(resource)
            onClosed?.invoke(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closeStartedAt))
        }
        return transferred
    }

    /** In-flight resources close themselves when their bounded native connect returns. */
    override fun close() {
        synchronized(lock) { closed = true }
    }

    private fun closeQuietly(resource: T) {
        runCatching { closeResource(resource) }
    }
}

internal const val ZERO_TIER_INITIAL_TARGET_CONNECT_TIMEOUT_MS = 30_000
internal const val ZERO_TIER_RECOVERY_TARGET_CONNECT_TIMEOUT_MS = 5_000

internal fun zeroTierTargetConnectTimeoutMs(recovery: Boolean): Int =
    if (recovery) ZERO_TIER_RECOVERY_TARGET_CONNECT_TIMEOUT_MS else ZERO_TIER_INITIAL_TARGET_CONNECT_TIMEOUT_MS

internal fun remainingConnectTimeoutMs(budgetMs: Int, startedAtNanos: Long, nowNanos: Long): Int =
    (budgetMs - java.util.concurrent.TimeUnit.NANOSECONDS.toMillis((nowNanos - startedAtNanos).coerceAtLeast(0L)))
        .coerceAtLeast(0L)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
