package dev.dsh.mobile.mesh.ui.screens.main

import kotlinx.coroutines.channels.Channel

/** Bounded FIFO queue scoped to exactly one writable server attachment. */
internal class TerminalInputQueue(
    private val send: suspend (String) -> Unit,
) {
    private val input = Channel<String>(256)
    private val lock = Any()
    private var accepting = true
    private var buffered = 0
    private val maxBufferedBytes = 1 shl 20
    private var bufferedBytes = 0

    fun offer(data: String): Boolean = offerAll(listOf(data))

    fun offer(data: String, maxBytes: Int): Boolean = offerAll(terminalInputChunks(data, maxBytes))

    private fun offerAll(chunks: List<String>): Boolean = synchronized(lock) {
        val bytes = chunks.sumOf { it.toByteArray(Charsets.UTF_8).size }
        if (!accepting || chunks.size > 256 - buffered || bytes > maxBufferedBytes - bufferedBytes) return@synchronized false
        chunks.forEach { check(input.trySend(it).isSuccess) }
        buffered += chunks.size
        bufferedBytes += bytes
        true
    }

    suspend fun drain(onFailure: (Throwable) -> Unit = {}) {
        for (data in input) {
            synchronized(lock) { buffered--; bufferedBytes -= data.toByteArray(Charsets.UTF_8).size }
            try {
                send(data)
            } catch (failure: Exception) {
                // The attachment is no longer trustworthy. Stop before a queued Enter can execute
                // after rejected paste/input.
                synchronized(lock) {
                    accepting = false
                    buffered = 0
                    bufferedBytes = 0
                    input.cancel()
                }
                onFailure(failure)
                return
            }
        }
    }

    fun close() {
        synchronized(lock) {
            accepting = false
            input.close()
        }
    }
}
