package dev.dsh.mobile.mesh.ui.screens.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.core.session.AssistantMessageNode
import dev.dsh.mobile.mesh.core.session.ChatNode
import dev.dsh.mobile.mesh.core.session.CommandNode
import dev.dsh.mobile.mesh.core.session.CompactionNode
import dev.dsh.mobile.mesh.core.session.ContextMessageNode
import dev.dsh.mobile.mesh.core.session.GoalNode
import dev.dsh.mobile.mesh.core.session.OtherNode
import dev.dsh.mobile.mesh.core.session.PlanModeNode
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
import dev.dsh.mobile.mesh.ui.components.AttachmentImage
import dev.dsh.mobile.mesh.ui.components.DisclosureRow
import dev.dsh.mobile.mesh.ui.components.DisclosureState
import dev.dsh.mobile.mesh.ui.components.DsPill
import dev.dsh.mobile.mesh.ui.components.FeatherIcons
import dev.dsh.mobile.mesh.ui.components.MarkdownText
import dev.dsh.mobile.mesh.ui.components.StateDot
import dev.dsh.mobile.mesh.ui.components.StateDotState
import dev.dsh.mobile.mesh.ui.components.ThinkingRow
import dev.dsh.mobile.mesh.ui.components.ToolCard
import dev.dsh.mobile.mesh.ui.components.UserBubble
import dev.dsh.mobile.mesh.ui.theme.DsAnimations
import dev.dsh.mobile.mesh.ui.theme.DsShapes
import dev.dsh.mobile.mesh.ui.theme.DsTheme
import dev.dsh.mobile.mesh.ui.theme.DsType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Everything one transcript row needs that is not on the node itself. */
internal data class ChatNodeContext(
    val nodes: List<ChatNode>,
    val running: Boolean,
    val cwd: String?,
    /** Host account home, used only to abbreviate a leftover home-rooted path as `~`. */
    val home: String? = null,
    val onOpenFile: ((String, String) -> Unit)? = null,
    val onOpenSubagent: (String) -> Unit,
    val onBranchFrom: (Long) -> Unit,
    val onFeedback: (Long, Boolean) -> Unit,
)

/**
 * One node of the conversation. The `when` is exhaustive over [ChatNode] on purpose: a harness that
 * grows a new event type still renders, because the fold produces an `OtherNode` rather than
 * dropping it, and this shows it rather than a gap in the transcript.
 */
