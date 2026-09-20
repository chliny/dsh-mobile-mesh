package dev.dsh.mobile.mesh.ui.screens.main

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.core.session.QueueItem
import dev.dsh.mobile.mesh.data.canMutateQueueItem
import dev.dsh.mobile.mesh.data.canSteerQueueItem
import dev.dsh.mobile.mesh.core.wire.dto.GoalPhase
import dev.dsh.mobile.mesh.core.wire.dto.GoalSnapshot
import dev.dsh.mobile.mesh.core.wire.dto.SessionStatsView
import dev.dsh.mobile.mesh.core.wire.dto.TokenUsageView
import dev.dsh.mobile.mesh.data.SessionStore
import dev.dsh.mobile.mesh.ui.components.DisclosureRow
import dev.dsh.mobile.mesh.ui.components.DsButton
import dev.dsh.mobile.mesh.ui.components.DsButtonVariant
import dev.dsh.mobile.mesh.ui.components.DsDialog
import dev.dsh.mobile.mesh.ui.components.DsMenu
import dev.dsh.mobile.mesh.ui.components.DsPill
import dev.dsh.mobile.mesh.ui.components.FeatherIcons
import dev.dsh.mobile.mesh.ui.components.MenuItem
import dev.dsh.mobile.mesh.ui.components.SectionHeader
import dev.dsh.mobile.mesh.ui.components.StateDot
import dev.dsh.mobile.mesh.ui.components.formatDurationMs
import dev.dsh.mobile.mesh.ui.components.formatTokens
import dev.dsh.mobile.mesh.ui.theme.DsTheme
import dev.dsh.mobile.mesh.ui.theme.DsType
import kotlinx.coroutines.launch

/**
 * The strip of persistent context between the transcript and the composer: to-dos, the ongoing
 * goal, the queue, and the run statistics.
 *
 * These render as collapsed one-line summaries and expand on tap, rather than appearing and
 * vanishing as their data arrives. A dock that pops into existence mid-turn shoves the transcript
 * and the composer around while you are reading or typing, which is most of what made the old
 * layout feel unsettled.
 */

