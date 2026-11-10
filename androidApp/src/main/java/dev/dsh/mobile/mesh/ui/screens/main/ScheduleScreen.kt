package dev.dsh.mobile.mesh.ui.screens.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.connection.missingUnaryRemoteEndpoint
import dev.dsh.mobile.mesh.core.wire.RpcError
import dev.dsh.mobile.mesh.core.wire.RpcResult
import dev.dsh.mobile.mesh.core.wire.TransportFailure
import dev.dsh.mobile.mesh.core.wire.TransportFailures
import dev.dsh.mobile.mesh.core.wire.dto.ScheduleCreateRequest
import dev.dsh.mobile.mesh.core.wire.dto.ScheduleEditPatch
import dev.dsh.mobile.mesh.core.wire.dto.ScheduleEditRequest
import dev.dsh.mobile.mesh.core.wire.dto.ScheduleTarget
import dev.dsh.mobile.mesh.core.wire.dto.ScheduleCatalogEntry
import dev.dsh.mobile.mesh.core.wire.dto.ScheduleHistoryRequest
import dev.dsh.mobile.mesh.core.wire.dto.ScheduleHistoryResult
import dev.dsh.mobile.mesh.ui.components.DsIconButton
import dev.dsh.mobile.mesh.ui.rememberConnectionManager
import dev.dsh.mobile.mesh.ui.rememberSessionStore
import dev.dsh.mobile.mesh.ui.theme.DsSpacing
import dev.dsh.mobile.mesh.ui.theme.DsTheme
import dev.dsh.mobile.mesh.ui.theme.DsType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Keep Host catalog metadata out of the compare-and-update expected record. */
internal fun ScheduleCatalogEntry.expectedRecord(): JsonObject = buildJsonObject {
    put("id", id)
    put("kind", kind)
    put("title", title)
    put("prompt", prompt)
    put("scheduledAt", scheduledAt)
    when (kind) {
        "after" -> afterSeconds?.let { put("afterSeconds", it) }
        "every" -> everySeconds?.let { put("everySeconds", it) }
        "daily", "weekly" -> {
            time?.let { put("time", it) }
            timeZone?.let { put("timeZone", it) }
            if (kind == "weekly") weekdays?.let { put("weekdays", kotlinx.serialization.json.JsonArray(it.map(::JsonPrimitive))) }
        }
        "cron" -> {
            expression?.let { put("expression", it) }
            timeZone?.let { put("timeZone", it) }
        }
    }
}

/** Presentation form only; the Host validates and commits the rule. */
internal fun scheduleTimingChange(kind: String, value: String, zone: String, weekdays: String): JsonObject? {
    val input = value.trim()
    val timeZone = zone.trim()
    if (input.isEmpty()) return null
    return when (kind) {
        "rrule" -> buildJsonObject { put("kind", "rrule"); put("rrule", input) }
        "at" -> {
            if (runCatching { Instant.parse(input) }.isFailure) return null
            buildJsonObject { put("kind", "at"); put("at", input) }
        }
        "every" -> {
            val seconds = input.toLongOrNull()?.takeIf { it >= 60 } ?: return null
            buildJsonObject { put("kind", "every"); put("every_seconds", seconds) }
        }
        "daily", "weekly", "cron" -> {
            if (timeZone.isEmpty() || runCatching { ZoneId.of(timeZone) }.isFailure) return null
            val days = if (kind == "weekly") {
                val parsed = weekdays.split(',').map { it.trim().toIntOrNull() ?: return null }
                if (parsed.isEmpty() || parsed.any { it !in 1..7 } || parsed.distinct().size != parsed.size) return null
                parsed.sorted()
            } else emptyList()
            val rule = buildJsonObject {
                if (kind == "cron") put("expression", input) else put("time", input)
                put("time_zone", timeZone)
                if (kind == "weekly") put("weekdays", kotlinx.serialization.json.JsonArray(days.map(::JsonPrimitive)))
            }
            buildJsonObject { put("kind", kind); put(kind, rule) }
        }
        else -> null
    }
}

