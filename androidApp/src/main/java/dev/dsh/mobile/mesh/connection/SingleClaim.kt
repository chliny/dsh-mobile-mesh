package dev.dsh.mobile.mesh.connection

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Hands a resource to exactly one owner.
 *
 * A readiness dial can finish after the wait that asked for it already returned, so publishing and
 * claiming race. A value that arrives after the claim, or after a newer value replaced it, goes to
 * [onSuperseded] so the owner releases it instead of leaking a socket to the host.
 */
internal class SingleClaim<T>(
    private val onSuperseded: (T) -> Unit = {},
) {
    private val value = AtomicReference<T?>(null)
    private val claimed = AtomicBoolean(false)

    fun publish(candidate: T) {
        value.getAndSet(candidate)?.let(onSuperseded)
        if (claimed.get()) release()
    }

    /** Returns the published value to the first caller, or null when none is available. */
    fun claim(): T? {
        claimed.set(true)
        return value.getAndSet(null)
    }

    private fun release() {
        value.getAndSet(null)?.let(onSuperseded)
    }
}