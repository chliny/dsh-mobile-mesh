package dev.dsh.mobile.mesh.ui.screens.main

import dev.dsh.mobile.mesh.core.session.AssistantMessageNode
import dev.dsh.mobile.mesh.core.session.ChatNode
import dev.dsh.mobile.mesh.core.session.OtherNode
import dev.dsh.mobile.mesh.core.session.PresentedFilesNode
import dev.dsh.mobile.mesh.core.session.TurnEndNode
import dev.dsh.mobile.mesh.core.session.TurnErrorNode
import dev.dsh.mobile.mesh.core.session.TurnStartNode
import dev.dsh.mobile.mesh.core.session.TodoNode
import dev.dsh.mobile.mesh.core.session.UserMessageNode

/** One immutable conversation list shared by range-based folded-process references. */
internal class TranscriptNodeSnapshot(val nodes: List<ChatNode>)

/** Presentation only: retain the original node (and seq) for every row. */
internal sealed interface TranscriptPart {
    data class Node(val node: ChatNode) : TranscriptPart
    data class Process(
        val startSeq: Long,
        val source: TranscriptNodeSnapshot,
        val startIndex: Int,
        val endExclusive: Int,
        val nodeCount: Int,
    ) : TranscriptPart {
        constructor(startSeq: Long, nodes: List<ChatNode>) : this(
            startSeq,
            TranscriptNodeSnapshot(nodes),
            0,
            nodes.size,
            nodes.size,
        )

        /** Materialized only when the disclosure opens; collapsed rows retain only this range. */
        fun materializeNodes(): List<ChatNode> =
            source.nodes.subList(startIndex, endExclusive).filter { it.rendersContent() }

        val firstNodeSeq: Long get() = source.nodes[startIndex].seq

        fun containsSeq(seq: Long): Boolean =
            (startIndex until endExclusive).any { source.nodes[it].seq == seq }
    }
}

/**
 * Only a fully observed, successfully completed turn can hide its process. A history page may
 * start in the middle of a turn, and a live page may end in the middle of one: both stay unfolded.
 * Unexpected events and error/unfinished outcomes remain in the ordinary transcript.
 */
internal fun partitionTranscript(nodes: List<ChatNode>): List<TranscriptPart> {
    val source = TranscriptNodeSnapshot(nodes)
    val result = mutableListOf<TranscriptPart>()
    var index = 0
    while (index < nodes.size) {
        val start = nodes[index] as? TurnStartNode
        if (start == null) {
            if (nodes[index].rendersContent()) result.add(TranscriptPart.Node(nodes[index]))
            index++
            continue
        }
        val endIndex = (index + 1 until nodes.size).firstOrNull { i ->
            nodes[i] is TurnStartNode || nodes[i] is TurnEndNode
        }
        val end = endIndex?.let { nodes[it] as? TurnEndNode }
        if (end == null || end.turn != start.turn || end.reasonKind != "completed") {
            val stop = endIndex ?: nodes.size
            for (i in index until stop) if (nodes[i].rendersContent()) result.add(TranscriptPart.Node(nodes[i]))
            index = stop
            continue
        }
        val turnNodes = mutableListOf<ChatNode>()
        val turnSourceIndexes = mutableListOf<Int>()
        for (sourceIndex in index + 1 until endIndex) {
            val node = nodes[sourceIndex]
            if (node.rendersContent()) {
                turnNodes.add(node)
                turnSourceIndexes.add(sourceIndex)
            }
        }
        // Without a delivered final answer there is no sensible summary to leave visible. Unknown
        // node/block types and errors also remain exposed rather than being silently concealed.
        val final = turnNodes.lastOrNull { it is AssistantMessageNode } as? AssistantMessageNode
        val safe = final != null && !final.streaming && !final.interrupted &&
            final.blocks.any { it.kind == "text" && !it.text.isNullOrBlank() || it.kind == "image" } &&
            turnNodes.none { node ->
                node is TurnErrorNode || node is OtherNode ||
                    (node is AssistantMessageNode && node.blocks.any { block ->
                        block.kind !in setOf("text", "reasoning", "image", "tool-call", "tool-result")
                    })
            }
        val processFlags = BooleanArray(turnNodes.size) { turnIndex ->
            val node = turnNodes[turnIndex]
            safe && node !== final && node !is UserMessageNode && node !is PresentedFilesNode &&
                node !is TodoNode && !(node is AssistantMessageNode &&
                (node.interrupted || node.blocks.any { it.kind == "text" && !it.text.isNullOrBlank() || it.kind == "image" }))
        }
        val processCount = processFlags.count { it }
        val firstProcess = processFlags.indexOfFirst { it }
        val lastProcess = processFlags.indexOfLast { it }
        val interleaved = firstProcess >= 0 && (firstProcess..lastProcess).any { !processFlags[it] }
        if (processCount == 0 || interleaved) {
            turnNodes.forEach { result.add(TranscriptPart.Node(it)) }
        } else {
            // Keep all user input and the final answer in their original relative positions.
            // The disclosure occupies the first process node's position; expansion puts every
            // process row back in its original order, with its original seq key.
            var inserted = false
            turnNodes.forEachIndexed { turnIndex, node ->
                if (processFlags[turnIndex]) {
                    if (!inserted) {
                        result.add(
                            TranscriptPart.Process(
                                startSeq = start.seq,
                                source = source,
                                startIndex = turnSourceIndexes[firstProcess],
                                endExclusive = turnSourceIndexes[lastProcess] + 1,
                                nodeCount = processCount,
                            ),
                        )
                        inserted = true
                    }
                } else {
                    result.add(TranscriptPart.Node(node))
                }
            }
        }
        index = endIndex + 1
    }
    return result
}
