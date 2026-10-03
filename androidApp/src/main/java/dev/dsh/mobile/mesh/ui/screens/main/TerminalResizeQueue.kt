package dev.dsh.mobile.mesh.ui.screens.main

import kotlinx.coroutines.channels.Channel

/** One attachment's ordered resize sender; stale pending sizes are superseded by the newest. */
internal class TerminalResizeQueue(private val send: suspend (Pair<Int, Int>) -> Unit) {
    private val pending = Channel<Pair<Int, Int>>(Channel.CONFLATED)
    private val lock = Any()
    private var accepting = true

    fun offer(size: Pair<Int, Int>): Boolean = synchronized(lock) {
        accepting && pending.trySend(size).isSuccess
    }

    suspend fun drain() {
        for (size in pending) send(size)
    }

    fun close() {
        synchronized(lock) {
            accepting = false
            pending.close()
        }
    }
}
