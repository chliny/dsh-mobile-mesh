package dev.dsh.mobile.mesh.connection

/** Order-independent default-network handover state for Android callback delivery. */
internal class DefaultNetworkTracker<T>(initial: T? = null) {
    private var current: T? = initial
    private var recoveryNeeded = false

    @Synchronized
    fun onAvailable(network: T, connected: Boolean) {
        val previous = current
        current = network
        if (connected && previous != null && previous != network) recoveryNeeded = true
    }

    @Synchronized
    fun onLost(network: T, connected: Boolean) {
        if (network != current) return
        current = null
        if (connected) recoveryNeeded = true
    }

    @Synchronized
    fun hasRecoveryNeeded(): Boolean = recoveryNeeded

    @Synchronized
    fun consumeRecoveryNeeded(): Boolean {
        val needed = recoveryNeeded
        recoveryNeeded = false
        return needed
    }

    @Synchronized
    fun current(): T? = current
}
