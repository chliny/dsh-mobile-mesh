package dev.dsh.mobile.mesh.ui.screens.main

import dev.dsh.mobile.mesh.core.session.*
import dev.dsh.mobile.mesh.data.TranscriptPresentation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class TranscriptFoldingTest {
    private fun answer(seq: Long, turn: Int) = AssistantMessageNode(seq, null, turn, 1, listOf(ChatBlock("text", "Answer")))
    private fun thought(seq: Long, turn: Int) = AssistantMessageNode(seq, null, turn, 0, listOf(ChatBlock("reasoning", "thinking")))
    private fun prompt(seq: Long) = UserMessageNode(seq, null, listOf(ChatBlock("text", "Question")), null)
    private fun todo(seq: Long) = TodoNode(seq, Json.parseToJsonElement(
        """[{"content":"Inspect source","status":"pending"}]""",
    ))
    private fun presented(seq: Long) = PresentedFilesNode(seq, 1, listOf(PresentedFile("notes.md")))
    private fun nodes(parts: List<TranscriptPart>) = parts.filterIsInstance<TranscriptPart.Node>().map { it.node.seq }

    @Test fun `complete old and new turns keep input final answer and fold tool process`() {
        val events = listOf(prompt(1), TurnStartNode(2, 1), thought(3, 1), ToolCallNode(4, "a", "bash", "{}", 1, 0),
            answer(5, 1), TurnEndNode(6, 1, "completed"), prompt(7), TurnStartNode(8, 2),
            ToolCallNode(9, "b", "bash", "{}", 2, 0), answer(10, 2), TurnEndNode(11, 2, "completed"))
        val parts = partitionTranscript(events)
        assertEquals(listOf(1L, 5L, 7L, 10L), nodes(parts))
        val processes = parts.filterIsInstance<TranscriptPart.Process>()
        assertEquals(listOf(listOf(3L, 4L), listOf(9L)), processes.map { it.materializeNodes().map(ChatNode::seq) })
        assertSame(processes.first().source, processes.last().source)
        assertEquals(listOf(2L, 8L), processes.map { it.startSeq })
    }

    @Test fun `Host folded reply choice changes what a reader sees after work collapses`() {
        val events = listOf(TurnStartNode(1, 1), thought(2, 1), answer(3, 1).copy(step = 1),
            ToolCallNode(4, "call", "bash", "{}", 1, 2), answer(5, 1).copy(step = 3),
            TurnEndNode(6, 1, "completed"))
        val all = partitionTranscript(events, TranscriptPresentation(foldedReplies = TranscriptPresentation.FoldedReplies.All))
        val final = partitionTranscript(events, TranscriptPresentation(foldedReplies = TranscriptPresentation.FoldedReplies.Final))

        assertEquals(listOf(3L, 5L), nodes(all))
        assertEquals(listOf(listOf(2L), listOf(4L)), all.filterIsInstance<TranscriptPart.Process>()
            .map { part -> part.materializeNodes().map(ChatNode::seq) })
        assertEquals(listOf(5L), nodes(final))
        assertEquals(listOf(2L, 3L, 4L), final.filterIsInstance<TranscriptPart.Process>().single()
            .materializeNodes().map(ChatNode::seq))
    }

    @Test fun `pagination fragments and unfinished turns never fold`() {
        val events = listOf(ToolCallNode(1, "a", "bash", "{}", 1, 0), answer(2, 1), TurnEndNode(3, 1, "completed"),
            TurnStartNode(4, 2), thought(5, 2), answer(6, 2))
        assertTrue(partitionTranscript(events).none { it is TranscriptPart.Process })
        assertEquals(listOf(1L, 2L, 5L, 6L), nodes(partitionTranscript(events)))
    }

    @Test fun `failed turn keeps its error row in the open transcript`() {
        val failed = listOf(TurnStartNode(1, 1), ToolCallNode(2, "a", "bash", "{}", 1, 0),
            TurnErrorNode(3, "failed", null), answer(4, 1), TurnEndNode(5, 1, "error"))
        assertTrue(partitionTranscript(failed).none { it is TranscriptPart.Process })
    }

    @Test fun `an unrecognised event folds with the work and stays reachable by expanding`() {
        // Folding does not conceal a row: one tap materializes it. Refusing to fold a whole Turn
        // because the Host sent one event this build has not learned yet is the worse outcome.
        val unknown = listOf(TurnStartNode(6, 2), OtherNode(7, "future/event", JsonObject(emptyMap())),
            ToolCallNode(8, "b", "bash", "{}", 2, 0), answer(9, 2), TurnEndNode(10, 2, "completed"))
        val parts = partitionTranscript(unknown)
        val folded = parts.filterIsInstance<TranscriptPart.Process>().single()
            .materializeNodes().map(ChatNode::seq)
        assertEquals(listOf(7L, 8L), folded)
        assertEquals(listOf(9L), nodes(parts))
    }

    @Test fun `interleaved input prevents reordering of process and user content`() {
        val events = listOf(TurnStartNode(1, 1), thought(2, 1), prompt(3),
            ToolCallNode(4, "a", "bash", "{}", 1, 0), answer(5, 1), TurnEndNode(6, 1, "completed"))
        val parts = partitionTranscript(events)
        assertTrue(parts.none { it is TranscriptPart.Process })
        assertEquals(listOf(2L, 3L, 4L, 5L), nodes(parts))
    }

    @Test fun `a to-do list folds with the work it plans instead of keeping the turn flat`() {
        // todo_write runs in almost every agent turn; treating its node as an "interruption" made
        // the whole turn refuse to collapse, so work steps never auto-hid.
        val events = listOf(TurnStartNode(1, 1), thought(2, 1), todo(3),
            ToolCallNode(4, "a", "bash", "{}", 1, 0), answer(5, 1), TurnEndNode(6, 1, "completed"))
        val parts = partitionTranscript(events)
        val folded = parts.flatMap { part ->
            if (part is TranscriptPart.Process) part.materializeNodes() else emptyList()
        }
        assertEquals(listOf(2L, 3L, 4L), folded.map(ChatNode::seq))
        assertEquals(listOf(5L), nodes(parts))
    }

    @Test fun `delivered files stay visible and do not stop the work from folding`() {
        val events = listOf(TurnStartNode(1, 1), thought(2, 1), ToolCallNode(3, "a", "bash", "{}", 1, 0),
            presented(4), answer(5, 1), TurnEndNode(6, 1, "completed"))
        val parts = partitionTranscript(events)
        // The delivered file is a result the reader came for, so it keeps its own row.
        assertEquals(listOf(4L, 5L), nodes(parts))
        assertEquals(listOf(listOf(2L, 3L)), parts.filterIsInstance<TranscriptPart.Process>()
            .map { part -> part.materializeNodes().map(ChatNode::seq) })
    }

    @Test
    fun `collapsed process stores a source range and materializes only its visible nodes`() {
        val events = listOf(
            prompt(1), TurnStartNode(2, 1), thought(3, 1),
            ToolCallNode(4, "tool", "bash", "{}", 1, 0),
            answer(5, 1), TurnEndNode(6, 1, "completed"),
        )
        val process = partitionTranscript(events).filterIsInstance<TranscriptPart.Process>().single()

        assertEquals(2, process.startIndex)
        assertEquals(4, process.endExclusive)
        assertEquals(2, process.nodeCount)
        assertEquals(listOf(3L, 4L), process.materializeNodes().map(ChatNode::seq))
    }

    @Test
    fun `streaming assistant row key survives changing provisional seq and settlement`() {
        val firstSnapshot = answer(10, 1).copy(streaming = true)
        val nextSnapshot = answer(18, 1).copy(streaming = true, blocks = listOf(ChatBlock("text", "Growing answer")))
        val settled = answer(22, 1)

        assertEquals(transcriptNodeRowKey(firstSnapshot), transcriptNodeRowKey(nextSnapshot))
        assertEquals(transcriptNodeRowKey(nextSnapshot), transcriptNodeRowKey(settled))
        assertEquals(
            DisclosureKeys.reasoning(transcriptNodeRowKey(firstSnapshot), 0),
            DisclosureKeys.reasoning(transcriptNodeRowKey(nextSnapshot), 0),
        )
        assertEquals(TranscriptRow.Node(firstSnapshot).anchorSeq, TranscriptRow.Node(nextSnapshot).anchorSeq)
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
        assertEquals(processSize, (parts.first() as TranscriptPart.Process).nodeCount)
        val foldedRows = buildTranscriptRows(parts, emptyMap())
        assertEquals(2, foldedRows.size)
        assertEquals(processSize + 2L, (foldedRows.last() as TranscriptRow.Node).node.seq)
    }

    @Test fun `verbose exposes completed process while other modes retain an outer fold`() {
        val events = listOf(TurnStartNode(1, 1), thought(2, 1),
            ToolCallNode(3, "a", "bash", "{}", 1, 0), answer(4, 1), TurnEndNode(5, 1, "completed"))
        TranscriptPresentation.Mode.entries.forEach { mode ->
            val parts = partitionTranscript(events, TranscriptPresentation(mode))
            assertEquals(mode != TranscriptPresentation.Mode.Verbose,
                parts.any { it is TranscriptPart.Process })
            assertTrue(4L in nodes(parts))
        }
    }

    @Test fun `compact and standard group live work but detailed and verbose show it flat`() {
        val events = listOf(TurnStartNode(1, 1), thought(2, 1), ToolCallNode(3, "a", "bash", "{}", 1, 0))
        TranscriptPresentation.Mode.entries.forEach { mode ->
            val parts = partitionTranscript(events, TranscriptPresentation(mode))
            assertEquals(mode == TranscriptPresentation.Mode.Compact || mode == TranscriptPresentation.Mode.Standard,
                parts.any { it is TranscriptPart.Process })
            assertEquals(setOf(2L, 3L), (nodes(parts) + parts.filterIsInstance<TranscriptPart.Process>()
                .flatMap { it.materializeNodes().map(ChatNode::seq) }).toSet())
        }
    }

    @Test fun `standard groups interrupted work while keeping the error visible`() {
        val events = listOf(TurnStartNode(1, 1), thought(2, 1),
            ToolCallNode(3, "a", "bash", "{}", 1, 0), TurnErrorNode(4, "failed", null),
            TurnEndNode(5, 1, "error"))
        val parts = partitionTranscript(events, TranscriptPresentation(TranscriptPresentation.Mode.Standard))
        assertEquals(listOf(2L, 3L), parts.filterIsInstance<TranscriptPart.Process>().single()
            .materializeNodes().map(ChatNode::seq))
        assertEquals(listOf(4L, 5L), nodes(parts))
    }

    @Test fun `verbose rows do not group adjacent command activity`() {
        val events = listOf(TurnStartNode(1, 1), ToolCallNode(2, "a", "bash", "{}", 1, 0),
            answer(3, 1), TurnEndNode(4, 1, "completed"))
        val parts = partitionTranscript(events, TranscriptPresentation(TranscriptPresentation.Mode.Verbose))
        assertTrue(buildTranscriptRows(parts, groupActivities = false) { false }.all { it is TranscriptRow.Node })
    }

    @Test fun `next input delays newest completed fold until another input arrives`() {
        val events = listOf(TurnStartNode(1, 1), thought(2, 1), answer(3, 1), TurnEndNode(4, 1, "completed"))
        val policy = TranscriptPresentation(collapseTiming = TranscriptPresentation.CollapseTiming.NextInput)
        assertTrue(partitionTranscript(events, policy).filterIsInstance<TranscriptPart.Process>().single().defaultExpanded)
        assertFalse(partitionTranscript(events + prompt(5), policy)
            .filterIsInstance<TranscriptPart.Process>().single().defaultExpanded)
    }

    @Test fun `older page keeps previously visible turn fragment open when its start arrives`() {
        val opening = listOf(thought(3, 1), ToolCallNode(4, "call", "bash", "{}", 1, 0),
            answer(5, 1), TurnEndNode(6, 1, "completed"), prompt(7))
        val complete = listOf(prompt(1), TurnStartNode(2, 1)) + opening
        val before = partitionTranscript(opening)
        val after = partitionTranscript(complete)
        val newlyFolded = newlyFoldedPrependProcesses(opening, before, complete, after)
        val scope = DisclosureScope(TranscriptDisclosures(), "session")

        assertEquals(setOf(2L), newlyFolded)
        newlyFolded.forEach { scope.setOpen(DisclosureKeys.process(it), true) }
        val rows = buildTranscriptRows(after) { seq ->
            (after.filterIsInstance<TranscriptPart.Process>().firstOrNull { it.startSeq == seq })
                ?.let { processExpanded(it, scope) } == true
        }
        assertTrue(rows.any { it is TranscriptRow.Node && it.node.seq == 3L })
        assertEquals(TranscriptRow.Node(opening[0]).key, rows.first { it.anchorSeq == 3L }.key)
        assertTrue(newlyFoldedPrependProcesses(complete, after, complete, after).isEmpty())

        // Existing folded rows and explicit reader choices must not be opened by a later prepend.
        scope.setOpen(DisclosureKeys.process(2), false)
        assertFalse(processExpanded(after.filterIsInstance<TranscriptPart.Process>().single(), scope))
        val evenOlder = listOf(prompt(0)) + complete
        assertTrue(newlyFoldedPrependProcesses(complete, after, evenOlder,
            partitionTranscript(evenOlder)).isEmpty())
    }

    @Test fun `deferred fold can be closed manually before next input`() {
        val part = partitionTranscript(listOf(TurnStartNode(1, 1), thought(2, 1), answer(3, 1),
            TurnEndNode(4, 1, "completed")), TranscriptPresentation(
                collapseTiming = TranscriptPresentation.CollapseTiming.NextInput,
            )).filterIsInstance<TranscriptPart.Process>().single()
        val scope = DisclosureScope(TranscriptDisclosures(), "session")
        assertTrue(processExpanded(part, scope))
        scope.setOpen(DisclosureKeys.process(part.startSeq), false)
        assertFalse(processExpanded(part, scope))
        scope.setOpen(DisclosureKeys.process(part.startSeq), true)
        assertTrue(processExpanded(part, scope))
    }

    @Test fun `standard live group previews its current command while compact hides it`() {
        val events = listOf(TurnStartNode(1, 1),
            ToolCallNode(2, "a", "bash", """{"description":"Inspect source"}""", 1, 0))
        val standard = TranscriptPresentation(TranscriptPresentation.Mode.Standard)
        val group = partitionTranscript(events, standard).filterIsInstance<TranscriptPart.Process>().single()
        assertEquals("Inspect source", liveProcessDetail(group, standard, null))
        assertNull(liveProcessDetail(group, TranscriptPresentation(TranscriptPresentation.Mode.Compact), null))
    }

    @Test fun `empty and bare answer have no disclosure`() {
        assertTrue(partitionTranscript(emptyList()).isEmpty())
        assertEquals(listOf(2L), nodes(partitionTranscript(listOf(TurnStartNode(1, 1), answer(2, 1), TurnEndNode(3, 1, "completed")))))
    }
}