@Composable
internal fun ChatNodeItem(node: ChatNode, context: ChatNodeContext) {
    val colors = DsTheme.colors
    when (node) {
        // Turn boundaries are structure, not content — the transcript shows the work, not the frame.
        is TurnStartNode -> Unit

        is UserMessageNode -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            node.blocks.filter { it.kind == "image" }.forEach { block ->
                parseImageRef(block)?.let { ref ->
                    AttachmentImage(
                        attachmentId = ref.attachmentId,
                        intrinsicWidth = ref.width,
                        intrinsicHeight = ref.height,
                        contentDescription = ref.name,
                    )
                }
            }
            // A file is a chip, not a preview: the host stores it verbatim and the model reads it
            // through its file tools, so there is nothing to render but what it is called.
            node.blocks.filter { it.kind == "file" }.forEach { block ->
                parseFileRef(block)?.let { ref ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        FileChip(
                            name = ref.name,
                            bytes = ref.bytes,
                            modifier = Modifier.clickable(enabled = context.onOpenFile != null) {
                                context.onOpenFile?.invoke(parseWorkspaceFilePath(block) ?: ref.name, ref.name)
                            },
                        )
                    }
                }
            }
            val text = node.displayText()
            if (text.isNotBlank()) MarkdownUserBubble(text)
        }

        is ContextMessageNode -> ContextInjectionRow(node)

        is AssistantMessageNode -> AssistantMessage(node, context)

        is ToolCallNode -> ToolCallRow(node, context)

        // Rendered inside the matching ToolCallNode's card; not a standalone row.
        is ToolResultNode -> Unit

        is TurnEndNode -> when (node.reasonKind) {
            "completed" -> ProducedFilesRow(node, context)
            "aborted", "interrupted" -> DsPill(text = stringResource(R.string.chat_stopped), warn = true)
            "error" -> Row(verticalAlignment = Alignment.CenterVertically) {
                StateDot(StateDotState.Error, size = 8.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.chat_error_turn) + node.reasonDetail?.let { " · $it" }.orEmpty(),
                    style = DsType.small13,
                    color = colors.error,
                )
            }
            "max-tokens" -> DsPill(text = stringResource(R.string.chat_max_tokens), warn = true)
            else -> Unit
        }

        is TodoNode -> parseTodos(node.todos)?.let { TodoDock(it) }

        is GoalNode -> parseGoal(node.data)?.let { GoalSummary(it) }

        is PlanModeNode -> DsPill(
            text = stringResource(if (node.active) R.string.plan_mode_on else R.string.plan_mode_off),
            warn = true,
        )

        is CompactionNode -> CompactionRow(node)

        is RetryNode -> {
            val delayMs = (node.data as? JsonObject)?.let { obj ->
                obj["delayMs"].asLong() ?: obj["ms"].asLong() ?: obj["providerRetryAfterMs"].asLong()
            }
            val label = if (delayMs != null && delayMs > 0) {
                stringResource(R.string.chat_retry_scheduled, (delayMs / 1000).toInt().coerceAtLeast(1))
            } else {
                // A retry with no stated delay used to read "Loading…", which says nothing about
                // what happened; four of them in a row before a failure is a story worth telling.
                stringResource(R.string.chat_retrying)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                StateDot(StateDotState.Warning, size = 8.dp)
                Spacer(Modifier.width(6.dp))
                Text(label, style = DsType.caption11, color = colors.labelTertiary)
            }
        }

        is TurnErrorNode -> Row(verticalAlignment = Alignment.CenterVertically) {
            StateDot(StateDotState.Error, size = 8.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.chat_error_turn) + " · " + node.message,
                style = DsType.small13,
                color = colors.error,
            )
            node.code?.let { Text(" · $it", style = DsType.caption11, color = colors.labelTertiary) }
        }

        is CommandNode -> CommandRow(node)

        is WorkflowNode -> WorkflowRow(node.data, context.onOpenSubagent)

        is TitleNode -> Text(node.title, style = DsType.caption11, color = colors.labelTertiary)
        is SubagentNode -> Text(
            stringResource(R.string.subagents_title),
            style = DsType.caption11,
            color = colors.labelTertiary,
        )
        // Unknown event types stay visible — that is the compatibility contract — but the
        // structural ones are not "unknown", they are bookkeeping, and printing `step/start` /
        // `step/end` between every tool call buried the actual work in noise.
        is OtherNode -> if (node.type !in STRUCTURAL_EVENT_TYPES) {
            Text(node.type, style = DsType.caption11, color = colors.labelCaption)
        }
    }
}

/** A Markdown-rendered user message bubble. */
@Composable
private fun MarkdownUserBubble(text: String) {
    val colors = DsTheme.colors
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = minOf(525.dp, maxWidth * 0.82f))
                .background(colors.userBubble, DsShapes.bubble)
                .border(1.dp, colors.borderL3, DsShapes.bubble)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            MarkdownText(text)
        }
    }
}

@Composable
private fun ContextInjectionRow(node: ContextMessageNode) {
    val text = node.displayText()
    var expanded by remember(node.seq) { mutableStateOf(false) }
    DisclosureRow(
        title = stringResource(R.string.chat_context_injection),
        summary = node.sourceKind,
        icon = FeatherIcons.Info,
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(DsShapes.block)
                .background(DsTheme.colors.codeBlockBg)
                .border(1.dp, DsTheme.colors.borderL2, DsShapes.block)
                .padding(10.dp),
        ) {
            MarkdownText(text)
        }
    }
}

