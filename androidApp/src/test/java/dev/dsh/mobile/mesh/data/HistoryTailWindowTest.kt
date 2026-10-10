package dev.dsh.mobile.mesh.data

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryTailWindowTest {
    @Test
    fun `tail window keeps requested visible messages with intervening events`() {
        val records = listOf("m1", "tool1", "m2", "tool2", "m3", "tool3", "m4")

        val page = historyTailWindow(
            entries = records,
            maxMessages = 2,
            maxEvents = 20,
            isMessage = { it.startsWith("m") },
        )

        assertEquals(listOf("m3", "tool3", "m4"), page)
    }

    @Test
    fun `tool results do not consume the server message budget`() {
        val records = listOf("user/message", "tool/result", "assistant/message", "tool/result", "assistant/message")

        val page = historyTailWindow(
            entries = records,
            maxMessages = 2,
            maxEvents = 20,
            isMessage = { it == "user/message" || it == "assistant/message" },
        )

        assertEquals(listOf("assistant/message", "tool/result", "assistant/message"), page)
    }

    @Test
    fun `event ceiling wins when tail has too few visible messages`() {
        val records = listOf("m1", "old", "old2", "tool1", "trace", "m2", "tool2", "trace2")

        val page = historyTailWindow(
            entries = records,
            maxMessages = 10,
            maxEvents = 4,
            isMessage = { it.startsWith("m") },
        )

        assertEquals(listOf("trace", "m2", "tool2", "trace2"), page)
    }

    @Test
    fun `short history returns all records`() {
        val records = listOf("m1", "tool1", "m2")
        assertEquals(
            records,
            historyTailWindow(records, maxMessages = 20, maxEvents = 4_000) { it.startsWith("m") },
        )
    }
}
