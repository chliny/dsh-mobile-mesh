package dev.dsh.mobile.mesh.connection

/** Serializes callback mutation with owner replacement, rejecting every stale owner. */
internal class RecoveryCallbackFence {
    internal class Token internal constructor()

    private val lock = Any()
    private var current: Token? = null

    fun next(): Token = synchronized(lock) {
        Token().also { current = it }
    }

    fun invalidate() = synchronized(lock) {
        current = null
    }

    fun accepts(token: Token): Boolean = synchronized(lock) { current === token }

    /** The callback body is atomic with respect to [next] and [invalidate]. */
    fun runIfCurrent(token: Token, block: () -> Unit): Boolean = synchronized(lock) {
        if (current !== token) return@synchronized false
        block()
        true
    }
}
