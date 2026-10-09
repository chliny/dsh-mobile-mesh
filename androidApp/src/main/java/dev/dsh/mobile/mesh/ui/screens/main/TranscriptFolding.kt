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

/** Presentation only: retain the original node (and seq) for every row. */
internal sealed interface TranscriptPart {
    data class Node(val node: ChatNode) : TranscriptPart
    data class Process(val startSeq: Long, val nodes: List<ChatNode>) : TranscriptPart
}

/**
 * Only a fully observed, successfully completed turn can hide its process. A history page may
 * start in the middle of a turn, and a live page may end in the middle of one: both stay unfolded.
 * Unexpected events and error/unfinished outcomes remain in the ordinary transcript.
 */
internal fun partitionTranscript(nodes: List<ChatNode>): List<TranscriptPart> {
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
        val turnNodes = nodes.subList(index + 1, endIndex).filter { it.rendersContent() }
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
        val process = turnNodes.filterIndexed { turnIndex, _ -> processFlags[turnIndex] }
        val firstProcess = processFlags.indexOfFirst { it }
        val lastProcess = processFlags.indexOfLast { it }
        val interleaved = firstProcess >= 0 && (firstProcess..lastProcess).any { !processFlags[it] }
        if (process.isEmpty() || interleaved) {
            turnNodes.forEach { result.add(TranscriptPart.Node(it)) }
        } else {
            // Keep all user input and the final answer in their original relative positions.
            // The disclosure occupies the first process node's position; expansion puts every
            // process row back in its original order, with its original seq key.
            var inserted = false
            turnNodes.forEachIndexed { turnIndex, node ->
                if (processFlags[turnIndex]) {
                    if (!inserted) {
                        result.add(TranscriptPart.Process(start.seq, process))
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
