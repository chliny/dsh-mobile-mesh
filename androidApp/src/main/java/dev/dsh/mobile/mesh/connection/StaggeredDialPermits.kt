package dev.dsh.mobile.mesh.connection

/**
 * Allows one foreground dial immediately and one staggered backup only when the first has stalled.
 * The second socket has its own TCP retransmission phase; no background probe or node restart occurs.
 */
internal class StaggeredDialPermits(
    private val secondAfterMillis: Long,
    private val maxConcurrentDials: Int = 2,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    init { require(maxConcurrentDials in 1..2) }

    private var firstStartedAt: Long? = null
    private var count = 0

    @Synchronized fun tryAcquire(): Int? {
        val now = nowNanos()
        if (count == 0) {
            firstStartedAt = now
            count = 1
            return 1
        }
        val started = firstStartedAt ?: return null
        if (count >= maxConcurrentDials || (now - started) < secondAfterMillis * 1_000_000L) return null
        count = 2
        return 2
    }

    @Synchronized fun release() {
        check(count > 0) { "release without a dial" }
        count--
        if (count == 0) firstStartedAt = null
    }

    @get:Synchronized val inFlight: Int get() = count
}

/** Counts native connects across recovery generations, including timed-out waits' late dials. */
internal class NativeDialPermits(private val maxConcurrentDials: Int) {
    init { require(maxConcurrentDials in 1..2) }

    private var count = 0

    @Synchronized fun tryAcquire(): Boolean {
        if (count >= maxConcurrentDials) return false
        count++
        return true
    }

    @Synchronized fun release() {
        check(count > 0) { "release without a native dial" }
        count--
    }

    @get:Synchronized val inFlight: Int get() = count
}

/** Preserve per-wait stagger while respecting the connector-wide native socket budget. */
internal class RecoveryDialPermits(
    secondAfterMillis: Long,
    maxConcurrentDials: Int,
    private val native: NativeDialPermits,
    nowNanos: () -> Long = System::nanoTime,
) {
    private val staggered = StaggeredDialPermits(secondAfterMillis, maxConcurrentDials, nowNanos)

    fun tryAcquire(): Int? {
        val slot = staggered.tryAcquire() ?: return null
        if (native.tryAcquire()) return slot
        staggered.release()
        return null
    }

    fun release() {
        staggered.release()
        native.release()
    }

    val inFlight: Int get() = staggered.inFlight
    val nativeInFlight: Int get() = native.inFlight
}
