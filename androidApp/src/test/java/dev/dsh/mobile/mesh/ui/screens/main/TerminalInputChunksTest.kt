package dev.dsh.mobile.mesh.ui.screens.main

import java.nio.charset.StandardCharsets.UTF_8
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalInputChunksTest {
    @Test fun splitsPasteWithinUtf8LimitWithoutBreakingCodePoints() {
        val paste = "a🙂b猫c🙂d"
        val chunks = terminalInputChunks(paste, 5)
        assertEquals(paste, chunks.joinToString(""))
        assertTrue(chunks.all { it.toByteArray(UTF_8).size <= 5 })
        assertTrue(chunks.none { it.contains('\uFFFD') })
    }

    @Test fun splitsPasteIntoHostBoundedWritesInOriginalOrder() = runBlocking {
        val paste = "a🙂b猫c🙂d"
        val sent = mutableListOf<String>()
        val queue = TerminalInputQueue { sent += it }
        val worker = launch { queue.drain() }
        assertTrue(queue.offer(paste, 5))
        queue.close()
        worker.join()
        assertEquals(paste, sent.joinToString(""))
        assertTrue(sent.all { it.toByteArray(UTF_8).size <= 5 })
    }

    @Test fun doesNotSendQueuedEnterAfterWriteFailure() = runBlocking {
        val sent = mutableListOf<String>()
        val queue = TerminalInputQueue { data ->
            if (data == "bad") error("rejected")
            sent += data
        }
        val worker = launch { queue.drain() }
        assertTrue(queue.offer("bad"))
        assertTrue(queue.offer("\r"))
        queue.close()
        worker.join()
        assertEquals(emptyList<String>(), sent)
    }
}
