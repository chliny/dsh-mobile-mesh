package dev.dsh.mobile.mesh.ui.screens.main

import dev.dsh.mobile.mesh.core.session.AssistantMessageNode
import dev.dsh.mobile.mesh.core.session.ChatNode
import dev.dsh.mobile.mesh.core.session.OtherNode
import dev.dsh.mobile.mesh.core.session.PresentedFilesNode
import dev.dsh.mobile.mesh.core.session.TurnEndNode
import dev.dsh.mobile.mesh.core.session.TurnErrorNode
import dev.dsh.mobile.mesh.core.session.TurnStartNode
import dev.dsh.mobile.mesh.core.session.TodoNode
import dev.dsh.mobile.mesh.core.session.ToolCallNode
import dev.dsh.mobile.mesh.core.session.UserMessageNode
import dev.dsh.mobile.mesh.data.TranscriptPresentation

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
        val defaultExpanded: Boolean = false,
        val live: Boolean = false,
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

/** Explicit reader choice overrides the next-input default-open completed Turn. */
internal fun processExpanded(part: TranscriptPart.Process, scope: DisclosureScope): Boolean {
    val key = DisclosureKeys.process(part.startSeq)
    return if (scope.isSet(key)) scope.isOpen(key) else part.defaultExpanded
}

/** The current running call's command, path or query; compact omits this preview. */
internal fun liveProcessDetail(part: TranscriptPart.Process, presentation: TranscriptPresentation, cwd: String?): String? {
    if (!part.live || !presentation.liveProcessDetail) return null
    for (index in part.endExclusive - 1 downTo part.startIndex) {
        val call = part.source.nodes[index] as? ToolCallNode ?: continue
        return toolRowModel(call.name, call.arguments, cwd).summary
    }
    return null
}

/**
 * Only a fully observed, successfully completed turn can hide its outer process. A partial
 * historical turn stays flat; compact and standard may group the current live work separately.
 * Unexpected events, interleaved input and failed outcomes remain visible.
 */
internal fun partitionTranscript(
    nodes: List<ChatNode>,
    presentation: TranscriptPresentation = TranscriptPresentation(),
): List<TranscriptPart> {
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
        if (end == null || end.turn != start.turn || end.reasonKind != "completed" ||
            !presentation.foldCompletedTurns
        ) {
            val stop = endIndex ?: nodes.size
            val visible = (index + 1 until stop).filter { nodes[it].rendersContent() }
            val live = end == null && endIndex == null
            val groupable = visible.takeWhile { i ->
                val node = nodes[i]
                node !is UserMessageNode && node !is OtherNode && node !is TurnErrorNode &&
                    node !is PresentedFilesNode && node !is TodoNode &&
                    !(node is AssistantMessageNode && node.blocks.any { block ->
                        block.kind == "text" && !block.text.isNullOrBlank() || block.kind == "image"
                    })
            }
            val canGroup = presentation.groupLiveSteps && (live || end?.reasonKind in setOf("error", "aborted", "interrupted"))
            if (canGroup && groupable.isNotEmpty()) {
                result.add(TranscriptPart.Process(start.seq, source, groupable.first(), groupable.last() + 1,
                    groupable.size, live = live))
                visible.drop(groupable.size).forEach { result.add(TranscriptPart.Node(nodes[it])) }
            } else visible.forEach { result.add(TranscriptPart.Node(nodes[it])) }
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
                                defaultExpanded = presentation.collapseTiming == TranscriptPresentation.CollapseTiming.NextInput &&
                                    endIndex == nodes.lastIndex,
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
