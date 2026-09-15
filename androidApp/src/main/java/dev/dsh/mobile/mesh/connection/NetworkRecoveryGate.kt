package dev.dsh.mobile.mesh.connection

/**
 * Keeps a network-handover recovery request armed until a transport operation can actually start.
 * Android may announce the replacement network while an older recovery is still unwinding; dropping
 * that event is what leaves the app retrying against the retired path indefinitely.
 */
internal class NetworkRecoveryGate {
    private var pending = false

    @Synchronized fun markPending() {
        pending = true
    }

    @Synchronized fun isPending(): Boolean = pending

    /** Consume only after the caller has won the operation slot. */
    @Synchronized fun consumeIfCanStart(canStart: Boolean): Boolean {
        if (!pending || !canStart) return false
        pending = false
        return true
    }

    @Synchronized fun clear() {
        pending = false
    }
}
