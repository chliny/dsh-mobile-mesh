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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.core.wire.RpcResult
import dev.dsh.mobile.mesh.core.wire.dto.ScheduleCatalogEntry
import dev.dsh.mobile.mesh.core.wire.dto.ScheduleDeleteRequest
import dev.dsh.mobile.mesh.core.wire.dto.ScheduleHistoryRequest
import dev.dsh.mobile.mesh.core.wire.dto.ScheduleHistoryResult
import dev.dsh.mobile.mesh.core.wire.dto.ScheduleUpdateRequest
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
    else -> kind
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
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf<List<ScheduleCatalogEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    val selected = records?.firstOrNull { it.id == selectedId }
    val unavailableText = stringResource(R.string.schedule_unavailable)
    val offlineText = stringResource(R.string.schedule_offline)

    LaunchedEffect(connection.host?.id, connection.phase, revision, reload) {
        records = null
        when (val response = manager.connectedApi?.scheduleCatalog()) {
            is RpcResult.Ok -> { records = response.value; error = null }
            is RpcResult.Err -> error = if (response.error.code == "capability-unavailable") unavailableText else response.error.message
            null -> error = offlineText
        }
    }
    Column(Modifier.fillMaxSize().background(colors.sidebar).safeDrawingPadding().padding(DsSpacing.medium)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            DsIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back), onClick = {
                if (selectedId != null) selectedId = null else onBack()
            })
            Text(stringResource(R.string.schedule_title), style = DsType.large20, color = colors.labelPrimary)
            DsIconButton(Icons.Filled.Refresh, stringResource(R.string.schedule_refresh), onClick = { reload++ })
        }
        if (selected == null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.schedule_catalog), color = colors.labelPrimary)
                DsIconButton(Icons.Filled.Add, stringResource(R.string.schedule_new), onClick = onNewTask)
            }
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
                        Text("${scheduleKindLabel(task.kind)} · ${if (task.status == "active") stringResource(R.string.schedule_enabled) else stringResource(R.string.schedule_inactive)} · ${formatScheduleTime(task.scheduledAt)}", color = colors.labelTertiary, maxLines = 2)
                    }
                }
            }
        } else {
            ScheduleDetail(selected, onOpenSession, onChanged = { reload++ })
        }
    }
}

@Composable
private fun ScheduleDetail(task: ScheduleCatalogEntry, onOpenSession: (String) -> Unit, onChanged: () -> Unit) {
    val colors = DsTheme.colors
    val manager = rememberConnectionManager()
    val store = rememberSessionStore()
    val sessions by store.sessions.collectAsStateWithLifecycle()
    val archived by store.archivedSessionIds.collectAsStateWithLifecycle()
    val sessionAvailable = task.sessionId !in archived && sessions.any { it.sessionId == task.sessionId }
    val scope = rememberCoroutineScope()
    var title by remember(task.id) { mutableStateOf(task.title) }
    var prompt by remember(task.id) { mutableStateOf(task.prompt) }
    var ruleKind by remember(task.id) { mutableStateOf(if (task.kind == "after") "at" else task.kind) }
    var timing by remember(task.id) { mutableStateOf("") }
    var zone by remember(task.id) { mutableStateOf(task.timeZone ?: ZoneId.systemDefault().id) }
    var weekdays by remember(task.id) { mutableStateOf(task.weekdays?.joinToString(",") ?: "1,2,3,4,5") }
    var timingChanged by remember(task.id) { mutableStateOf(false) }
    var history by remember(task.id) { mutableStateOf<ScheduleHistoryResult?>(null) }
    var loading by remember(task.id) { mutableStateOf(false) }
    var message by remember(task.id) { mutableStateOf<String?>(null) }
    var confirmDelete by remember(task.id) { mutableStateOf(false) }
    var busy by remember(task.id) { mutableStateOf(false) }
    var showHistory by remember(task.id) { mutableStateOf(false) }
    val canEdit = task.status == "active" && task.kind in setOf("after", "at", "every", "daily", "weekly", "cron")
    LaunchedEffect(task.id, showHistory) {
        if (showHistory && history == null) {
            loading = true
            when (val result = manager.connectedApi?.scheduleHistory(ScheduleHistoryRequest(task.sessionId, task.id))) {
                is RpcResult.Ok -> { history = result.value; message = result.value.code }
                is RpcResult.Err -> message = result.error.message
                null -> message = null
            }
            loading = false
        }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
        item { Text(task.title, style = DsType.large20, color = colors.labelPrimary) }
        item { Text("${scheduleKindLabel(task.kind)} · ${formatScheduleTime(task.scheduledAt)}", color = colors.labelPrimary) }
        item { Text(if (task.status == "active") stringResource(R.string.schedule_enabled) else stringResource(R.string.schedule_inactive), color = colors.labelPrimary) }
        item { Text("${stringResource(R.string.schedule_task_id)}: ${task.id}", color = colors.labelTertiary) }
        item { TextButton(enabled = sessionAvailable, onClick = { onOpenSession(task.sessionId) }) {
            Text(stringResource(if (sessionAvailable) R.string.schedule_open_session else R.string.schedule_session_unavailable))
        } }
        if (canEdit) {
            item { OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.schedule_name)) }, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(prompt, { prompt = it }, label = { Text(stringResource(R.string.schedule_instruction)) }, modifier = Modifier.fillMaxWidth(), minLines = 2) }
            item {
                Column {
                    Text(stringResource(R.string.schedule_timing), color = colors.labelPrimary)
                    listOf("at", "every", "daily", "weekly", "cron").chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                            row.forEach { kind ->
                                FilterChip(selected = ruleKind == kind, onClick = {
                                    ruleKind = kind; timing = ""; timingChanged = false
                                }, label = { Text(when (kind) {
                                    "at" -> stringResource(R.string.schedule_once)
                                    "every" -> stringResource(R.string.schedule_interval)
                                    "daily" -> stringResource(R.string.schedule_daily)
                                    "weekly" -> stringResource(R.string.schedule_weekly)
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
                    scope.launch {
                        busy = true
                        val change = if (timingChanged) scheduleTimingChange(ruleKind, timing, zone, weekdays) else null
                        when (val result = manager.connectedApi?.scheduleUpdate(ScheduleUpdateRequest(
                            task.sessionId, task.id, task.expectedRecord(), title.trim(), prompt.trim(), change,
                        ))) {
                            is RpcResult.Ok -> {
                                message = result.value.code ?: if (result.value.record != null) null else "unknown"
                                if (result.value.record != null || result.value.code in setOf("schedule_conflict", "schedule_ended", "schedule_not_found")) onChanged()
                            }
                            is RpcResult.Err -> message = result.error.message
                            null -> message = null
                        }
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
                    scope.launch {
                        loading = true
                        val next = manager.connectedApi?.scheduleHistory(ScheduleHistoryRequest(task.sessionId, task.id, before = history?.nextBefore))
                        when (next) {
                            is RpcResult.Ok -> if (next.value.code == null) history = next.value.copy(records = history!!.records + next.value.records)
                                else message = next.value.code
                            is RpcResult.Err -> message = next.error.message
                            null -> Unit
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
            scope.launch {
                busy = true
                when (val result = manager.connectedApi?.scheduleDelete(ScheduleDeleteRequest(task.sessionId, task.id))) {
                    is RpcResult.Ok -> if (result.value.deleted || result.value.code == "schedule_not_found") onChanged() else message = result.value.code
                    is RpcResult.Err -> message = result.error.message
                    null -> Unit
                }
                busy = false
            }
        }) { Text(stringResource(R.string.schedule_delete)) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.schedule_cancel)) } },
    )
}