/** Event types that carry no user-facing content; they frame the transcript rather than fill it. */
internal val STRUCTURAL_EVENT_TYPES = setOf(
    "step/start",
    "step/end",
    "request/header",
    "request/context",
    "session/end-seed",
    "session/title-llm-request",
    "agent/inbox/spliced",
    "assistant/chunk",
    // A model attempt that settled without a message (harness 0.1.3): replay data, not content.
    "assistant/attempt",
)

/**
 * One stored file in a message: its display name and exact size, nothing more.
 *
 * There is no preview and no download — the harness keeps the bytes for the agent's file tools,
 * and the reference the log carries is a content digest rather than a path or a URL. The chip
 * says what was sent, which is all a transcript needs.
 */
@Composable
internal fun FileChip(name: String, bytes: Long, modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    Row(
        modifier = modifier
            .clip(DsShapes.block)
            .background(colors.bgModulePlatform)
            .border(1.dp, colors.borderL3, DsShapes.block)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Outlined.Description,
            contentDescription = null,
            tint = colors.labelSecondary,
            modifier = Modifier.size(18.dp),
        )
        Column {
            Text(
                name,
                style = DsType.small13,
                color = colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(fileSizeText(bytes), style = DsType.caption11, color = colors.labelTertiary)
        }
    }
}

// ---------------------------------------------------------------------------
// Assistant messages
// ---------------------------------------------------------------------------

@Composable
private fun AssistantMessage(node: AssistantMessageNode, context: ChatNodeContext) {
    val colors = DsTheme.colors
    val isLast = context.nodes.lastOrNull()?.seq == node.seq
    // A message the harness marked as a cancelled turn's prefix arrives before that turn's end,
    // so `running` is still true for a frame. Without this the last thing the user sees after
    // tapping stop is the answer apparently still being written.
    val streaming = context.running && isLast && !node.interrupted
    val reasoningExpanded = remember(node.seq) { mutableStateMapOf<Int, Boolean>() }
    var actionsVisible by remember(node.seq) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !streaming) { actionsVisible = !actionsVisible },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        node.blocks.forEachIndexed { index, block ->
            when (block.kind) {
                "text" -> MarkdownText(block.text.orEmpty())
                "reasoning" -> {
                    val expanded = reasoningExpanded[index] ?: false
                    ThinkingRow(
                        summary = block.text?.lineSequence()?.firstOrNull()
                            ?: stringResource(R.string.chat_thinking),
                        expanded = expanded,
                        onToggle = { reasoningExpanded[index] = !expanded },
                        streaming = streaming,
                    )
                    AnimatedVisibility(visible = expanded) {
                        MarkdownText(block.text.orEmpty())
                    }
                }
                // Tool calls arrive as their own nodes and render as cards; the inline block is a
                // duplicate reference, so it stays quiet here.
                "tool-call", "tool-result" -> Unit
                "image" -> parseImageRef(block)?.let { ref ->
                    AttachmentImage(
                        attachmentId = ref.attachmentId,
                        intrinsicWidth = ref.width,
                        intrinsicHeight = ref.height,
                        contentDescription = ref.name,
                    )
                }
                "file" -> parseFileRef(block)?.let { ref -> FileChip(
                            name = ref.name,
                            bytes = ref.bytes,
                            modifier = Modifier.clickable(enabled = context.onOpenFile != null) {
                                context.onOpenFile?.invoke(parseWorkspaceFilePath(block) ?: ref.name, ref.name)
                            },
                        ) }
                else -> block.text?.let {
                    Text(it, style = DsType.caption11, color = colors.labelTertiary)
                }
            }
        }
        if (node.interrupted) {
            DsPill(text = stringResource(R.string.chat_stopped), warn = true)
        }
        AnimatedVisibility(
            visible = actionsVisible && !streaming,
            enter = fadeIn(DsAnimations.fade),
            exit = fadeOut(DsAnimations.fade),
        ) {
            MessageActionsRow(node, context)
        }
    }
}