@Composable
private fun scheduleKindLabel(kind: String): String = when (kind) {
    "at", "after" -> stringResource(R.string.schedule_once)
    "every" -> stringResource(R.string.schedule_interval)
    "daily" -> stringResource(R.string.schedule_daily)
    "weekly" -> stringResource(R.string.schedule_weekly)
    "cron" -> stringResource(R.string.schedule_cron)
    "rrule" -> stringResource(R.string.schedule_rrule)
    else -> kind
}

/**
 * Host status copy. 0.2.2 replaced `inactive` with `paused` and `ended`; every non-active value
 * means the task schedules no further delivery.
 */
@Composable
private fun scheduleStatusLabel(status: String): String = when (status) {
    "active" -> stringResource(R.string.schedule_enabled)
    "paused" -> stringResource(R.string.schedule_paused)
    "ended" -> stringResource(R.string.schedule_ended)
    else -> stringResource(R.string.schedule_inactive)
}

private fun scheduleFailure(error: RpcError, endpoint: String, unsupported: String, unknown: String): String {
    if (missingUnaryRemoteEndpoint(error.code, error.details, endpoint)) return unsupported
    val transport = TransportFailures.of(error)
    return if (transport != null && transport !in setOf(TransportFailure.UNAUTHENTICATED, TransportFailure.TRUST_FENCE, TransportFailure.NOT_FOUND))
        "$unknown ${error.message}" else error.message
}

private fun formatScheduleTime(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault(value)

@Composable
internal fun ScheduleScreen(onBack: () -> Unit, onNewTask: () -> Unit, onOpenSession: (String) -> Unit) {
    val colors = DsTheme.colors
    val manager = rememberConnectionManager()
    val store = rememberSessionStore()
    val connection by manager.state.collectAsStateWithLifecycle()
    val revision by store.scheduleRevision.collectAsStateWithLifecycle()
    val generationId by store.sessionsCurrentGenerationId.collectAsStateWithLifecycle()
    key(connection.host?.id, generationId) {
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf<List<ScheduleCatalogEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var clearBusy by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    val selected = records?.firstOrNull { it.id == selectedId }
    val unavailableText = stringResource(R.string.schedule_unavailable)
    val offlineText = stringResource(R.string.schedule_offline)
    val unsupportedText = stringResource(R.string.schedule_newapi_unsupported)
    val unknownText = stringResource(R.string.schedule_newapi_unknown)

    LaunchedEffect(connection.host?.id, connection.phase, generationId, revision, reload) {
        val generation = manager.generation
        val api = manager.connectedApi
        if (generation == null || generation.clientId != generationId || api == null) {
            error = offlineText
        } else {
            val response = api.scheduleCatalog()
            if (manager.generation === generation && manager.connectedApi === api) {
                when (response) {
                    is RpcResult.Ok -> { records = response.value; error = null }
                    is RpcResult.Err -> error = if (response.error.code == "capability-unavailable") unavailableText else response.error.message
                }
            }
        }
    }
    Column(Modifier.fillMaxSize().background(colors.sidebar).safeDrawingPadding().padding(DsSpacing.medium)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            DsIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back), onClick = {
                if (creating) creating = false else if (selectedId != null) selectedId = null else onBack()
            })
            Text(stringResource(R.string.schedule_title), style = DsType.large20, color = colors.labelPrimary)
            DsIconButton(Icons.Filled.Refresh, stringResource(R.string.schedule_refresh), onClick = { reload++ })
        }
        notice?.let { Text(it, color = colors.labelPrimary) }
        if (creating) {
            ScheduleCreate(generationId, onLegacyNew = onNewTask, onCreated = { creating = false; reload++ })
        } else if (selected == null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.schedule_catalog), color = colors.labelPrimary)
                DsIconButton(Icons.Filled.Add, stringResource(R.string.schedule_new), onClick = { creating = true })
            }
            TextButton(enabled = !clearBusy, onClick = { confirmClear = true }) { Text(stringResource(R.string.schedule_newapi_clear)) }
            if (records == null && error == null) CircularProgressIndicator()
            if (error != null) {
                Text(error ?: "", color = colors.labelPrimary)
                TextButton(onClick = { reload++ }) { Text(stringResource(R.string.schedule_retry)) }
            }
            if (records?.isEmpty() == true) Text(stringResource(R.string.schedule_empty), color = colors.labelPrimary)
            LazyColumn {
                items(records.orEmpty(), key = { it.id }) { task ->
                    Column(Modifier.fillMaxWidth().clickable { selectedId = task.id }.padding(vertical = DsSpacing.small)) {
                        Text(task.title, style = DsType.std14, color = colors.labelPrimary)
                        Text("${scheduleKindLabel(task.kind)} · ${scheduleStatusLabel(task.status)} · ${formatScheduleTime(task.scheduledAt)}", color = colors.labelTertiary, maxLines = 2)
                    }
                }
            }
        } else {
            ScheduleDetail(selected, generationId, onOpenSession, onChanged = { result -> notice = result; reload++ })
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text(stringResource(R.string.schedule_newapi_clear)) },
        text = { Text(stringResource(R.string.schedule_newapi_clear_warning)) },
        confirmButton = { TextButton(onClick = {
            confirmClear = false
            val generation = manager.generation
            val api = manager.connectedApi
            if (generation != null && generation.clientId == generationId && api != null) scope.launch {
                clearBusy = true
                if (manager.generation !== generation || manager.connectedApi !== api) { clearBusy = false; return@launch }
                val result = api.scheduleDeleteInactive()
                if (manager.generation !== generation || manager.connectedApi !== api) return@launch
                when (result) {
                    is RpcResult.Ok -> { error = null; reload++ }
                    is RpcResult.Err -> error = scheduleFailure(result.error, "schedule/deleteInactive", unsupportedText, unknownText)
                }
                clearBusy = false
            } else error = offlineText
        }) { Text(stringResource(R.string.schedule_delete)) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.schedule_cancel)) } },
    )
    }
}

