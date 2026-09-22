package dev.dsh.mobile.mesh.ui.screens.main

import dev.dsh.mobile.mesh.core.session.AssistantMessageNode
import dev.dsh.mobile.mesh.core.session.ChatBlock
import dev.dsh.mobile.mesh.core.session.ChatNode
import dev.dsh.mobile.mesh.core.session.CommandNode
import dev.dsh.mobile.mesh.core.session.CompactionNode
import dev.dsh.mobile.mesh.core.session.ContextMessageNode
import dev.dsh.mobile.mesh.core.session.ChangesNode
import dev.dsh.mobile.mesh.core.session.GoalNode
import dev.dsh.mobile.mesh.core.session.OtherNode
import dev.dsh.mobile.mesh.core.session.PlanModeNode
import dev.dsh.mobile.mesh.core.session.ProducedFilesNode
import dev.dsh.mobile.mesh.core.session.PresentedFilesNode
import dev.dsh.mobile.mesh.core.session.RetryNode
import dev.dsh.mobile.mesh.core.session.SubagentNode
import dev.dsh.mobile.mesh.core.session.TitleNode
import dev.dsh.mobile.mesh.core.session.TodoNode
import dev.dsh.mobile.mesh.core.session.ToolCallNode
import dev.dsh.mobile.mesh.core.session.ToolResultNode
import dev.dsh.mobile.mesh.core.session.TurnEndNode
import dev.dsh.mobile.mesh.core.session.TurnErrorNode
import dev.dsh.mobile.mesh.core.session.TurnStartNode
import dev.dsh.mobile.mesh.core.session.UserMessageNode
import dev.dsh.mobile.mesh.core.session.WorkflowNode
import kotlinx.serialization.json.JsonObject

/**
 * Whether [ChatNodeItem] draws anything for this node.
 *
 * The transcript spaces its rows with `Arrangement.spacedBy`, which does not care that an item is
 * zero-height: a node that renders nothing still costs a gap. A single turn folds into twenty-odd
 * of them — turn and step boundaries, request headers, tool results (drawn inside their call's
 * card), assistant chunks — and the gaps stack into a blank band at the top of the viewport. So
 * they are filtered out of the list rather than emitted and hidden.
 *
 * The `when` is exhaustive against [ChatNode] on purpose: a new node type is a compile error here,
 * which is the only thing keeping this in step with [ChatNodeItem]'s own dispatch.
 */
/**
 * The text a user turn renders in its bubble.
 *
 * Shared by [rendersContent] and [ChatNodeItem] so the two cannot disagree about whether a turn has
 * anything to show — a node judged renderable but drawing nothing costs a gap in the transcript, and
 * one judged empty but holding text loses the message.
 *
 * `"unknown"` blocks carrying text count: a harness build that labels a block something this client
 * has not seen should still put the user's words on screen rather than drop them.
 */
internal fun UserMessageNode.displayText(): String = blocks.displayText(previewText)

/** Text suitable for a user or injected-context disclosure without promoting unknown blocks to UI. */
internal fun List<ChatBlock>.displayText(previewText: String): String = this
    .filter { it.kind == "text" || (it.kind == "unknown" && it.text != null) }
    .joinToString("\n") { it.text.orEmpty() }
    .ifBlank { previewText }

internal fun ContextMessageNode.displayText(): String = blocks.displayText(previewText)

internal fun ChatNode.rendersContent(): Boolean = when (this) {
    // Structure, not content.
    is TurnStartNode -> false
    // Rendered inside the matching call's card.
    is ToolResultNode -> false
    // Only an unclean ending says anything; a completed turn is the frame.
    is TurnEndNode -> reasonKind != "completed"
    // Bookkeeping event types are not "unknown" — they are noise between the tool calls.
    is OtherNode -> type !in STRUCTURAL_EVENT_TYPES

    // Content that can still fold to nothing.
    is UserMessageNode -> blocks.any { it.kind == "image" } || displayText().isNotBlank()
    is ContextMessageNode -> displayText().isNotBlank()
    is AssistantMessageNode -> interrupted || blocks.any { block ->
        when (block.kind) {
            // Tool calls arrive as their own nodes; the inline block is a duplicate reference.
            "tool-call", "tool-result" -> false
            "text" -> !block.text.isNullOrBlank()
            "reasoning", "image" -> true
            else -> block.text != null
        }
    }
    is TodoNode -> parseTodos(todos) != null
    is GoalNode -> parseGoal(data) != null
    is ProducedFilesNode -> this.paths.isNotEmpty()
    is PresentedFilesNode -> this.files.isNotEmpty()
    is ChangesNode -> true
    is WorkflowNode -> data is JsonObject

    // Always draws.
    is ToolCallNode -> true
    is PlanModeNode -> true
    is CompactionNode -> true
    is RetryNode -> true
    is TurnErrorNode -> true
    is CommandNode -> true
    is TitleNode -> true
    is SubagentNode -> true
}