/**
 * Per-message actions, revealed on tap rather than always shown — a transcript with a row of icons
 * under every message reads as clutter, and these are all occasional.
 */
@Composable
private fun MessageActionsRow(node: AssistantMessageNode, context: ChatNodeContext) {
    val colors = DsTheme.colors
    val clipboard = LocalClipboardManager.current
    Row(
        modifier = Modifier.padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionIcon(Icons.Filled.ContentCopy, stringResource(R.string.chat_copy_message)) {
            clipboard.setText(AnnotatedString(node.plainText))
        }
        ActionIcon(Icons.AutoMirrored.Outlined.CallSplit, stringResource(R.string.chat_branch_message)) {
            context.onBranchFrom(node.seq)
        }
        ActionIcon(Icons.Filled.ThumbUp, stringResource(R.string.chat_feedback_up)) {
            context.onFeedback(node.seq, true)
        }
        ActionIcon(Icons.Filled.ThumbDown, stringResource(R.string.chat_feedback_down)) {
            context.onFeedback(node.seq, false)
        }
    }
}

@Composable
private fun ActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Icon(
        icon,
        contentDescription = label,
        tint = DsTheme.colors.labelTertiary,
        modifier = Modifier
            .size(28.dp)
            .clickable(onClick = onClick)
            .padding(6.dp),
    )
}

@Composable
private fun ProducedFilesRow(node: TurnEndNode, context: ChatNodeContext) {
    val paths = remember(node.turn, context.nodes) {
        val successfulCalls = context.nodes.filterIsInstance<ToolResultNode>()
            .filter { it.turn == node.turn && !it.isError }
            .map { it.callId }
            .toSet()
        context.nodes
            .filterIsInstance<ToolCallNode>()
            .filter { it.turn == node.turn && it.callId in successfulCalls }
            .filter { it.name in setOf("write", "edit", "str_replace_editor") }
            .mapNotNull { changedFilePath(it) }
            .distinct()
    }
    if (paths.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.chat_produced_files), style = DsType.caption11, color = DsTheme.colors.labelTertiary)
        paths.take(6).forEach { path ->
            Row(
                modifier = Modifier
                    .clip(DsShapes.pillFull)
                    .background(DsTheme.colors.bgModulePlatform)
                    .clickable(enabled = context.onOpenFile != null) { context.onOpenFile?.invoke(path, basename(path)) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Description, contentDescription = null, modifier = Modifier.size(14.dp), tint = DsTheme.colors.labelSecondary)
                Text(basename(path), style = DsType.caption11, color = DsTheme.colors.labelSecondary, modifier = Modifier.padding(start = 4.dp))
            }
        }
        if (paths.size > 6) Text(stringResource(R.string.chat_more_files, paths.size - 6), style = DsType.caption11, color = DsTheme.colors.labelTertiary)
    }
}

private fun changedFilePath(call: ToolCallNode): String? = runCatching {
    val args = kotlinx.serialization.json.Json.parseToJsonElement(call.arguments) as? JsonObject ?: return@runCatching null
    args["file_path"]?.toString()?.trim('"') ?: args["path"]?.toString()?.trim('"')
}.getOrNull()

// ---------------------------------------------------------------------------
// Tool calls
// ---------------------------------------------------------------------------

