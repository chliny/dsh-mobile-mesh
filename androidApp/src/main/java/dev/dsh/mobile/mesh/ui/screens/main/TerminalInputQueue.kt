package dev.dsh.mobile.mesh.ui.screens.main

import kotlinx.coroutines.channels.Channel

/** Bounded FIFO queue scoped to exactly one writable server attachment. */
internal class TerminalInputQueue(
    private val send: suspend (String) -> Unit,
) {
    private val input = Channel<String>(256)

    fun offer(data: String): Boolean = input.trySend(data).isSuccess

    suspend fun drain() {
        for (data in input) send(data)
    }

    fun close() { input.close() }
}
