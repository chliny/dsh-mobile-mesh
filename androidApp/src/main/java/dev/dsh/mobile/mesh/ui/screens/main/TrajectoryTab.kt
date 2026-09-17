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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.core.session.AssistantMessageNode
import dev.dsh.mobile.mesh.core.session.ChatNode
import dev.dsh.mobile.mesh.core.session.ContextMessageNode
import dev.dsh.mobile.mesh.core.session.ConversationSnapshot
import dev.dsh.mobile.mesh.core.session.ToolCallNode
import dev.dsh.mobile.mesh.core.session.ToolResultNode
import dev.dsh.mobile.mesh.core.session.UserMessageNode
import dev.dsh.mobile.mesh.core.wire.dto.SessionStatsView
import dev.dsh.mobile.mesh.core.wire.dto.TokenUsageView
import dev.dsh.mobile.mesh.ui.components.DisclosureRow
import dev.dsh.mobile.mesh.ui.components.SectionHeader
import dev.dsh.mobile.mesh.ui.components.StateDot
import dev.dsh.mobile.mesh.ui.components.StateDotState
import dev.dsh.mobile.mesh.ui.components.formatDurationMs
import dev.dsh.mobile.mesh.ui.components.formatTokens
import dev.dsh.mobile.mesh.ui.theme.DsTheme
import dev.dsh.mobile.mesh.ui.theme.DsType

private val MonoCaption = DsType.caption11.copy(fontFamily = DsType.codeFont)

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
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
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
                    style = DsType.caption11,
                    color = colors.labelTertiary,
                )
            }
        }
        groups.forEach { (turn, turnNodes) ->
            item(key = "turn-$turn") {
                SectionHeader(
                    title = stringResource(R.string.trajectory_turn, turn),
                    action = stringResource(R.string.trajectory_steps, turnNodes.count { it is ToolCallNode }),
                )
            }
            items(
                count = turnNodes.size,
                key = { index -> "n-${turnNodes[index].seq}" },
            ) { index ->
                TrajectoryRow(turnNodes[index], turnNodes, cwd)
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
private fun TrajectoryRow(node: ChatNode, siblings: List<ChatNode>, cwd: String?) {
    val colors = DsTheme.colors
    when (node) {
        is UserMessageNode -> {
            val preview = node.previewText.trim()
            if (preview.isNotEmpty()) {
                Text("> $preview", style = DsType.caption11, color = colors.labelSecondary)
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
                    style = DsType.caption11,
                    color = colors.labelTertiary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        is ToolCallNode -> {
            val result = siblings
                .filterIsInstance<ToolResultNode>()
                .firstOrNull { it.callId == node.callId }
            ToolLedgerRow(node, result, cwd)
        }
        else -> Unit
    }
}

@Composable
private fun ToolLedgerRow(call: ToolCallNode, result: ToolResultNode?, cwd: String?) {
    val colors = DsTheme.colors
    val row = remember(call.callId, cwd) { toolRowModel(call.name, call.arguments, cwd) }
    var expanded by remember(call.callId) { mutableStateOf(false) }
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
            expanded = expanded,
            onToggle = { expanded = !expanded },
            modifier = Modifier.weight(1f),
        ) {
            Column(Modifier.padding(start = 28.dp, top = 2.dp)) {
                Text(stringResource(R.string.chat_input_placeholder), style = DsType.caption11, color = colors.labelCaption)
                Text(call.arguments, style = MonoCaption, color = colors.labelTertiary)
                result?.content?.let { content ->
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.chat_output_placeholder), style = DsType.caption11, color = colors.labelCaption)
                    Text(
                        content.toString(),
                        style = MonoCaption,
                        color = if (result.isError) colors.error else colors.labelTertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun TrajectoryTotals(stats: SessionStatsView?, usage: TokenUsageView?) {
    val colors = DsTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        SectionHeader(stringResource(R.string.trajectory_tab_usage))
        stats?.let {
            Text(
                stringResource(R.string.chat_stats_turns, it.turns, it.steps),
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
            Text(
                stringResource(
                    R.string.chat_stats_timing,
                    formatDurationMs(it.llmMs),
                    formatDurationMs(it.toolMs),
                ),
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
            Text(
                stringResource(
                    R.string.chat_stats_speed,
                    formatDurationMs(it.meanTtftMs),
                    it.tokensPerSecond?.let { rate -> String.format(java.util.Locale.US, "%.0f", rate) } ?: "—",
                ),
                style = DsType.caption11,
                color = colors.labelTertiary,
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
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
        }
    }
}
