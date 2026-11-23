package dev.dsh.mobile.mesh.ui.screens.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.core.session.AssistantMessageNode
import dev.dsh.mobile.mesh.core.session.ChatNode
import dev.dsh.mobile.mesh.core.session.ContextMessageNode
import dev.dsh.mobile.mesh.core.session.ConversationSnapshot
import dev.dsh.mobile.mesh.core.session.QuestionReplyNode
import dev.dsh.mobile.mesh.core.session.ToolCallNode
import dev.dsh.mobile.mesh.core.session.ToolResultNode
import dev.dsh.mobile.mesh.core.session.UserMessageNode
import dev.dsh.mobile.mesh.core.wire.dto.SessionStatsView
import dev.dsh.mobile.mesh.core.wire.dto.TokenUsageView
import dev.dsh.mobile.mesh.ui.components.DisclosureRow
import dev.dsh.mobile.mesh.ui.components.StateDot
import dev.dsh.mobile.mesh.ui.components.StateDotState
import dev.dsh.mobile.mesh.ui.components.formatDurationMs
import dev.dsh.mobile.mesh.ui.components.formatTokens

private val MonoCaption: TextStyle @Composable get() =
    MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)

/**
 * The turn-by-turn ledger: what the agent did, in order, with its inputs and outputs.
 *
 * Where the Chat tab shows the conversation as it reads, this shows it as it *ran* — every tool
 * call with its arguments and result, grouped by turn. It replaces the cramped trajectory section
 * that used to sit inside the details panel; one home per fact.
 */
@Composable
internal fun TrajectoryTab(
    conversation: ConversationSnapshot?,
    stats: SessionStatsView?,
    usage: TokenUsageView?,
    cwd: String?,
    listState: LazyListState,
    disclosureScope: DisclosureScope,
    modifier: Modifier = Modifier,
) {
    val nodes = conversation?.nodes ?: emptyList()
    val groups = remember(nodes) { groupByTurn(nodes) }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (groups.isEmpty()) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.trajectory_empty),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        groups.forEach { (turn, turnNodes) ->
            item(key = "turn-$turn") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.trajectory_turn, turn),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stringResource(R.string.trajectory_steps, turnNodes.count { it is ToolCallNode }),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(
                count = turnNodes.size,
                key = { index -> "n-${turnNodes[index].seq}" },
            ) { index ->
                TrajectoryRow(turnNodes[index], turnNodes, cwd, disclosureScope)
            }
        }
        if (stats != null || usage != null) {
            item(key = "totals") {
                Spacer(Modifier.width(8.dp))
                TrajectoryTotals(stats, usage)
            }
        }
    }
}

@Composable
private fun TrajectoryRow(node: ChatNode, siblings: List<ChatNode>, cwd: String?, disclosureScope: DisclosureScope) {
    when (node) {
        is UserMessageNode -> {
            val preview = node.previewText.trim()
            if (preview.isNotEmpty()) {
                Text("> $preview", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // Model context has a model-facing user role but was not typed by the user; omit it from
        // the trajectory's user-input ledger just as the main transcript avoids a user bubble.
        is ContextMessageNode -> Unit
        is AssistantMessageNode -> {
            val snippet = node.plainText.trim().take(160)
            if (snippet.isNotEmpty()) {
                Text(
                    snippet,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        is QuestionReplyNode -> {
            val skipped = stringResource(R.string.questions_skip)
            val summary = node.questions.joinToString(" · ") { question ->
                val answer = node.answers.firstOrNull { it.id == question.id }
                val values = answer?.selected.orEmpty() +
                    listOfNotNull(answer?.custom?.trim()?.takeIf { it.isNotEmpty() })
                "${question.header?.takeIf { it.isNotBlank() && it != question.question } ?: question.question}: " +
                    values.joinToString(", ").ifEmpty { skipped }
            }
            if (summary.isNotBlank()) {
                Text(
                    "> $summary",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        is ToolCallNode -> {
            val result = siblings
                .filterIsInstance<ToolResultNode>()
                .firstOrNull { it.callId == node.callId }
            ToolLedgerRow(node, result, cwd, disclosureScope)
        }
        else -> Unit
    }
}

@Composable
private fun ToolLedgerRow(call: ToolCallNode, result: ToolResultNode?, cwd: String?, disclosureScope: DisclosureScope) {
    val row = remember(call.callId, cwd) { toolRowModel(call.name, call.arguments, cwd) }
    val expanded = rememberDisclosure(disclosureScope, DisclosureKeys.trajectoryCall(call.callId))
    Row(verticalAlignment = Alignment.CenterVertically) {
        StateDot(
            when {
                result == null -> StateDotState.Running
                result.isError -> StateDotState.Error
                else -> StateDotState.Done
            },
            size = 8.dp,
        )
        Spacer(Modifier.width(6.dp))
        DisclosureRow(
            title = row.title ?: stringResource(row.variant.titleResource()),
            summary = row.summary,
            // The ledger already leads with its own state dot, so the slot keeps the glyph.
            icon = row.variant.featherIcon(),
            expanded = expanded.expanded,
            onToggle = expanded.onToggle,
            modifier = Modifier.weight(1f),
        ) {
            Column(Modifier.padding(start = 28.dp, top = 2.dp)) {
                Text(stringResource(R.string.chat_input_placeholder), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(call.arguments, style = MonoCaption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                result?.content?.let { content ->
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.chat_output_placeholder), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        content.toString(),
                        style = MonoCaption,
                        color = if (result.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TrajectoryTotals(stats: SessionStatsView?, usage: TokenUsageView?) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(
            stringResource(R.string.trajectory_tab_usage),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        stats?.let {
            Text(
                stringResource(R.string.chat_stats_turns, it.turns, it.steps),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(
                    R.string.chat_stats_timing,
                    formatDurationMs(it.llmMs),
                    formatDurationMs(it.toolMs),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(
                    R.string.chat_stats_speed,
                    formatDurationMs(it.meanTtftMs),
                    it.tokensPerSecond?.let { rate -> String.format(java.util.Locale.US, "%.0f", rate) } ?: "—",
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        usage?.let {
            Text(
                stringResource(
                    R.string.trajectory_usage,
                    formatTokens(it.inputTokens),
                    formatTokens(it.outputTokens),
                    formatTokens(it.cacheReadTokens),
                    formatTokens(it.cacheWriteTokens),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
