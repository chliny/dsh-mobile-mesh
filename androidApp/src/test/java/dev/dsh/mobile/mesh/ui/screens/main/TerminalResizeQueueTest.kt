package dev.dsh.mobile.mesh.ui.screens.main

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalResizeQueueTest {
    @Test fun serializesConcurrentResizesSoTheLatestSizeWins() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val sent = mutableListOf<Pair<Int, Int>>()
        val queue = TerminalResizeQueue { size ->
            if (size == (80 to 24)) {
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
            sent += size
        }
        val worker = launch { queue.drain() }
        assertTrue(queue.offer(80 to 24))
        firstStarted.await()
        assertTrue(queue.offer(100 to 30))
        assertTrue(queue.offer(120 to 40))
        releaseFirst.complete(Unit)
        queue.close()
        worker.join()
        assertEquals(listOf(80 to 24, 120 to 40), sent)
        assertFalse(queue.offer(150 to 50))
    }
}
