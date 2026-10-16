package dev.dsh.mobile.mesh.ui.screens.main

import dev.dsh.mobile.mesh.core.wire.RemoteStream
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalAttachmentInputTest {
    private class Stream : RemoteStream {
        val sent = mutableListOf<JsonElement>()
        var cancelled = false
        override suspend fun receive(): JsonElement? = null
        override fun send(value: JsonElement?) { sent += requireNotNull(value) }
        override fun endUplink() = Unit
        override fun cancel() { cancelled = true }
    }

    @Test fun `command waits for matching ack before sending next`() = runTest {
        val stream = Stream()
        val input = TerminalAttachmentInput(stream)
        input.snapshot(true, null)
        val first = async { input.write("hello") }
        testScheduler.runCurrent()
        val second = async { input.resize(80, 24) }
        testScheduler.runCurrent()
        assertEquals(1, stream.sent.size)
        assertFalse(first.isCompleted)
        input.acknowledge(7, null)
        testScheduler.runCurrent()
        assertFalse(first.isCompleted)
        input.acknowledge(1, null)
        testScheduler.runCurrent()
        assertEquals(2, stream.sent.size)
        assertEquals("2", stream.sent.last().jsonObject["sequence"].toString())
        input.acknowledge(2, null)
        first.await()
        second.await()
        input.close()
        assertTrue(stream.cancelled)
    }

    @Test fun `refusal and lost writability stop queued commands`() = runTest {
        val stream = Stream()
        val input = TerminalAttachmentInput(stream)
        input.snapshot(true, null)
        val first = async { runCatching { input.write("never replay") }.exceptionOrNull() }
        testScheduler.runCurrent()
        input.acknowledge(1, "not-running")
        assertTrue(first.await() is IllegalStateException)
        input.state(false)
        assertTrue(runCatching { input.resize(80, 24) }.exceptionOrNull() is IllegalStateException)
        assertEquals(1, stream.sent.size)
    }

    @Test fun `writability revocation fails an in flight command instead of hanging`() = runTest {
        val stream = Stream()
        val input = TerminalAttachmentInput(stream)
        input.snapshot(true, null)
        val command = async { runCatching { input.write("pending") }.exceptionOrNull() }
        testScheduler.runCurrent()
        input.state(false)
        assertTrue(command.await() is IllegalStateException)
        assertEquals(1, stream.sent.size)
    }

    @Test fun `encoded command larger than carrier window is not sent`() = runTest {
        val stream = Stream()
        val input = TerminalAttachmentInput(stream)
        input.snapshot(true, JsonObject(mapOf("maxBytes" to JsonPrimitive(12), "frameOverheadBytes" to JsonPrimitive(4))))
        assertTrue(runCatching { input.write("too large") }.exceptionOrNull() is IllegalStateException)
        assertTrue(stream.sent.isEmpty())
    }
}
