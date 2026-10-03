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

    @Test fun `empty and bare answer have no disclosure`() {
        assertTrue(partitionTranscript(emptyList()).isEmpty())
        assertEquals(listOf(2L), nodes(partitionTranscript(listOf(TurnStartNode(1, 1), answer(2, 1), TurnEndNode(3, 1, "completed")))))
    }
}
