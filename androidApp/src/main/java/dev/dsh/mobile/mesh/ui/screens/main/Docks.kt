package dev.dsh.mobile.mesh.ui.screens.main

import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.core.session.QueueItem
import dev.dsh.mobile.mesh.core.wire.dto.GoalPhase
import dev.dsh.mobile.mesh.core.wire.dto.GoalSnapshot
import dev.dsh.mobile.mesh.core.wire.dto.SessionStatsView
import dev.dsh.mobile.mesh.core.wire.dto.TokenUsageView
import dev.dsh.mobile.mesh.data.SessionStore
import dev.dsh.mobile.mesh.data.canMutateQueueItem
import dev.dsh.mobile.mesh.data.canSteerQueueItem
import dev.dsh.mobile.mesh.data.queueEditLosesNonText
import dev.dsh.mobile.mesh.data.queueEditSeedText
import dev.dsh.mobile.mesh.ui.components.DisclosureRow
import dev.dsh.mobile.mesh.ui.components.AppDialogBodyMaxHeight
import dev.dsh.mobile.mesh.ui.components.FeatherIcons
import dev.dsh.mobile.mesh.ui.components.StateDot
import dev.dsh.mobile.mesh.ui.components.StatusBadge
import dev.dsh.mobile.mesh.ui.components.formatDurationMs
import dev.dsh.mobile.mesh.ui.components.formatTokens
import kotlinx.coroutines.launch

/**
 * The strip of persistent context between the transcript and the composer: to-dos, the ongoing
 * goal, the queue, and the run statistics.
 *
 * These render as collapsed one-line summaries and expand on tap, rather than appearing and
 * vanishing as their data arrives. A dock that pops into existence mid-turn shoves the transcript
 * and the composer around while you are reading or typing, which is most of what made the old
 * layout feel unsettled. Which ones are expanded is reader intent, so it is held per session rather
 * than by this composition.
 *
 * [disclosureKey] is the caller's to choose on the to-do list: the dock above the composer and the
 * to-do event inside the transcript show the same list, but they are two rows on screen, and one
 * tapping the other open would be a surprise.
 */