@Composable
internal fun TodoDock(todos: List<TodoEntry>, modifier: Modifier = Modifier) {
    if (todos.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val completed = todos.count { it.status == "completed" }
    val inProgress = todos.count { it.status == "in_progress" }
    val pending = todos.size - completed - inProgress
    val progress = todoProgressLabel(
        completed = completed,
        inProgress = inProgress,
        pending = pending,
        completedLabel = stringResource(R.string.chat_todo_progress_done, completed),
        inProgressLabel = stringResource(R.string.chat_todo_progress_active, inProgress),
        pendingLabel = stringResource(R.string.chat_todo_progress_pending, pending),
    )
    DisclosureRow(
        title = stringResource(R.string.chat_todo_title),
        summary = progress,
        icon = FeatherIcons.CheckSquare,
        expanded = expanded,
        onToggle = { expanded = !expanded },
        modifier = modifier,
    ) {
        todos.forEach { todo ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 28.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StateDot(todoStatusDot(todo.status), size = 8.dp)
                Spacer(Modifier.width(8.dp))
                Text(todo.content, style = DsType.small13, color = DsTheme.colors.labelSecondary)
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}

/** Matches Web's status summary, omitting zero-count segments. */
internal fun todoProgressLabel(
    completed: Int,
    inProgress: Int,
    pending: Int,
    completedLabel: String,
    inProgressLabel: String,
    pendingLabel: String,
): String = listOfNotNull(
    completedLabel.takeIf { completed > 0 },
    inProgressLabel.takeIf { inProgress > 0 },
    pendingLabel.takeIf { pending > 0 },
).joinToString("\u2002·\u2002")

/** The read-only goal summary shown inline in the transcript when a goal event lands. */
@Composable
internal fun GoalSummary(goal: GoalSnapshot) {
    val colors = DsTheme.colors
    SectionHeader(stringResource(R.string.goal_title))
    DsPill(text = stringResource(goalPhaseLabelRes(goal.phase)))
    Spacer(Modifier.height(4.dp))
    Text(goal.objective, style = DsType.small13, color = colors.labelSecondary)
    goal.blockedReason?.let {
        Text(
            stringResource(R.string.goal_blocked_reason, it.message),
            style = DsType.caption11,
            color = colors.warnLabel,
        )
    }
}

/** The live goal bar above the composer, with its phase verbs. */
@Composable
internal fun GoalBar(goal: GoalSnapshot, store: SessionStore, modifier: Modifier = Modifier) {
    // Completion is terminal. The durable goal/change entry remains in the transcript, but the
    // active composer dock must disappear without making the user clear a completed task manually.
    if (goal.phase == GoalPhase.COMPLETE) return
    val scope = rememberCoroutineScope()
    val colors = DsTheme.colors
    var editing by remember { mutableStateOf(false) }
    var editText by remember(goal.revision) { mutableStateOf(goal.objective) }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        StateDot(
            when (goal.phase) {
                GoalPhase.ACTIVE -> dev.dsh.mobile.mesh.ui.components.StateDotState.Running
                GoalPhase.BLOCKED -> dev.dsh.mobile.mesh.ui.components.StateDotState.Warning
                GoalPhase.COMPLETE -> dev.dsh.mobile.mesh.ui.components.StateDotState.Done
                GoalPhase.PAUSED -> dev.dsh.mobile.mesh.ui.components.StateDotState.Idle
            },
            size = 8.dp,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            goal.objective,
            style = DsType.small13,
            color = colors.labelSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        DsPill(text = stringResource(goalPhaseLabelRes(goal.phase)))
        Spacer(Modifier.width(4.dp))
        DsMenu(
            anchor = { onOpen ->
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.goal_edit),
                    tint = colors.labelTertiary,
                    modifier = Modifier.size(20.dp).clickable(onClick = onOpen),
                )
            },
            items = buildList {
                when (goal.phase) {
                    GoalPhase.ACTIVE -> add(
                        MenuItem(stringResource(R.string.goal_pause)) {
                            scope.launch { store.goalAction("pause") }
                        },
                    )
                    GoalPhase.PAUSED, GoalPhase.BLOCKED -> add(
                        MenuItem(stringResource(R.string.goal_resume)) {
                            scope.launch { store.goalAction("resume") }
                        },
                    )
                    GoalPhase.COMPLETE -> Unit
                }
                add(MenuItem(stringResource(R.string.goal_edit)) { editing = true })
                add(
                    MenuItem(stringResource(R.string.goal_clear), danger = true) {
                        scope.launch { store.goalAction("clear") }
                    },
                )
            },
        )
    }
    if (editing) {
        DsDialog(title = stringResource(R.string.goal_edit), onDismiss = { editing = false }) {
            TextField(
                value = editText,
                onValueChange = { editText = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.goal_title), style = DsType.std14) },
                colors = dialogTextFieldColors(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DsButton(
                    text = stringResource(R.string.common_ok),
                    onClick = {
                        scope.launch { store.goalAction("edit", editText) }
                        editing = false
                    },
                    variant = DsButtonVariant.Info,
                )
                DsButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { editing = false },
                    variant = DsButtonVariant.Ghost,
                )
            }
        }
    }
}

