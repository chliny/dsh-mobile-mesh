package dev.dsh.mobile.mesh.connection

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Distinguishes an expected relay shutdown during replacement from an unexpected listener exit.
 *
 * A local forwarder's `listen()` loop exits for both cases. Only the latter is a transport event;
 * reporting the former would recursively schedule another recovery while ConnectionManager is
 * intentionally rebuilding the relay.
 */
internal class RelayTerminationGate {
    val token: Long = nextToken.incrementAndGet()
    private val closing = AtomicBoolean(false)
    private val reported = AtomicBoolean(false)

    fun markClosing() {
        closing.set(true)
    }

    /** Returns true exactly once for an unexpected relay termination. */
    fun reportUnexpectedTermination(): Boolean =
        !closing.get() && reported.compareAndSet(false, true)

    private companion object {
        val nextToken = AtomicLong(0)
    }
}
