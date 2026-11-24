package dev.dsh.mobile.mesh.ui.screens.main

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.core.DshCore
import dev.dsh.mobile.mesh.core.session.ChatNode
import dev.dsh.mobile.mesh.core.session.ConversationSnapshot
import dev.dsh.mobile.mesh.core.session.QueueItem
import dev.dsh.mobile.mesh.core.session.WorkflowNode
import dev.dsh.mobile.mesh.core.wire.dto.AgentPresetListValue
import dev.dsh.mobile.mesh.core.wire.dto.ContextBreakdownView
import dev.dsh.mobile.mesh.core.wire.dto.ContextPressureView
import dev.dsh.mobile.mesh.core.wire.dto.HostDescription
import dev.dsh.mobile.mesh.core.wire.dto.JobView
import dev.dsh.mobile.mesh.core.wire.dto.SessionAddress
import dev.dsh.mobile.mesh.core.wire.dto.SessionModelsValue
import dev.dsh.mobile.mesh.core.wire.dto.SessionStatsView
import dev.dsh.mobile.mesh.core.wire.dto.SubagentListEntry
import dev.dsh.mobile.mesh.core.wire.dto.TokenUsageView
import dev.dsh.mobile.mesh.core.wire.dto.agentTeamView
import dev.dsh.mobile.mesh.data.SessionRow
import dev.dsh.mobile.mesh.ui.components.ContextMeterDetail
import dev.dsh.mobile.mesh.ui.components.DsToastHost
import dev.dsh.mobile.mesh.ui.components.StateDot
import dev.dsh.mobile.mesh.ui.components.StateDotState
import dev.dsh.mobile.mesh.ui.components.StatusBadge
import dev.dsh.mobile.mesh.ui.components.formatDurationMs
import dev.dsh.mobile.mesh.ui.components.formatTokens
import dev.dsh.mobile.mesh.ui.components.rememberDsToast
import dev.dsh.mobile.mesh.ui.rememberConnectionManager
import dev.dsh.mobile.mesh.ui.rememberSessionStore
import dev.dsh.mobile.mesh.ui.theme.DsSpacing
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * The session details page: everything about the open session that is not the conversation.
 *
 * A page rather than a drawer. On a phone the 300dp side sheet halved the transcript to show a
 * column of facts, and the facts themselves are a vertical list anyway — so it now takes the screen,
 * has its own back affordance, and leaves the conversation exactly as it was when you return.
 *
 * Each section reads as `label: value` rows with one edit affordance per editable field; every edit
 * control is the same text button, and every whole-section action is the same outlined button. The
 * trajectory ledger is not duplicated here — it has its own tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailsPanel(
    onClose: () -> Unit,
    disclosures: DisclosureScope,
    onOpenSubagent: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onClose)
    val store = rememberSessionStore()
    val manager = rememberConnectionManager()
    val connection by manager.state.collectAsState()
    var observedGeneration by remember(manager) { mutableStateOf(manager.generation) }
    LaunchedEffect(manager) {
        manager.connectedGenerations.onSubscription {
            manager.generation?.let { emit(it) }
        }.collect { observedGeneration = it }
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val toast = rememberDsToast()
    val clipboard = LocalClipboardManager.current

    val conversation by store.currentConversation.collectAsState()
    val jobs by store.jobs.collectAsState()
    val hostInfo by store.hostInfo.collectAsState()
    val subagents by store.subagents.collectAsState()
    val sessions by store.sessions.collectAsState()
    val currentSessionId by store.currentSessionId.collectAsState()
    val currentAddress by store.currentSessionAddress.collectAsState()
    val stats by store.sessionStats.collectAsState()
    val usage by store.tokenUsage.collectAsState()
    val breakdown by store.contextBreakdown.collectAsState()
    val pressure by store.contextPressure.collectAsState()
    val models by store.models.collectAsState()
    val agentPresets by store.agentPresets.collectAsState()
    val current = sessions.firstOrNull { it.sessionId == currentSessionId }
    // A followed projection is newer than the (possibly cached) session/list hint.
    val currentPreset = ((conversation?.takeIf { it.sessionId == currentSessionId }
        ?.projections?.get("agentPreset")) as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        ?: current?.agentPreset

    LaunchedEffect(currentSessionId) {
        if (currentSessionId != null) store.refreshSessions()
    }

    // This page owns its own sheets rather than reaching back into ChatScreen's: it is reachable on
    // its own, where the chat surface is not even composed behind it.
    var sheet by remember { mutableStateOf<DetailsSheet?>(null) }
    // One page refresh re-reads what this page owns: the session rows and the Host's passive state.
    // Everything below (jobs, subagents, usage, goal) arrives on the session's own streams.
    var refresh by remember { mutableStateOf(0) }

    val savedLabel = stringResource(R.string.chat_export_saved)
    val failedLabel = stringResource(R.string.chat_export_failed)
    val copiedLabel = stringResource(R.string.common_copied)
    val forkCreatedLabel = stringResource(R.string.chat_fork_created)
    val jobKillRequestedLabel = stringResource(R.string.jobs_kill_requested)
    val jobKillFailedLabel = stringResource(R.string.jobs_kill_failed)

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { sink ->
                    store.exportSessionTo(sink, includeDescendants = true)
                } ?: false
            }.getOrDefault(false)
            toast.second(if (ok) savedLabel else failedLabel)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.chat_details_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            refresh++
                            scope.launch { store.refreshSessions() }
                        },
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.common_refresh),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        },
    ) { innerPadding ->
        Box {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = DsSpacing.comfortable, vertical = DsSpacing.small),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
            ) {
                SessionSection(
                    session = current,
                    onRename = { title ->
                        scope.launch { currentSessionId?.let { store.renameSession(it, title) } }
                    },
                )
                ModelPresetSection(
                    models = models,
                    currentPreset = currentPreset,
                    presets = agentPresets,
                    onOpenModels = { sheet = DetailsSheet.Models },
                    onOpenPresets = {
                        // Open the sheet immediately; cached presets render first while a refresh
                        // continues off the interaction path.
                        sheet = DetailsSheet.Presets
                        scope.launch { store.refreshAgentPresets() }
                    },
                )
                ActionSection(
                    exportEnabled = currentSessionId != null,
                    onExport = {
                        exportLauncher.launch("dsh-session-${currentSessionId.orEmpty()}.zip")
                    },
                    onCopy = {
                        scope.launch {
                            store.exportSessionUrl()?.let { url ->
                                clipboard.setText(AnnotatedString(url))
                                toast.second(copiedLabel)
                            }
                        }
                    },
                    onFork = {
                        scope.launch {
                            currentSessionId?.let {
                                store.forkSession(it)
                                toast.second(forkCreatedLabel)
                            }
                        }
                    },
                    onArchive = { scope.launch { currentSessionId?.let { store.archiveSession(it) } } },
                )

                val generation = observedGeneration
                val stateApi = generation?.let { manager.apiForEvent(it.clientId) }
                if (connection.phase == dev.dsh.mobile.mesh.connection.ConnectionPhase.CONNECTED &&
                    generation != null && manager.generation === generation && stateApi != null &&
                    manager.connectedApi === stateApi && currentAddress is SessionAddress.Session &&
                    currentSessionId == (currentAddress as SessionAddress.Session).sessionId
                ) {
                    SessionStateCard(currentSessionId!!, generation, stateApi, refreshSignal = refresh)
                }

                val conv = conversation
                if (conv == null) {
                    Text(
                        stringResource(R.string.chat_details_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = DsSpacing.medium),
                    )
                    HostSection(hostInfo)
                } else {
                    ContextSection(breakdown, pressure, usage, stats)
                    GoalSection(conv, store)
                    JobsSection(jobs, store, scope, disclosures, jobKillRequestedLabel, jobKillFailedLabel, toast.second)
                    QueueSection(conv.queue, store)
                    SubagentsSection(subagents, sessions, onOpen = onOpenSubagent)
                    AgentTeamSection(conv.projections["agentTeam"])
                    WorkflowSection(conv.nodes)
                    HostSection(hostInfo)
                }
            }
            DsToastHost(toast, modifier = Modifier.fillMaxWidth())
        }
    }

    when (sheet) {
        DetailsSheet.Models -> ModelsSheet(models = models, store = store, onDismiss = { sheet = null })
        DetailsSheet.Presets -> PresetsSheet(
            presets = agentPresets,
            currentPreset = currentPreset,
            sessionBlank = current?.blank ?: false,
            store = store,
            onDismiss = { sheet = null },
        )
        null -> Unit
    }
}