@Composable
private fun ToolCallRow(node: ToolCallNode, context: ChatNodeContext) {
    val colors = DsTheme.colors
    val result = context.nodes
        .filterIsInstance<ToolResultNode>()
        .firstOrNull { it.callId == node.callId }

    val card = buildToolCardView(
        call = node,
        result = result,
        running = result == null && context.running,
        cwd = context.cwd,
        home = context.home,
    )
    // The row header is derived here rather than taken from the card: only this layer knows the
    // tool's name and the session's cwd, which is what turns an absolute path into `app\build.gradle.kts`.
    val row = toolRowModel(
        toolName = node.name,
        argumentsJson = node.arguments,
        cwd = context.cwd,
        // Derived from the call itself now: 0.1.2 sends no presenter title to prefer over it.
        viewTitle = null,
    )
    var expanded by remember(node.callId) { mutableStateOf(false) }
    // The leading slot carries the outcome: a red dot for a failed call, the tool glyph otherwise.
    val state = when {
        result?.isError == true -> DisclosureState.Error
        result == null && context.running -> DisclosureState.Running
        else -> DisclosureState.Idle
    }
    ToolCard(
        view = card,
        expanded = expanded,
        onToggle = { expanded = !expanded },
        titleOverride = row.title,
        summaryOverride = row.summary,
        iconOverride = row.variant.featherIcon(),
        state = state,
    )
    if (result?.isError == true) {
        // The dot is colour-only, so the word stays — but without a second dot beside it.
        Text(
            stringResource(R.string.common_error),
            style = DsType.caption11,
            color = colors.error,
            modifier = Modifier.padding(start = 26.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// Compaction / commands / workflow
// ---------------------------------------------------------------------------

@Composable
private fun CompactionRow(node: CompactionNode) {
    val summaryText = remember(node.seq) {
        runCatching {
            val array = (node.data as? JsonObject)?.get("summary") as? JsonArray
            array?.mapNotNull { (it as? JsonObject)?.get("text").asString() }?.joinToString("\n")
        }.getOrNull()
    }
    var expanded by remember(node.seq) { mutableStateOf(false) }
    DisclosureRow(
        title = stringResource(R.string.chat_compaction),
        summary = stringResource(R.string.chat_compaction_summary),
        icon = FeatherIcons.Archive,
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        if (!summaryText.isNullOrBlank()) MarkdownText(summaryText)
    }
}

@Composable
private fun CommandRow(node: CommandNode) {
    val colors = DsTheme.colors
    val data = node.data as? JsonObject
    val name = data?.get("name").asString() ?: node.kind
    val text = data?.get("text").asString()
    var expanded by remember(node.seq) { mutableStateOf(false) }
    DisclosureRow(
        title = "/$name",
        summary = text,
        icon = FeatherIcons.Terminal,
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        Text(
            node.data.toString(),
            style = DsType.caption11.copy(fontFamily = DsType.codeFont),
            color = colors.labelCaption,
            modifier = Modifier.padding(start = 28.dp, top = 2.dp),
        )
    }
}

@Composable
private fun WorkflowRow(
    data: kotlinx.serialization.json.JsonElement,
    onOpenMember: (String) -> Unit,
) {
    val colors = DsTheme.colors
    val obj = data as? JsonObject ?: return
    val name = obj["name"].asString()
    val status = obj["status"].asString() ?: obj["stopReason"].asString() ?: obj["outcome"].asString()
    val members = remember(data) { parseWorkflowMembers(data) }
    var expanded by remember(data) { mutableStateOf(false) }
    DisclosureRow(
        title = stringResource(R.string.workflow_title),
        summary = listOfNotNull(name, workflowStatusLabel(status)).joinToString(" · ").ifEmpty { null },
        icon = FeatherIcons.GitBranch,
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        members.forEach { member ->
            val memberChildId = member.childId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 28.dp, top = 2.dp)
                    .then(
                        if (memberChildId != null) {
                            Modifier.clickable { onOpenMember(memberChildId) }
                        } else {
                            Modifier
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StateDot(workflowMemberDot(member.status), size = 8.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    member.label ?: memberChildId.orEmpty(),
                    style = DsType.small13,
                    color = colors.labelSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                member.status?.let {
                    Text(
                        workflowStatusLabel(it) ?: it,
                        style = DsType.caption11,
                        color = colors.labelTertiary,
                    )
                }
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}
