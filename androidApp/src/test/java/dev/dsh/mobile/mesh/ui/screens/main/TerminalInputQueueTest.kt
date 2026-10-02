package dev.dsh.mobile.mesh.ui.screens.main

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalInputQueueTest {
    @Test fun boundsInputWhenWriterCannotKeepUp() = runBlocking {
        val queue = TerminalInputQueue { }
        repeat(256) { assertTrue(queue.offer("x")) }
        assertEquals(false, queue.offer("overflow"))
        queue.close()
    }

    @Test fun preservesInputOrderAcrossSlowWrites() = runBlocking {
        val first = CompletableDeferred<Unit>()
        val accepted = mutableListOf<String>()
        val queue = TerminalInputQueue { data ->
            if (data == "l") first.await()
            accepted += data
        }
        val worker = launch { queue.drain() }
        assertTrue(queue.offer("l"))
        assertTrue(queue.offer("s"))
        assertTrue(queue.offer("\r"))
        first.complete(Unit)
        queue.close()
        worker.join()
        assertEquals(listOf("l", "s", "\r"), accepted)
    }
}