/** Which picker, if any, is open over the details page. */
private enum class DetailsSheet { Models, Presets }

// ---------------------------------------------------------------------------
// Sections
// ---------------------------------------------------------------------------

@Composable
private fun SessionSection(
    session: SessionRow?,
    onRename: (String) -> Unit,
) {
    if (session == null) return
    var renaming by remember(session.sessionId) { mutableStateOf(false) }
    val title = session.title?.takeIf { it.isNotBlank() }
        ?: session.cwd?.let { basename(it) }
        ?: session.sessionId
    DetailSection(stringResource(R.string.details_section_session)) {
        DetailRow(stringResource(R.string.details_field_name), title) {
            DetailEditButton { renaming = true }
        }
        session.cwd?.takeIf { it.isNotBlank() }?.let {
            DetailRow(stringResource(R.string.details_field_folder), it)
        }
        DetailRow(
            stringResource(R.string.details_field_status),
            stringResource(
                if (session.running) R.string.session_state_running else R.string.details_value_idle,
            ),
        )
    }
    if (renaming) {
        RenameDialog(
            initial = session.title.orEmpty(),
            title = stringResource(R.string.chatlist_session_rename),
            onDismiss = { renaming = false },
            onConfirm = {
                onRename(it)
                renaming = false
            },
        )
    }
}

