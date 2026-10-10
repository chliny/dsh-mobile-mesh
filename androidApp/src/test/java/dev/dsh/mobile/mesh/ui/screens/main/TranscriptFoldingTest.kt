package dev.dsh.mobile.mesh.ui.screens.main

import dev.dsh.mobile.mesh.core.session.*
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class TranscriptFoldingTest {
    private fun answer(seq: Long, turn: Int) = AssistantMessageNode(seq, null, turn, 1, listOf(ChatBlock("text", "Answer")))
    private fun thought(seq: Long, turn: Int) = AssistantMessageNode(seq, null, turn, 0, listOf(ChatBlock("reasoning", "thinking")))
    private fun prompt(seq: Long) = UserMessageNode(seq, null, listOf(ChatBlock("text", "Question")), null)
    private fun nodes(parts: List<TranscriptPart>) = parts.filterIsInstance<TranscriptPart.Node>().map { it.node.seq }

    @Test fun `complete old and new turns keep input final answer and fold tool process`() {
        val events = listOf(prompt(1), TurnStartNode(2, 1), thought(3, 1), ToolCallNode(4, "a", "bash", "{}", 1, 0),
            answer(5, 1), TurnEndNode(6, 1, "completed"), prompt(7), TurnStartNode(8, 2),
            ToolCallNode(9, "b", "bash", "{}", 2, 0), answer(10, 2), TurnEndNode(11, 2, "completed"))
        val parts = partitionTranscript(events)
        assertEquals(listOf(1L, 5L, 7L, 10L), nodes(parts))
        assertEquals(listOf(listOf(3L, 4L), listOf(9L)), parts.filterIsInstance<TranscriptPart.Process>().map { it.nodes.map(ChatNode::seq) })
        assertEquals(listOf(2L, 8L), parts.filterIsInstance<TranscriptPart.Process>().map { it.startSeq })
    }

    @Test fun `pagination fragments and unfinished turns never fold`() {
        val events = listOf(ToolCallNode(1, "a", "bash", "{}", 1, 0), answer(2, 1), TurnEndNode(3, 1, "completed"),
            TurnStartNode(4, 2), thought(5, 2), answer(6, 2))
        assertTrue(partitionTranscript(events).none { it is TranscriptPart.Process })
        assertEquals(listOf(1L, 2L, 5L, 6L), nodes(partitionTranscript(events)))
    }

    @Test fun `failed turn and unknown types remain visible`() {
        val failed = listOf(TurnStartNode(1, 1), ToolCallNode(2, "a", "bash", "{}", 1, 0),
            TurnErrorNode(3, "failed", null), answer(4, 1), TurnEndNode(5, 1, "error"))
        assertTrue(partitionTranscript(failed).none { it is TranscriptPart.Process })
        val unknown = listOf(TurnStartNode(6, 2), OtherNode(7, "future/event", JsonObject(emptyMap())),
            ToolCallNode(8, "b", "bash", "{}", 2, 0), answer(9, 2), TurnEndNode(10, 2, "completed"))
        assertTrue(partitionTranscript(unknown).none { it is TranscriptPart.Process })
        assertTrue(7L in nodes(partitionTranscript(unknown)))
    }

    @Test fun `interleaved input prevents reordering of process and user content`() {
        val events = listOf(TurnStartNode(1, 1), thought(2, 1), prompt(3),
            ToolCallNode(4, "a", "bash", "{}", 1, 0), answer(5, 1), TurnEndNode(6, 1, "completed"))
        val parts = partitionTranscript(events)
        assertTrue(parts.none { it is TranscriptPart.Process })
        assertEquals(listOf(2L, 3L, 4L, 5L), nodes(parts))
    }

    @Test
    fun `quick transcript tail finds latest answer beyond long structural suffix`() {
        val latestAnswer = answer(10, 1)
        val nodes = buildList {
            add(prompt(1))
            add(latestAnswer)
            repeat(2_048) { index -> add(TurnEndNode(index + 11L, 1, "completed")) }
        }

        assertSame(latestAnswer, quickTailContentNode(nodes))
    }

    @Test
    fun `pending tail only appends a newer row and avoids duplicate LazyColumn keys`() {
        val previous = answer(10, 1)
        val current = answer(11, 1)

        assertSame(current, newQuickTailNode(listOf(previous), current))
        val updated = previous.copy(blocks = listOf(ChatBlock("text", "Updated answer")))
        assertSame(updated, newQuickTailNode(listOf(previous), updated))
        assertNull(newQuickTailNode(listOf(previous), previous.copy()))
        assertNull(newQuickTailNode(listOf(previous), answer(9, 1)))
    }

    @Test
    fun `streaming assistant row key survives changing provisional seq and settlement`() {
        val firstSnapshot = answer(10, 1).copy(streaming = true)
        val nextSnapshot = answer(18, 1).copy(streaming = true, blocks = listOf(ChatBlock("text", "Growing answer")))
        val settled = answer(22, 1)

        assertEquals(transcriptNodeRowKey(firstSnapshot), transcriptNodeRowKey(nextSnapshot))
        assertEquals(transcriptNodeRowKey(nextSnapshot), transcriptNodeRowKey(settled))
        assertEquals(TranscriptRow.Node(firstSnapshot).anchorSeq, TranscriptRow.Node(nextSnapshot).anchorSeq)
        assertSame(nextSnapshot, newQuickTailNode(listOf(firstSnapshot), nextSnapshot))
        assertSame(settled, newQuickTailNode(listOf(nextSnapshot), settled))
    }

    @Test(timeout = 5_000)
    fun `large folded process is partitioned without quadratic membership scans`() {
        val processSize = 20_000
        val events = buildList {
            add(TurnStartNode(1, 1))
            repeat(processSize) { index ->
                val seq = index + 2L
                add(ToolCallNode(seq, "call-$seq", "bash", "{}", 1, index))
            }
            add(answer(processSize + 2L, 1))
            add(TurnEndNode(processSize + 3L, 1, "completed"))
        }

        val parts = partitionTranscript(events)
        assertEquals(2, parts.size)
        assertEquals(processSize, (parts.first() as TranscriptPart.Process).nodes.size)
        val foldedRows = buildTranscriptRows(parts, emptyMap())
        assertEquals(2, foldedRows.size)
        assertEquals(processSize + 2L, (foldedRows.last() as TranscriptRow.Node).node.seq)
    }

    @Test fun `empty and bare answer have no disclosure`() {
        assertTrue(partitionTranscript(emptyList()).isEmpty())
        assertEquals(listOf(2L), nodes(partitionTranscript(listOf(TurnStartNode(1, 1), answer(2, 1), TurnEndNode(3, 1, "completed")))))
    }
}