/** Pending turns, with the edit / remove / steer verbs the harness exposes. */
@Composable
internal fun QueueDock(
    queue: List<QueueItem>,
    store: SessionStore,
    onInsertQueued: (QueueItem) -> Unit,
    running: Boolean,
    queueSubmissionPending: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val pendingQueue = queue.filter { it.placement == "queued" }
    if (pendingQueue.isEmpty() && !queueSubmissionPending) return
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val queueInsertedLabel = stringResource(R.string.chat_queue_inserted)
    val colors = DsTheme.colors
    var expanded by remember(pendingQueue.size) { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var editText by remember { mutableStateOf("") }

    DisclosureRow(
        title = if (queueSubmissionPending && pendingQueue.isEmpty()) {
            stringResource(R.string.chat_queue_submitting)
        } else {
            stringResource(R.string.chat_queue_count, pendingQueue.size)
        },
        summary = null,
        expanded = expanded,
        onToggle = { expanded = !expanded },
        modifier = modifier,
    ) {
        pendingQueue.forEach { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 28.dp, top = 2.dp)
                    .clickable {
                        onInsertQueued(item)
                        Toast.makeText(context, queueInsertedLabel, Toast.LENGTH_SHORT).show()
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    item.previewText,
                    modifier = Modifier.weight(1f),
                    style = DsType.small13,
                    color = colors.labelSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                DsMenu(
                    anchor = { onOpen ->
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.chat_queue_actions),
                            tint = colors.labelTertiary,
                            modifier = Modifier.size(18.dp).clickable(onClick = onOpen),
                        )
                    },
                    items = buildList {
                        if (canMutateQueueItem(item)) {
                            add(MenuItem(stringResource(R.string.chat_queue_edit)) {
                                editingId = item.id
                                editText = item.previewText
                            })
                            add(MenuItem(stringResource(R.string.chat_queue_remove), danger = true) {
                                scope.launch { store.updateQueue(item.id, "remove") }
                            })
                            if (canSteerQueueItem(item, running)) {
                                add(MenuItem(stringResource(R.string.chat_queue_steer)) {
                                    scope.launch { store.updateQueue(item.id, "steer") }
                                })
                            }
                        }
                        add(MenuItem(queueInsertedLabel) {
                            onInsertQueued(item)
                            Toast.makeText(context, queueInsertedLabel, Toast.LENGTH_SHORT).show()
                        })
                    },
                )
            }
        }
        Spacer(Modifier.height(2.dp))
    }

    editingId?.let { id ->
        DsDialog(title = stringResource(R.string.chat_queue_edit), onDismiss = { editingId = null }) {
            TextField(
                value = editText,
                onValueChange = { editText = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.chat_composer_hint), style = DsType.std14) },
                colors = dialogTextFieldColors(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DsButton(
                    text = stringResource(R.string.common_ok),
                    onClick = {
                        scope.launch { store.updateQueue(id, "edit", editText) }
                        editingId = null
                    },
                    variant = DsButtonVariant.Info,
                )
                DsButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { editingId = null },
                    variant = DsButtonVariant.Ghost,
                )
            }
        }
    }
}

/**
 * The run statistics line under the transcript.
 *
 * Two of these numbers are aggregates on the wire, not averages: `ttftMs` is a *sum* across
 * `ttftSteps`, and there is no throughput field at all — it comes from decoded tokens over decode
 * milliseconds. Printing them raw would have shown a time-to-first-token of several minutes.
 */
@Composable
internal fun StatsFooter(
    stats: SessionStatsView?,
    usage: TokenUsageView?,
    modifier: Modifier = Modifier,
) {
    if (stats == null && usage == null) return
    val colors = DsTheme.colors
    var expanded by remember { mutableStateOf(false) }
    val parts = buildList {
        stats?.let {
            add(stringResource(R.string.chat_stats_turns, it.turns, it.steps))
            add(
                stringResource(
                    R.string.chat_stats_speed,
                    formatDurationMs(it.meanTtftMs),
                    it.tokensPerSecond?.let { rate -> String.format(java.util.Locale.US, "%.0f", rate) } ?: "—",
                ),
            )
        }
        usage?.cacheHitRatio?.let { add(stringResource(R.string.chat_stats_cache, (it * 100).toInt())) }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
    ) {
        Text(
            parts.joinToString(" · "),
            style = DsType.statsText,
            color = colors.labelCaption,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp),
        )
        if (expanded) {
            stats?.let {
                DetailLine(
                    stringResource(
                        R.string.chat_stats_timing,
                        formatDurationMs(it.llmMs),
                        formatDurationMs(it.toolMs),
                    ),
                )
            }
            usage?.let {
                DetailLine(
                    stringResource(
                        R.string.chat_stats_tokens,
                        formatTokens(it.inputTokens),
                        formatTokens(it.outputTokens),
                    ),
                )
            }
        }
    }
}

@Composable
private fun DetailLine(text: String) {
    Text(
        text,
        style = DsType.statsText,
        color = DsTheme.colors.labelCaption,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}