@Composable
private fun ModelPresetSection(
    models: SessionModelsValue?,
    currentPreset: String?,
    presets: AgentPresetListValue?,
    onOpenModels: () -> Unit,
    onOpenPresets: () -> Unit,
) {
    val modelLabel = models?.let { value ->
        val group = value.groups.firstOrNull { it.id == value.current.provider }
        group?.models?.firstOrNull { it.id == value.current.model }?.name ?: value.current.model
    }
    val presetLabel = currentPreset?.let { agentPresetLabel(it, presets) }
        ?: stringResource(R.string.presets_current)
    DetailSection(stringResource(R.string.details_section_model_preset)) {
        DetailRow(stringResource(R.string.details_field_model), modelLabel ?: stringResource(R.string.common_loading)) {
            DetailEditButton(enabled = models != null, onClick = onOpenModels)
        }
        DetailRow(stringResource(R.string.details_field_preset), presetLabel) {
            DetailEditButton(onClick = onOpenPresets)
        }
    }
}

@Composable
private fun ActionSection(
    exportEnabled: Boolean,
    onExport: () -> Unit,
    onCopy: () -> Unit,
    onFork: () -> Unit,
    onArchive: () -> Unit,
) {
    DetailSection(stringResource(R.string.details_section_actions)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = DsSpacing.xsmall),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
        ) {
            DetailActionButton(stringResource(R.string.chat_export), enabled = exportEnabled, onClick = onExport)
            DetailActionButton(stringResource(R.string.common_copy), onClick = onCopy)
            DetailActionButton(stringResource(R.string.chatlist_session_fork), onClick = onFork)
            DetailActionButton(stringResource(R.string.common_archive), onClick = onArchive)
        }
    }
}