@Composable
private fun ScheduleTargetPicker(
    generationId: String?, kind: String, sessionId: String?,
    onKindChange: (String) -> Unit, onSessionChange: (String) -> Unit,
) {
    val store = rememberSessionStore()
    val sessions by store.sessions.collectAsStateWithLifecycle()
    val baseline by store.sessionsBaselineGenerationId.collectAsStateWithLifecycle()
    Column {
        Text(stringResource(R.string.schedule_newapi_target))
        listOf("session", "new_once", "new").forEach { value ->
            FilterChip(selected = kind == value, onClick = { onKindChange(value) }, label = {
                Text(stringResource(when (value) {
                    "session" -> R.string.schedule_newapi_existing_session
                    "new_once" -> R.string.schedule_newapi_new_once
                    else -> R.string.schedule_newapi_new
                }))
            })
        }
        if (kind == "session") {
            Text(stringResource(R.string.schedule_newapi_select_session))
            if (generationId != null && baseline == generationId) {
                sessions.forEach { row ->
                    FilterChip(selected = row.sessionId == sessionId, onClick = { onSessionChange(row.sessionId) },
                        label = { Text(row.title?.takeIf { it.isNotBlank() } ?: row.sessionId) })
                }
            }
        }
    }
}

@Composable
private fun ScheduleCreate(generationId: String?, onLegacyNew: () -> Unit, onCreated: () -> Unit) {
    val manager = rememberConnectionManager()
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("at") }
    var timing by remember { mutableStateOf("") }
    var zone by remember { mutableStateOf(ZoneId.systemDefault().id) }
    var weekdays by remember { mutableStateOf("1,2,3,4,5") }
    var target by remember { mutableStateOf("new_once") }
    var targetSessionId by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var limit by remember { mutableStateOf<Long?>(null) }
    val unsupported = stringResource(R.string.schedule_newapi_unsupported)
    val unknown = stringResource(R.string.schedule_newapi_unknown)
    val offline = stringResource(R.string.schedule_offline)
    LaunchedEffect(generationId) {
        val generation = manager.generation
        val api = manager.connectedApi
        if (generation == null || generation.clientId != generationId || api == null) { message = offline; return@LaunchedEffect }
        val result = api.scheduleDeliveryLimits()
        if (manager.generation !== generation || manager.connectedApi !== api) return@LaunchedEffect
        when (result) {
            is RpcResult.Ok -> limit = result.value.newSessionIntervalSeconds
            is RpcResult.Err -> if (!missingUnaryRemoteEndpoint(result.error.code, result.error.details, "schedule/deliveryLimits")) message = result.error.message
        }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
        item { Text(stringResource(R.string.schedule_newapi_create)) }
        item { OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.schedule_name)) }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(prompt, { prompt = it }, label = { Text(stringResource(R.string.schedule_instruction)) }, modifier = Modifier.fillMaxWidth()) }
        item {
            listOf("at", "every", "daily", "weekly", "cron", "rrule").chunked(3).forEach { row ->
                Row { row.forEach { value -> FilterChip(selected = kind == value, onClick = { kind = value; timing = "" }, label = { Text(scheduleKindLabel(value)) }) } }
            }
        }
        item { OutlinedTextField(timing, { timing = it }, label = { Text(stringResource(R.string.schedule_timing)) }, supportingText = {
            Text(stringResource(when (kind) {
                "rrule" -> R.string.schedule_newapi_rrule_hint
                "at" -> R.string.schedule_hint_at
                "every" -> R.string.schedule_hint_every
                "cron" -> R.string.schedule_hint_cron
                else -> R.string.schedule_hint_clock
            }))
        }, modifier = Modifier.fillMaxWidth()) }
        if (kind in setOf("daily", "weekly", "cron")) item { OutlinedTextField(zone, { zone = it }, label = { Text(stringResource(R.string.schedule_zone)) }) }
        if (kind == "weekly") item { OutlinedTextField(weekdays, { weekdays = it }, label = { Text(stringResource(R.string.schedule_weekdays)) }) }
        item { ScheduleTargetPicker(generationId, target, targetSessionId, { target = it }, { targetSessionId = it }) }
        if (target == "new") limit?.let { value -> item { Text(stringResource(R.string.schedule_newapi_delivery_limit, value)) } }
        message?.let { value -> item { Text(value) } }
        item { Button(enabled = !busy && title.isNotBlank() && title.length <= 120 && prompt.isNotBlank() && (target != "session" || targetSessionId != null) && scheduleTimingChange(kind, timing, zone, weekdays) != null, onClick = {
            val rule = scheduleTimingChange(kind, timing, zone, weekdays) ?: return@Button
            val generation = manager.generation
            val api = manager.connectedApi
            val request = ScheduleCreateRequest(title.trim(), prompt.trim(), rule, ScheduleTarget(target, if (target == "session") targetSessionId else null))
            if (generation == null || generation.clientId != generationId || api == null) { message = offline; return@Button }
            scope.launch {
                busy = true
                if (manager.generation !== generation || manager.connectedApi !== api) { busy = false; return@launch }
                val result = api.scheduleCreate(request)
                if (manager.generation !== generation || manager.connectedApi !== api) return@launch
                when (result) {
                    is RpcResult.Ok -> onCreated()
                    is RpcResult.Err -> message = scheduleFailure(result.error, "schedule/create", unsupported, unknown)
                }
                busy = false
            }
        }) { Text(stringResource(R.string.schedule_newapi_create)) } }
        item { TextButton(onClick = onLegacyNew) { Text(stringResource(R.string.schedule_newapi_legacy_new)) } }
    }
}