@Composable
internal fun TodoDock(
    todos: List<TodoEntry>,
    disclosureKey: String,
    disclosureScope: DisclosureScope,
    modifier: Modifier = Modifier,
) {
    if (todos.isEmpty()) return
    val expanded = rememberDisclosure(disclosureScope, disclosureKey)
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
        expanded = expanded.expanded,
        onToggle = expanded.onToggle,
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
                Text(
                    todo.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
    Text(
        stringResource(R.string.goal_title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    StatusBadge(text = stringResource(goalPhaseLabelRes(goal.phase)))
    Spacer(Modifier.height(4.dp))
    Text(goal.objective, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    goal.blockedReason?.let {
        Text(
            stringResource(R.string.goal_blocked_reason, it.message),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
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
    var editing by remember { mutableStateOf(false) }
    var editText by remember(goal.revision) { mutableStateOf(goal.objective) }
    var menuOpen by remember { mutableStateOf(false) }
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
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        StatusBadge(text = stringResource(goalPhaseLabelRes(goal.phase)))
        Spacer(Modifier.width(4.dp))
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.goal_edit),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                when (goal.phase) {
                    GoalPhase.ACTIVE -> DropdownMenuItem(
                        text = { Text(stringResource(R.string.goal_pause)) },
                        onClick = {
                            menuOpen = false
                            scope.launch { store.goalAction("pause") }
                        },
                    )
                    GoalPhase.PAUSED, GoalPhase.BLOCKED -> DropdownMenuItem(
                        text = { Text(stringResource(R.string.goal_resume)) },
                        onClick = {
                            menuOpen = false
                            scope.launch { store.goalAction("resume") }
                        },
                    )
                    GoalPhase.COMPLETE -> Unit
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.goal_edit)) },
                    onClick = {
                        menuOpen = false
                        editing = true
                    },
                )
                DropdownMenuItem(
                    text = {
                        Text(stringResource(R.string.goal_clear), color = MaterialTheme.colorScheme.error)
                    },
                    onClick = {
                        menuOpen = false
                        scope.launch { store.goalAction("clear") }
                    },
                )
            }
        }
    }
    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(stringResource(R.string.goal_edit)) },
            text = {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = AppDialogBodyMaxHeight()),
                    // Same defect as the queued-turn editor: an unbounded field pushes the OK row
                    // off the screen once the objective is long enough.
                    minLines = 1,
                    maxLines = 12,
                    placeholder = { Text(stringResource(R.string.goal_title)) },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { store.goalAction("edit", editText) }
                    editing = false
                }) { Text(stringResource(R.string.common_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

/** Pending turns, with the edit / remove / steer verbs the harness exposes. */
@Composable
internal fun QueueDock(
    queue: List<QueueItem>,
    store: SessionStore,
    onInsertQueued: (QueueItem) -> Unit,
    running: Boolean,
    disclosureScope: DisclosureScope,
    queueSubmissionPending: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val pendingQueue = queue.filter { it.placement == "queued" }
    if (pendingQueue.isEmpty() && !queueSubmissionPending) return
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val queueInsertedLabel = stringResource(R.string.chat_queue_inserted)
    // Keyed on the row, not on the queue length: the queue grows precisely when the reader is using
    // it, and collapsing on every new entry made it impossible to read a list you had just opened.
    val expanded = rememberDisclosure(disclosureScope, DisclosureKeys.dock("queue"))
    var editingId by remember { mutableStateOf<String?>(null) }
    var editText by remember { mutableStateOf("") }
    var editWarningItem by remember { mutableStateOf<QueueItem?>(null) }
    var menuOpenId by remember { mutableStateOf<String?>(null) }

    DisclosureRow(
        title = if (queueSubmissionPending && pendingQueue.isEmpty()) {
            stringResource(R.string.chat_queue_submitting)
        } else {
            stringResource(R.string.chat_queue_count, pendingQueue.size)
        },
        summary = null,
        expanded = expanded.expanded,
        onToggle = expanded.onToggle,
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
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Box {
                    IconButton(onClick = { menuOpenId = item.id }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.chat_queue_actions),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpenId == item.id,
                        onDismissRequest = { menuOpenId = null },
                    ) {
                        if (canMutateQueueItem(item)) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_queue_edit)) },
                                onClick = {
                                    menuOpenId = null
                                    // updateQueue's text-only edit replaces *all* blocks. Warn
                                    // before opening the editor if this message contains an image,
                                    // file or another non-text block; continuing will discard those.
                                    if (queueEditLosesNonText(item)) {
                                        editWarningItem = item
                                    } else {
                                        editingId = item.id
                                        // The full message, not the dock row's 200-char preview.
                                        editText = queueEditSeedText(item)
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.chat_queue_remove),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    menuOpenId = null
                                    scope.launch { store.updateQueue(item.id, "remove") }
                                },
                            )
                            if (canSteerQueueItem(item, running)) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_queue_steer)) },
                                    onClick = {
                                        menuOpenId = null
                                        scope.launch { store.updateQueue(item.id, "steer") }
                                    },
                                )
                            }
                        }
                        DropdownMenuItem(
                            text = { Text(queueInsertedLabel) },
                            onClick = {
                                menuOpenId = null
                                onInsertQueued(item)
                                Toast.makeText(context, queueInsertedLabel, Toast.LENGTH_SHORT).show()
                            },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(2.dp))
    }

    editWarningItem?.let { item ->
        AlertDialog(
            onDismissRequest = { editWarningItem = null },
            title = { Text(stringResource(R.string.chat_queue_edit_warning_title)) },
            text = {
                Text(
                    stringResource(R.string.chat_queue_edit_warning_body),
                    color = MaterialTheme.colorScheme.error,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    editText = queueEditSeedText(item)
                    editingId = item.id
                    editWarningItem = null
                }) { Text(stringResource(R.string.chat_queue_edit_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { editWarningItem = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    editingId?.let { id ->
        AlertDialog(
            onDismissRequest = { editingId = null },
            title = { Text(stringResource(R.string.chat_queue_edit)) },
            text = {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = AppDialogBodyMaxHeight()),
                    // A queued turn can be as long as the composer accepts, and this field used to
                    // grow to all of it: the plate ran off the bottom of the screen and took the OK
                    // row with it, so a long message was both unreadable and impossible to save.
                    minLines = 3,
                    maxLines = 12,
                    placeholder = { Text(stringResource(R.string.chat_composer_hint)) },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { store.updateQueue(id, "edit", editText) }
                    editingId = null
                }) { Text(stringResource(R.string.common_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { editingId = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
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
    disclosureScope: DisclosureScope,
    modifier: Modifier = Modifier,
) {
    if (stats == null && usage == null) return
    val expanded = rememberDisclosure(disclosureScope, DisclosureKeys.dock("stats"))
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
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded.toggle() }
                .padding(vertical = 2.dp),
        )
        if (expanded.expanded) {
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
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}