@Composable
private fun ContextSection(
    breakdown: ContextBreakdownView?,
    pressure: ContextPressureView?,
    usage: TokenUsageView?,
    stats: SessionStatsView?,
) {
    if (breakdown == null && pressure == null && usage == null && stats == null) return
    DetailSection(stringResource(R.string.chat_context_title)) {
        ContextMeterDetail(breakdown, pressure)
        usage?.let {
            Text(
                stringResource(
                    R.string.chat_stats_tokens,
                    formatTokens(it.inputTokens),
                    formatTokens(it.outputTokens),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            it.cacheHitRatio?.let { ratio ->
                Text(
                    stringResource(R.string.chat_stats_cache, (ratio * 100).toInt()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        stats?.let {
            Text(
                stringResource(
                    R.string.chat_stats_timing,
                    formatDurationMs(it.llmMs),
                    formatDurationMs(it.toolMs),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GoalSection(conversation: ConversationSnapshot, store: dev.dsh.mobile.mesh.data.SessionStore) {
    val goal = parseGoal(conversation.projections["goal"])
    DetailSection(stringResource(R.string.goal_title)) {
        if (goal == null) {
            Text(
                stringResource(R.string.goal_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@DetailSection
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                goal.objective,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(DsSpacing.small))
            StatusBadge(text = stringResource(goalPhaseLabelRes(goal.phase)))
        }
        goal.blockedReason?.let {
            Text(
                stringResource(R.string.goal_blocked_reason, it.message),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (goal.maxGoalRounds > 0) {
            Text(
                stringResource(R.string.goal_max_rounds, goal.maxGoalRounds.toString()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        GoalBar(goal, store)
    }
}

@Composable
private fun JobsSection(
    jobs: List<JobView>,
    store: dev.dsh.mobile.mesh.data.SessionStore,
    scope: kotlinx.coroutines.CoroutineScope,
    disclosureScope: DisclosureScope,
    killRequestedLabel: String,
    killFailedLabel: String,
    showToast: (String) -> Unit,
) {
    val jobOutputs by store.jobOutputById.collectAsState()
    if (jobs.isEmpty()) return
    DetailSection(stringResource(R.string.jobs_title)) {
        // A running job's elapsed time has to be driven by a ticker; reading the clock inside a
        // composable that never recomposes leaves the timer frozen at its first value.
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        val hasRunning = jobs.any { it.finishedAt == null }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(hasRunning, lifecycle) {
            if (hasRunning) tickWhileVisible(lifecycle.startedStates()) {
                now = System.currentTimeMillis()
            }
        }
        jobs.forEach { job ->
            val jobKey = DisclosureKeys.detailsJob(job.id)
            val expanded = disclosureScope.isOpen(jobKey)
            val output = jobOutputs[job.id]
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            disclosureScope.toggle(jobKey)
                            store.setJobOutputFollowed(job.id, !expanded)
                        }
                        .padding(vertical = DsSpacing.xsmall),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StateDot(jobStatusDot(job.status))
                    Spacer(Modifier.width(DsSpacing.small))
                    Column(Modifier.weight(1f)) {
                        Text(
                            job.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            job.detail?.let { "${job.kind} · $it" } ?: job.kind,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        formatDurationMs((job.finishedAt ?: now) - job.startedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (job.status == dev.dsh.mobile.mesh.core.wire.dto.JobStatus.RUNNING ||
                        job.status == dev.dsh.mobile.mesh.core.wire.dto.JobStatus.STOPPING
                    ) {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    showToast(if (store.killJob(job.id)) killRequestedLabel else killFailedLabel)
                                }
                            },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.jobs_stop),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else {
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(18.dp)
                                .graphicsLayer { rotationZ = if (expanded) 180f else 0f },
                        )
                    }
                }
                if (expanded) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = DsSpacing.large, bottom = DsSpacing.small),
                    ) {
                        if (output?.gapBefore == true) {
                            Text(
                                stringResource(R.string.jobs_output_gap),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (output?.lossy == true) {
                            Text(
                                stringResource(R.string.jobs_output_lossy),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        output?.error?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        output?.text?.takeIf { it.isNotEmpty() }?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueSection(queue: List<QueueItem>, store: dev.dsh.mobile.mesh.data.SessionStore) {
    if (queue.isEmpty()) return
    val scope = rememberCoroutineScope()
    DetailSection(stringResource(R.string.details_section_queue)) {
        queue.forEach { item ->
            Row(Modifier.fillMaxWidth().padding(vertical = DsSpacing.xsmall), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        item.previewText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        item.placement,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = { scope.launch { store.updateQueue(item.id, "remove") } },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.common_remove)) }
            }
        }
    }
}

@Composable
private fun SubagentsSection(
    subagents: List<SubagentListEntry>,
    sessions: List<dev.dsh.mobile.mesh.data.SessionRow>,
    onOpen: (String) -> Unit,
) {
    if (subagents.isEmpty()) return
    val sessionsById = remember(sessions) { sessions.associateBy { it.sessionId } }
    var subagentNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val hasRunningTiming = subagents.any { entry ->
        val row = subagentId(entry)?.let(sessionsById::get)
        row?.subagentTiming?.active != null && subagentActivityRunning(entry, row.running)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(hasRunningTiming, lifecycle) {
        if (!hasRunningTiming) return@LaunchedEffect
        tickWhileVisible(lifecycle.startedStates()) {
            subagentNow = System.currentTimeMillis()
        }
    }
    DetailSection(stringResource(R.string.subagents_title)) {
        subagents.forEach { entry ->
            val id = subagentId(entry)
            val label = subagentLabel(entry)?.takeIf { it.isNotBlank() }
            val name = subagentName(entry)
            val child = id?.let(sessionsById::get)
            val running = subagentActivityRunning(entry, child?.running == true)
            val duration = child?.subagentTiming?.let { subagentElapsedMs(it, running, subagentNow) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = canOpenSubagentTranscript(entry)) { id?.let(onOpen) }
                    .padding(vertical = DsSpacing.xsmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StateDot(if (running) StateDotState.Running else StateDotState.Idle)
                Spacer(Modifier.width(DsSpacing.small))
                Column(Modifier.weight(1f)) {
                    Text(
                        label ?: name ?: id.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(
                            name?.takeIf { label != null && it != label },
                            stringResource(if (running) R.string.subagents_running else R.string.subagents_inactive),
                            duration?.let(::formatDurationMs),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * The Host's Agent Team task board, read from the open Session's `agentTeam` projection.
 *
 * The Host owns every fact this section shows: the board order, each task's status, its saved owner
 * name, its blockers, the `ready` flag, and the advisory write-scope warnings. A task is therefore
 * rendered exactly as the Host published it — never re-sorted, filtered, re-derived, or completed
 * with an invented member. A Host that composes no Agent Team service publishes no `agentTeam` key,
 * so the section stays absent instead of showing an empty board.
 */
@Composable
private fun AgentTeamSection(element: JsonElement?) {
    val team = remember(element) { agentTeamView(element) } ?: return
    // Sessions without a Team still carry the registered projection; a board with no tasks and no
    // recorded failure has nothing to tell the reader, so it earns no section.
    if (team.tasks.isEmpty() && team.failure == null) return
    DetailSection(stringResource(R.string.agent_team_title)) {
        team.failure?.let { failure ->
            Text(
                stringResource(R.string.agent_team_failure, failure),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        team.tasks.forEach { task ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = DsSpacing.xsmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StateDot(agentTeamStatusDot(task.status))
                Spacer(Modifier.width(DsSpacing.small))
                Column(Modifier.weight(1f)) {
                    Text(
                        task.subject,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val owner = task.ownerName
                        ?.takeIf { it.isNotBlank() }
                        ?.let { stringResource(R.string.agent_team_owner, it) }
                    val blockers = task.blockedBy
                        .takeIf { it.isNotEmpty() }
                        ?.let { stringResource(R.string.agent_team_blocked_by, it.joinToString(", ")) }
                    val detail = listOfNotNull(owner, blockers).joinToString(" · ")
                    if (detail.isNotEmpty()) {
                        Text(
                            detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Host advisory text (e.g. overlapping write scopes), shown verbatim.
                    task.writeScopeWarnings.forEach { warning ->
                        Text(
                            warning,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Spacer(Modifier.width(DsSpacing.small))
                if (task.ready) {
                    StatusBadge(
                        text = stringResource(R.string.agent_team_ready),
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(Modifier.width(DsSpacing.xsmall))
                StatusBadge(text = agentTeamStatusLabel(task.status))
            }
        }
    }
}

/** Status wording the board reuses from the existing task vocabulary; unknown statuses stay verbatim. */
@Composable
private fun agentTeamStatusLabel(status: String): String = when (status) {
    "pending" -> stringResource(R.string.chat_todo_status_pending)
    "in_progress" -> stringResource(R.string.chat_todo_status_in_progress)
    "completed" -> stringResource(R.string.chat_todo_status_completed)
    // The Host vocabulary may grow; showing its own word beats mislabelling a new state.
    else -> status
}

/** Dot colour for one Host status; readiness and warnings are separate Host facts. */
private fun agentTeamStatusDot(status: String): StateDotState = when (status) {
    "in_progress" -> StateDotState.Running
    "completed" -> StateDotState.Done
    else -> StateDotState.Idle
}

@Composable
private fun WorkflowSection(nodes: List<ChatNode>) {
    val workflows = remember(nodes) { parseWorkflows(nodes) }
    if (workflows.isEmpty()) return
    DetailSection(stringResource(R.string.workflow_title)) {
        workflows.forEach { workflow ->
            Row(Modifier.fillMaxWidth().padding(vertical = DsSpacing.xsmall), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        workflow.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(R.string.workflow_members, workflow.members),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(DsSpacing.small))
                if (workflow.status == WorkflowStatus.Failed) {
                    StatusBadge(
                        text = stringResource(R.string.workflow_failed),
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                } else {
                    StatusBadge(
                        text = stringResource(
                            when (workflow.status) {
                                WorkflowStatus.Running -> R.string.workflow_running
                                WorkflowStatus.Completed -> R.string.workflow_completed
                                WorkflowStatus.Failed -> R.string.workflow_failed
                            },
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun HostSection(hostInfo: HostDescription?) {
    if (hostInfo == null) return
    // The harness's own version used to head this card. No 0.1.2 wire field carries it, so the
    // section names the protocol this build was written against instead — which is this client's
    // fact, not the host's, and is labelled as such rather than dressed up as a host version.
    DetailSection(stringResource(R.string.settings_host_info)) {
        Text(
            stringResource(R.string.connect_protocol_baseline, DshCore.PROTOCOL_BASELINE),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            stringResource(R.string.connect_harness_home, hostInfo.home),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------
// Parsing helpers local to the panel
// ---------------------------------------------------------------------------

internal enum class WorkflowStatus { Running, Completed, Failed }

internal data class WorkflowView(
    val runId: String,
    val name: String,
    val status: WorkflowStatus,
    val members: Int,
)

private class WorkflowBuilder(val runId: String) {
    var name: String = ""
    val memberSeqs = mutableSetOf<String>()
    var status: WorkflowStatus? = null
    var failed = false
}

/** Fold the workflow event family into one row per run. */
internal fun parseWorkflows(nodes: List<ChatNode>): List<WorkflowView> {
    val builders = linkedMapOf<String, WorkflowBuilder>()
    for (node in nodes) {
        if (node !is WorkflowNode) continue
        val data = node.data as? JsonObject ?: continue
        val runId = data["runId"].asString() ?: continue
        val builder = builders.getOrPut(runId) { WorkflowBuilder(runId) }
        when (node.kind) {
            "tool-workflow/run-start" -> data["name"].asString()?.let { builder.name = it }
            "tool-workflow/agent-start" -> data["seq"].asString()?.let { builder.memberSeqs.add(it) }
            "tool-workflow/agent-end" -> {
                val outcome = data["outcome"].asString()
                if (outcome == "failed" || outcome == "cancelled") builder.failed = true
            }
            "tool-workflow/run-end" -> {
                val stop = data["stopReason"].asString()
                builder.status = if (stop == "completed") WorkflowStatus.Completed else WorkflowStatus.Failed
            }
        }
    }
    return builders.values.map { builder ->
        WorkflowView(
            runId = builder.runId,
            name = builder.name.ifBlank { builder.runId },
            status = builder.status ?: if (builder.failed) WorkflowStatus.Failed else WorkflowStatus.Running,
            members = builder.memberSeqs.size,
        )
    }
}