@Composable
private fun ScheduleDetail(task: ScheduleCatalogEntry, generationId: String?, onOpenSession: (String) -> Unit, onChanged: (String?) -> Unit) {
    val colors = DsTheme.colors
    val manager = rememberConnectionManager()
    val store = rememberSessionStore()
    val sessions by store.sessions.collectAsStateWithLifecycle()
    val archived by store.archivedSessionIds.collectAsStateWithLifecycle()
    // 0.2.2 rows name their delivery destination in `target`; older rows carry `sessionId`.
    val taskSessionId = task.deliverySessionId
    val currentTarget = task.target
    val sessionAvailable = taskSessionId != null && taskSessionId !in archived &&
        sessions.any { it.sessionId == taskSessionId }
    val scope = rememberCoroutineScope()
    var title by remember(task.id, task.updatedAt) { mutableStateOf(task.title) }
    var prompt by remember(task.id, task.updatedAt) { mutableStateOf(task.prompt) }
    var ruleKind by remember(task.id, task.updatedAt) { mutableStateOf(if (task.kind == "after") "at" else task.kind) }
    var timing by remember(task.id, task.updatedAt) { mutableStateOf(task.rrule.orEmpty()) }
    var zone by remember(task.id, task.updatedAt) { mutableStateOf(task.timeZone ?: ZoneId.systemDefault().id) }
    var weekdays by remember(task.id, task.updatedAt) { mutableStateOf(task.weekdays?.joinToString(",") ?: "1,2,3,4,5") }
    var timingChanged by remember(task.id, task.updatedAt) { mutableStateOf(false) }
    var targetKind by remember(task.id, task.updatedAt) { mutableStateOf(task.target?.kind ?: "session") }
    var targetSessionId by remember(task.id, task.updatedAt) {
        mutableStateOf(task.target?.takeIf { it.kind == "session" }?.sessionId)
    }
    var targetChanged by remember(task.id, task.updatedAt) { mutableStateOf(false) }
    var history by remember(task.id) { mutableStateOf<ScheduleHistoryResult?>(null) }
    var loading by remember(task.id) { mutableStateOf(false) }
    var message by remember(task.id) { mutableStateOf<String?>(null) }
    var confirmDelete by remember(task.id) { mutableStateOf(false) }
    var busy by remember(task.id) { mutableStateOf(false) }
    var showHistory by remember(task.id) { mutableStateOf(false) }
    val canEdit = task.enabled && task.kind in setOf("after", "at", "every", "daily", "weekly", "cron", "rrule")
    val unsupported = stringResource(R.string.schedule_newapi_unsupported)
    val unknown = stringResource(R.string.schedule_newapi_unknown)
    val pending = stringResource(R.string.schedule_newapi_pending)
    LaunchedEffect(task.id, showHistory) {
        if (showHistory && history == null) {
            val generation = manager.generation
            val api = manager.connectedApi
            if (generation == null || generation.clientId != generationId || api == null) return@LaunchedEffect
            loading = true
            val result = api.scheduleHistory(ScheduleHistoryRequest(taskSessionId.orEmpty(), task.id))
            if (manager.generation !== generation || manager.connectedApi !== api) return@LaunchedEffect
            when (result) {
                is RpcResult.Ok -> { history = result.value; message = result.value.code }
                is RpcResult.Err -> message = result.error.message
            }
            loading = false
        }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
        item { Text(task.title, style = DsType.large20, color = colors.labelPrimary) }
        item { Text("${scheduleKindLabel(task.kind)} · ${formatScheduleTime(task.scheduledAt)}", color = colors.labelPrimary) }
        item { Text(scheduleStatusLabel(task.status), color = colors.labelPrimary) }
        task.rrule?.let { rule -> item { Text(rule, color = colors.labelTertiary) } }
        item { Row {
            if (task.status in setOf("active", "paused") && task.target != null) {
                TextButton(enabled = !busy, onClick = {
                    val generation = manager.generation
                    val api = manager.connectedApi
                    if (generation == null || generation.clientId != generationId || api == null) { message = unknown; return@TextButton }
                    scope.launch {
                        busy = true
                        if (manager.generation !== generation || manager.connectedApi !== api) { busy = false; return@launch }
                        val result = api.scheduleEdit(ScheduleEditRequest(task.id, "update", ScheduleEditPatch(active = task.status != "active"), task.updatedAt))
                        if (manager.generation !== generation || manager.connectedApi !== api) return@launch
                        when (result) {
                            is RpcResult.Ok -> { message = result.value.code; if (result.value.accepted || result.value.code != null) onChanged(result.value.code) }
                            is RpcResult.Err -> message = scheduleFailure(result.error, "schedule/edit", unsupported, unknown)
                        }
                        busy = false
                    }
                }) { Text(stringResource(if (task.status == "active") R.string.schedule_newapi_pause else R.string.schedule_newapi_resume)) }
            }
            TextButton(enabled = !busy, onClick = {
                val generation = manager.generation
                val api = manager.connectedApi
                if (generation == null || generation.clientId != generationId || api == null) { message = unknown; return@TextButton }
                scope.launch {
                    busy = true
                    if (manager.generation !== generation || manager.connectedApi !== api) { busy = false; return@launch }
                    val result = api.scheduleRun(task.id)
                    if (manager.generation !== generation || manager.connectedApi !== api) return@launch
                    when (result) {
                        is RpcResult.Ok -> {
                            message = if (result.value.code == "schedule_execution_pending") pending else result.value.code
                            if (result.value.code != "schedule_execution_pending") onChanged(result.value.code)
                        }
                        is RpcResult.Err -> message = scheduleFailure(result.error, "schedule/run", unsupported, unknown)
                    }
                    busy = false
                }
            }) { Text(stringResource(R.string.schedule_newapi_run)) }
        } }
        if (currentTarget != null) {
            item { ScheduleTargetPicker(generationId, targetKind, targetSessionId,
                { targetKind = it; targetChanged = true; if (it != "session") targetSessionId = null },
                { targetSessionId = it; targetChanged = true }) }
            item { Button(enabled = !busy && targetChanged && (targetKind != currentTarget.kind || (targetKind == "session" && targetSessionId != currentTarget.sessionId)) && (targetKind != "session" || targetSessionId != null), onClick = {
                val generation = manager.generation
                val api = manager.connectedApi
                if (generation == null || generation.clientId != generationId || api == null) { message = unknown; return@Button }
                val request = ScheduleEditRequest(task.id, "update", ScheduleEditPatch(target = ScheduleTarget(targetKind, if (targetKind == "session") targetSessionId else null)), task.updatedAt)
                scope.launch {
                    busy = true
                    if (manager.generation !== generation || manager.connectedApi !== api) { busy = false; return@launch }
                    val result = api.scheduleEdit(request)
                    if (manager.generation !== generation || manager.connectedApi !== api) return@launch
                    when (result) {
                        is RpcResult.Ok -> { message = result.value.code; if (result.value.accepted || result.value.code != null) onChanged(result.value.code) }
                        is RpcResult.Err -> message = scheduleFailure(result.error, "schedule/edit", unsupported, unknown)
                    }
                    busy = false
                }
            }) { Text(stringResource(R.string.schedule_newapi_save_target)) } }
        }
        item { Text("${stringResource(R.string.schedule_task_id)}: ${task.id}", color = colors.labelTertiary) }
        item { TextButton(enabled = sessionAvailable, onClick = { taskSessionId?.let(onOpenSession) }) {
            Text(stringResource(if (sessionAvailable) R.string.schedule_open_session else R.string.schedule_session_unavailable))
        } }
        if (canEdit) {
            item { OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.schedule_name)) }, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(prompt, { prompt = it }, label = { Text(stringResource(R.string.schedule_instruction)) }, modifier = Modifier.fillMaxWidth(), minLines = 2) }
            item {
                Column {
                    Text(stringResource(R.string.schedule_timing), color = colors.labelPrimary)
                    listOf("at", "every", "daily", "weekly", "cron", "rrule").chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                            row.forEach { kind ->
                                FilterChip(selected = ruleKind == kind, onClick = {
                                    ruleKind = kind; timing = ""; timingChanged = false
                                }, label = { Text(when (kind) {
                                    "at" -> stringResource(R.string.schedule_once)
                                    "every" -> stringResource(R.string.schedule_interval)
                                    "daily" -> stringResource(R.string.schedule_daily)
                                    "weekly" -> stringResource(R.string.schedule_weekly)
                                    "rrule" -> stringResource(R.string.schedule_rrule)
                                    else -> stringResource(R.string.schedule_cron)
                                }) })
                            }
                        }
                    }
                    OutlinedTextField(timing, { timing = it; timingChanged = true },
                        label = { Text(stringResource(R.string.schedule_timing)) },
                        supportingText = { Text(stringResource(when (ruleKind) {
                            "at" -> R.string.schedule_hint_at
                            "every" -> R.string.schedule_hint_every
                            "cron" -> R.string.schedule_hint_cron
                            "rrule" -> R.string.schedule_newapi_rrule_hint
                            else -> R.string.schedule_hint_clock
                        })) }, modifier = Modifier.fillMaxWidth())
                    if (ruleKind in setOf("daily", "weekly", "cron")) OutlinedTextField(zone,
                        { zone = it; timingChanged = true }, label = { Text(stringResource(R.string.schedule_zone)) }, modifier = Modifier.fillMaxWidth())
                    if (ruleKind == "weekly") OutlinedTextField(weekdays,
                        { weekdays = it; timingChanged = true }, label = { Text(stringResource(R.string.schedule_weekdays)) }, modifier = Modifier.fillMaxWidth())
                }
            }
            item {
                Button(enabled = !busy && title.isNotBlank() && title.length <= 120 && prompt.isNotBlank()
                    && (!timingChanged || scheduleTimingChange(ruleKind, timing, zone, weekdays) != null), onClick = {
                    val generation = manager.generation
                    if (generation == null || generation.clientId != generationId || manager.connectedApi == null) { message = unknown; return@Button }
                    scope.launch {
                        busy = true
                        val change = if (timingChanged) scheduleTimingChange(ruleKind, timing, zone, weekdays) else null
                        val outcome = store.updateScheduleTask(
                            task = task,
                            title = title.trim(),
                            prompt = prompt.trim(),
                            timingChange = change,
                            legacyExpected = task.expectedRecord(),
                            expectedGenerationId = generation.clientId,
                        )
                        if (manager.generation !== generation) return@launch
                        message = outcome.message
                        if (outcome.reload) onChanged(outcome.message)
                        busy = false
                    }
                }) { Text(stringResource(R.string.schedule_save)) }
            }
        }
        item { TextButton(onClick = { showHistory = !showHistory }) { Text(stringResource(R.string.schedule_records)) } }
        if (showHistory) {
            if (loading) item { CircularProgressIndicator() }
            if (history?.records?.isEmpty() == true) item { Text(stringResource(R.string.schedule_no_records), color = colors.labelPrimary) }
            items(history?.records.orEmpty(), key = { it.messageId }) { receipt ->
                Column(Modifier.fillMaxWidth()) {
                    Text(formatScheduleTime(receipt.deliveredAt), color = colors.labelPrimary)
                    receipt.prompt?.let { Text(it, color = colors.labelTertiary) }
                }
            }
            if (history?.nextBefore != null) item {
                TextButton(onClick = {
                    val generation = manager.generation
                    val api = manager.connectedApi
                    if (generation == null || generation.clientId != generationId || api == null) return@TextButton
                    val cursor = history?.nextBefore
                    scope.launch {
                        loading = true
                        if (manager.generation !== generation || manager.connectedApi !== api) { loading = false; return@launch }
                        val next = api.scheduleHistory(ScheduleHistoryRequest(taskSessionId.orEmpty(), task.id, before = cursor))
                        if (manager.generation !== generation || manager.connectedApi !== api) return@launch
                        when (next) {
                            is RpcResult.Ok -> if (next.value.code == null) history = next.value.copy(records = history!!.records + next.value.records)
                                else message = next.value.code
                            is RpcResult.Err -> message = next.error.message
                        }
                        loading = false
                    }
                }) { Text(stringResource(R.string.schedule_more)) }
            }
            history?.retention?.let { bounds -> item { Text(stringResource(R.string.schedule_retention, bounds.records, bounds.days), color = colors.labelTertiary) } }
        }
        message?.let { value -> item { Text(value, color = colors.labelPrimary) } }
        item {
            TextButton(enabled = !busy, onClick = { confirmDelete = true }) {
                Text(stringResource(R.string.schedule_delete))
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.schedule_delete)) },
        text = { Text(stringResource(R.string.schedule_delete_warning)) },
        confirmButton = { TextButton(onClick = {
            confirmDelete = false
            val generation = manager.generation
            if (generation == null || generation.clientId != generationId || manager.connectedApi == null) { message = unknown; return@TextButton }
            scope.launch {
                busy = true
                val outcome = store.deleteScheduleTask(task, generation.clientId)
                if (manager.generation !== generation) return@launch
                message = outcome.message
                if (outcome.reload) onChanged(outcome.message)
                busy = false
            }
        }) { Text(stringResource(R.string.schedule_delete)) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.schedule_cancel)) } },
    )
}
