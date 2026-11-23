package dev.dsh.mobile.mesh.connection

import java.io.Closeable

/**
 * Tracks sockets while a blocking native connect is in flight.
 *
 * Closing the registry fences late completions and asks in-flight connects to return early via
 * [cancelResource]; it never closes a socket concurrently with its own native connect, because
 * closing one mid-handshake races the native TCP stack. Timed-out or cancelled sockets are closed
 * when that bounded call returns. A successful finish transfers ownership to the caller.
 */
internal class BoundedConnectRegistry<T>(
    private val cancelResource: (T) -> Unit = {},
    private val closeResource: (T) -> Unit,
) : Closeable {
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

    /**
     * Retire this generation. In-flight resources stay open and are only asked to stop waiting;
     * each one closes itself when its bounded native call returns.
     */
    override fun close() {
        val inFlight = synchronized(lock) {
            closed = true
            connecting.toList()
        }
        inFlight.forEach { resource -> runCatching { cancelResource(resource) } }
    }

    private fun closeQuietly(resource: T) {
        runCatching { closeResource(resource) }
    }
}

// Cold bootstrap gets time for node startup and one staggered second dial, but should not
// pin the UI to a black-holed destination for the old 30-second native connect budget.
internal const val ZERO_TIER_INITIAL_TARGET_CONNECT_TIMEOUT_MS = 15_000
internal const val ZERO_TIER_RECOVERY_TARGET_CONNECT_TIMEOUT_MS = 5_000
internal const val ZERO_TIER_INITIAL_READINESS_TIMEOUT_MS = 20_000L
internal const val ZERO_TIER_REUSED_READINESS_TIMEOUT_MS = 8_000L

/** A retained native node is a recovery even if the suspended manager has no active relay. */
internal fun zeroTierReadinessTimeoutMs(reusedNode: Boolean): Long =
    if (reusedNode) ZERO_TIER_REUSED_READINESS_TIMEOUT_MS else ZERO_TIER_INITIAL_READINESS_TIMEOUT_MS

internal fun zeroTierTargetConnectTimeoutMs(recovery: Boolean): Int =
    if (recovery) ZERO_TIER_RECOVERY_TARGET_CONNECT_TIMEOUT_MS else ZERO_TIER_INITIAL_TARGET_CONNECT_TIMEOUT_MS

// Fast recovery windows assume the retained node still owns a working path: a healthy handover
// dials in milliseconds. A Wi-Fi/cellular switch or a long background instead expires the node's
// NAT bindings and peer paths, and rebuilding them needs far longer than one 5s dial — a device
// trace showed a retained node whose every 5s dial timed out for minutes, while a fresh node
// needed one 14.4s dial. Consecutive not-ready results are that evidence, so the following attempt
// gets the whole cold-bootstrap budget and can span the rejoin instead of failing on the same
// short deadline forever.
internal const val ZERO_TIER_PATIENT_AFTER_FAILURES = 2

internal fun zeroTierPatientRecovery(consecutiveNotReady: Int): Boolean =
    consecutiveNotReady >= ZERO_TIER_PATIENT_AFTER_FAILURES

/** Readiness window for one recovery attempt against a retained node. */
internal fun zeroTierRecoveryReadinessTimeoutMs(consecutiveNotReady: Int): Long =
    if (zeroTierPatientRecovery(consecutiveNotReady)) ZERO_TIER_INITIAL_READINESS_TIMEOUT_MS
    else ZERO_TIER_REUSED_READINESS_TIMEOUT_MS

/** Per-dial connect budget for one recovery attempt against a retained node. */
internal fun zeroTierRecoveryTargetConnectTimeoutMs(consecutiveNotReady: Int): Int =
    if (zeroTierPatientRecovery(consecutiveNotReady)) ZERO_TIER_INITIAL_TARGET_CONNECT_TIMEOUT_MS
    else ZERO_TIER_RECOVERY_TARGET_CONNECT_TIMEOUT_MS

internal fun remainingConnectTimeoutMs(budgetMs: Int, startedAtNanos: Long, nowNanos: Long): Int =
    (budgetMs - java.util.concurrent.TimeUnit.NANOSECONDS.toMillis((nowNanos - startedAtNanos).coerceAtLeast(0L)))
        .coerceAtLeast(0L)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
