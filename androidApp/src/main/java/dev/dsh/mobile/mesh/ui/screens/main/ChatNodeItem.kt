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
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.delay
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.core.session.AssistantMessageNode
import dev.dsh.mobile.mesh.core.session.ChatNode
import dev.dsh.mobile.mesh.core.session.CommandNode
import dev.dsh.mobile.mesh.core.session.CompactionNode
import dev.dsh.mobile.mesh.core.session.ContextMessageNode
import dev.dsh.mobile.mesh.core.session.GoalNode
import dev.dsh.mobile.mesh.core.session.OtherNode
import dev.dsh.mobile.mesh.core.session.PlanModeNode
import dev.dsh.mobile.mesh.core.session.ProducedFilesNode
import dev.dsh.mobile.mesh.core.session.RetryNode
import dev.dsh.mobile.mesh.core.session.TurnEndNode
import dev.dsh.mobile.mesh.core.session.SubagentNode
import dev.dsh.mobile.mesh.core.session.TitleNode
import dev.dsh.mobile.mesh.core.session.TodoNode
import dev.dsh.mobile.mesh.core.session.ToolCallNode
import dev.dsh.mobile.mesh.core.session.ToolResultNode
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
internal fun openWorkspacePath(context: ChatNodeContext, path: String, title: String, onOpen: ((String, String) -> Unit)?) {
    val clean = path.trim().trim('"').replace('\\', '/')
    if (clean.isBlank()) return
    val relative = relativizeToCwd(clean, context.cwd).trimStart('/')
    onOpen?.invoke(relative.ifBlank { clean }, title)
}

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
            "completed" -> Unit
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

        is ProducedFilesNode -> ProducedFilesRow(node.paths, context)

        is PlanModeNode -> DsPill(
            text = stringResource(if (node.active) R.string.plan_mode_on else R.string.plan_mode_off),
            warn = true,
        )

        is CompactionNode -> CompactionRow(node)

        is RetryNode -> RetryRow(node)

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

private fun retryDelayMs(node: RetryNode): Long? =
    (node.data as? kotlinx.serialization.json.JsonObject)
        ?.get("delayMs")
        ?.jsonPrimitive
        ?.longOrNull

internal fun retryDelaySeconds(delayMs: Long): Long = (delayMs + 999L).coerceAtLeast(0L) / 1_000L

@Composable
private fun RetryRow(node: RetryNode) {
    var expanded by remember(node.seq) { mutableStateOf(false) }
    val delayMs = retryDelayMs(node)
    val title = if (delayMs != null) {
        stringResource(R.string.chat_model_request_retried_with_delay, node.attempts, node.maxAttempts, retryDelaySeconds(delayMs))
    } else {
        stringResource(R.string.chat_model_request_retried, node.attempts, node.maxAttempts)
    }
    DisclosureRow(
        title = title,
        icon = FeatherIcons.AlertTriangle,
        expanded = expanded,
        onToggle = { expanded = !expanded },
        state = DisclosureState.Idle,
    ) {
        val details = node.failures.ifEmpty { listOf(node.data.toString()) }
        Column(
            Modifier.fillMaxWidth().padding(start = 26.dp, top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            details.forEachIndexed { index, failure ->
                Text(
                    if (details.size > 1) "${index + 1}. $failure" else failure,
                    style = DsType.caption11,
                    color = DsTheme.colors.error,
                )
            }
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
private fun WaitingForModel(context: ChatNodeContext) {
    var elapsedSeconds by remember { mutableStateOf(0L) }
    LaunchedEffect(context.running) {
        if (!context.running) return@LaunchedEffect
        val started = System.currentTimeMillis()
        while (true) {
            elapsedSeconds = ((System.currentTimeMillis() - started) / 1000L).coerceAtLeast(0L)
            delay(1000L)
        }
    }
    ThinkingRow(
        summary = stringResource(R.string.chat_deep_diving),
        elapsedLabel = stringResource(R.string.chat_waiting_seconds, elapsedSeconds),
        expanded = false,
        onToggle = {},
        streaming = true,
    )
}

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

internal fun producedFileRows(paths: List<String>): List<String> = paths.filter { it.isNotBlank() }

@Composable
private fun ProducedFilesRow(paths: List<String>, context: ChatNodeContext) {
    val rows = producedFileRows(paths)
    if (rows.isEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.chat_produced_files), style = DsType.caption11, color = DsTheme.colors.labelTertiary)
        rows.forEach { path ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(DsShapes.block)
                    .background(DsTheme.colors.bgModulePlatform)
                    .clickable(enabled = context.onOpenFile != null && path.isNotBlank()) { openWorkspacePath(context, path, basename(path), context.onOpenFile) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Description, contentDescription = null, modifier = Modifier.size(16.dp), tint = DsTheme.colors.labelSecondary)
                Text(
                    path,
                    style = DsType.small13,
                    color = DsTheme.colors.labelSecondary,
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

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
    val directFilePath = directFilePathForTool(row.variant, card)
    // The leading slot carries the outcome: a red dot for a failed call, the tool glyph otherwise.
    val state = when {
        result?.isError == true -> DisclosureState.Error
        result == null && context.running -> DisclosureState.Running
        else -> DisclosureState.Idle
    }
    ToolCard(
        view = card,
        expanded = expanded,
        onToggle = {
            if (directFilePath != null && context.onOpenFile != null) {
                openWorkspacePath(context, directFilePath, basename(directFilePath), context.onOpenFile)
            } else {
                expanded = !expanded
            }
        },
        titleOverride = row.title,
        summaryOverride = row.summary,
        iconOverride = row.variant.featherIcon(),
        state = state,
        onOpenFile = { path, title -> openWorkspacePath(context, path, title, context.onOpenFile) },
    )
    if (result?.isError == true) {
        // Tool failures are actionable only when the host's full message survives to the transcript.
        Text(
            toolResultText(result) ?: stringResource(R.string.common_error),
            style = DsType.caption11,
            color = colors.error,
            modifier = Modifier.padding(start = 26.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// Compaction / commands / workflow
// ---------------------------------------------------------------------------

internal fun directFilePathForTool(variant: ToolRowVariant, card: dev.dsh.mobile.mesh.ui.components.ToolCardView): String? = when (variant) {
    ToolRowVariant.Read, ToolRowVariant.Write -> when (card) {
        is dev.dsh.mobile.mesh.ui.components.ToolCardView.ReadCard -> card.path
        is dev.dsh.mobile.mesh.ui.components.ToolCardView.DiffCard -> card.diffs.firstOrNull()?.path
        else -> null
    }
    else -> null
}

@Composable
private fun CompactionRow(node: CompactionNode) {
    val summaryText = remember(node.data) {
        runCatching {
            val data = node.data as? JsonObject
            val array = data?.get("summary") as? JsonArray
            array?.mapNotNull { (it as? JsonObject)?.get("text").asString() }
                ?.joinToString("\n")
                ?.takeIf { it.isNotBlank() }
                ?: data?.get("text").asString()
                ?: data?.get("summaryText").asString()
        }.getOrNull()
    }
    val summaryPreview = summaryText?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }
    var expanded by remember(node.data) { mutableStateOf(false) }
    DisclosureRow(
        title = stringResource(R.string.chat_compaction),
        summary = summaryPreview ?: stringResource(R.string.chat_compaction_summary),
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
