package dev.dsh.mobile.mesh.ui.screens.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.connection.missingUnaryRemoteEndpoint
import dev.dsh.mobile.mesh.core.wire.RpcError
import dev.dsh.mobile.mesh.core.wire.RpcResult
import dev.dsh.mobile.mesh.core.wire.TransportFailure
import dev.dsh.mobile.mesh.core.wire.TransportFailures
import dev.dsh.mobile.mesh.core.wire.dto.ScheduleCatalogEntry
import dev.dsh.mobile.mesh.core.wire.dto.ScheduleCreateRequest
import dev.dsh.mobile.mesh.core.wire.dto.ScheduleEditPatch
import dev.dsh.mobile.mesh.core.wire.dto.ScheduleEditRequest
import dev.dsh.mobile.mesh.core.wire.dto.ScheduleHistoryRequest
import dev.dsh.mobile.mesh.core.wire.dto.ScheduleHistoryResult
import dev.dsh.mobile.mesh.core.wire.dto.ScheduleTarget
import dev.dsh.mobile.mesh.ui.rememberConnectionManager
import dev.dsh.mobile.mesh.ui.rememberSessionStore
import dev.dsh.mobile.mesh.ui.theme.DsSpacing
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Rule kinds, in the order the picker lists them. */
private val scheduleRuleKinds = listOf("at", "every", "daily", "weekly", "cron", "rrule")

/** Delivery destinations, in the order the picker lists them. */
private val scheduleTargetKinds = listOf("session", "new_once", "new")

/** Rule kinds whose value is a Host-local clock reading next to an explicit zone. */
private val scheduleZoneKinds = setOf("daily", "weekly", "cron")

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

/** The page the system Back gesture steps out of, before the screen itself is left. */
internal enum class ScheduleBackStep { CancelCreate, CloseDetail, LeaveScreen }

/**
 * Back unwinds one page at a time: an open create form or task detail returns to the catalog, and
 * only the catalog hands the gesture back to the session list it was opened from.
 *
 * The catalog used to register no Back handler at all, so the gesture fell through every composed
 * screen to the Activity and exited the app from the task list.
 */
internal fun scheduleBackStep(creating: Boolean, detailOpen: Boolean): ScheduleBackStep = when {
    creating -> ScheduleBackStep.CancelCreate
    detailOpen -> ScheduleBackStep.CloseDetail
    else -> ScheduleBackStep.LeaveScreen
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

@Composable
private fun scheduleTargetLabel(kind: String): String = stringResource(when (kind) {
    "session" -> R.string.schedule_newapi_existing_session
    "new_once" -> R.string.schedule_newapi_new_once
    else -> R.string.schedule_newapi_new
})

/** The value hint for one rule kind; the Host validates the actual input. */
private fun scheduleTimingHint(kind: String): Int = when (kind) {
    "rrule" -> R.string.schedule_newapi_rrule_hint
    "at" -> R.string.schedule_hint_at
    "every" -> R.string.schedule_hint_every
    "cron" -> R.string.schedule_hint_cron
    else -> R.string.schedule_hint_clock
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

/**
 * One dropdown field: the current value in a read-only outline that opens a Material menu.
 *
 * Every fixed set of choices on this page is a dropdown rather than a row of chips, so the same
 * option on a phone never wraps into a wall of buttons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleSelectField(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (option, optionLabel) ->
                DropdownMenuItem(
                    text = { Text(optionLabel) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun ScheduleRuleKindField(kind: String, onSelect: (String) -> Unit) {
    val options = scheduleRuleKinds.map { option -> option to scheduleKindLabel(option) }
    ScheduleSelectField(
        label = stringResource(R.string.schedule_rule_kind),
        value = scheduleKindLabel(kind),
        options = options,
        onSelect = onSelect,
    )
}

@Composable
private fun ScheduleTargetField(kind: String, onSelect: (String) -> Unit) {
    val options = scheduleTargetKinds.map { option -> option to scheduleTargetLabel(option) }
    ScheduleSelectField(
        label = stringResource(R.string.schedule_newapi_target),
        value = scheduleTargetLabel(kind),
        options = options,
        onSelect = onSelect,
    )
}

/**
 * Weekday names from the platform CLDR data for the app's active locale, index 0 = Monday.
 *
 * The Host takes weekday numbers, but nobody reads "1,2,3,4,5" as Monday to Friday on a phone in
 * any of the app's eleven locales.
 */
@Composable
private fun rememberScheduleWeekdayNames(style: TextStyle): List<String> {
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.getDefault()
    return remember(locale, style) { (1..7).map { DayOfWeek.of(it).getDisplayName(style, locale) } }
}

/** Multi-select weekday picker; the menu stays open while days are toggled. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleWeekdayField(selected: Set<Int>, onToggle: (Int) -> Unit) {
    val names = rememberScheduleWeekdayNames(TextStyle.FULL)
    val summaryNames = rememberScheduleWeekdayNames(TextStyle.SHORT)
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.sorted().joinToString(", ") { day -> summaryNames[day - 1] },
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.schedule_weekdays)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            (1..7).forEach { day ->
                DropdownMenuItem(
                    text = { Text(names[day - 1]) },
                    leadingIcon = { Checkbox(checked = day in selected, onCheckedChange = null) },
                    onClick = { onToggle(day) },
                )
            }
        }
    }
}

/** Searchable session picker; rows come from the Host's session catalog only. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleSessionField(generationId: String?, sessionId: String?, onSessionChange: (String) -> Unit) {
    val store = rememberSessionStore()
    val sessions by store.sessions.collectAsStateWithLifecycle()
    val baseline by store.sessionsBaselineGenerationId.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val availableSessions = if (generationId != null && baseline == generationId) sessions else emptyList()
    val selectedSession = availableSessions.firstOrNull { it.sessionId == sessionId }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = if (expanded) query else selectedSession?.let { it.title?.takeIf(String::isNotBlank) ?: it.sessionId }.orEmpty(),
            onValueChange = { query = it; expanded = true },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryEditable, enabled = true),
            label = { Text(stringResource(R.string.schedule_newapi_select_session)) },
            placeholder = { Text(stringResource(R.string.chatlist_search_hint)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            availableSessions.filter { row ->
                val label = row.title?.takeIf(String::isNotBlank) ?: row.sessionId
                query.isBlank() || label.contains(query, ignoreCase = true) || row.sessionId.contains(query, ignoreCase = true)
            }.forEach { row ->
                DropdownMenuItem(
                    text = { Text(row.title?.takeIf(String::isNotBlank) ?: row.sessionId) },
                    onClick = { onSessionChange(row.sessionId); query = ""; expanded = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduleScreen(onBack: () -> Unit, onNewTask: () -> Unit, onOpenSession: (String) -> Unit) {
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
    fun goBack() {
        when (scheduleBackStep(creating = creating, detailOpen = selectedId != null)) {
            ScheduleBackStep.CancelCreate -> creating = false
            ScheduleBackStep.CloseDetail -> selectedId = null
            ScheduleBackStep.LeaveScreen -> onBack()
        }
    }
    // Always registered: Back is this page's business even at the catalog root, where it returns
    // to the session list instead of letting the gesture reach the Activity and quit the app.
    BackHandler { goBack() }
    val title = when {
        creating -> stringResource(R.string.schedule_newapi_create)
        selected != null -> selected.title
        else -> stringResource(R.string.schedule_title)
    }
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
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { goBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = { reload++ }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.schedule_refresh))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        },
        floatingActionButton = {
            if (!creating && selected == null) FloatingActionButton(
                onClick = { creating = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.schedule_new))
            }
        },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            notice?.let { value ->
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = DsSpacing.comfortable, vertical = DsSpacing.small),
                )
            }
            if (creating) {
                ScheduleCreate(generationId, onLegacyNew = onNewTask, onCreated = { creating = false; reload++ })
            } else if (selected == null) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = DsSpacing.comfortable,
                        end = DsSpacing.comfortable,
                        top = DsSpacing.small,
                        // Room for the floating action button to sit below the last row.
                        bottom = 96.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
                ) {
                    when {
                        records == null && error == null -> item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = DsSpacing.xxlarge),
                                contentAlignment = Alignment.Center,
                            ) { CircularProgressIndicator() }
                        }
                        error != null -> item {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = DsSpacing.large),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(DsSpacing.medium),
                            ) {
                                Text(
                                    error.orEmpty(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    textAlign = TextAlign.Center,
                                )
                                Button(onClick = { reload++ }) { Text(stringResource(R.string.schedule_retry)) }
                            }
                        }
                        records?.isEmpty() == true -> item {
                            Text(
                                stringResource(R.string.schedule_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(vertical = DsSpacing.large),
                            )
                        }
                    }
                    items(records.orEmpty(), key = { it.id }) { task ->
                        OutlinedCard(
                            onClick = { selectedId = task.id },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            ListItem(
                                headlineContent = {
                                    Text(task.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                supportingContent = {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            "${scheduleKindLabel(task.kind)} · ${scheduleStatusLabel(task.status)}",
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(formatScheduleTime(task.scheduledAt))
                                    }
                                },
                                trailingContent = {
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                        }
                    }
                    if (records != null) item {
                        TextButton(
                            enabled = !clearBusy,
                            onClick = { confirmClear = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.schedule_newapi_clear)) }
                    }
                }
            } else {
                ScheduleDetail(selected, generationId, onOpenSession, onChanged = { result -> notice = result; reload++ })
            }
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
private fun ScheduleCreate(generationId: String?, onLegacyNew: () -> Unit, onCreated: () -> Unit) {
    val manager = rememberConnectionManager()
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("at") }
    var timing by remember { mutableStateOf("") }
    var zone by remember { mutableStateOf(ZoneId.systemDefault().id) }
    var weekdays by remember { mutableStateOf(setOf(1, 2, 3, 4, 5)) }
    var target by remember { mutableStateOf("new_once") }
    var targetSessionId by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var limit by remember { mutableStateOf<Long?>(null) }
    val unsupported = stringResource(R.string.schedule_newapi_unsupported)
    val unknown = stringResource(R.string.schedule_newapi_unknown)
    val offline = stringResource(R.string.schedule_offline)
    val weekdayParam = weekdays.sorted().joinToString(",")
    val rule = scheduleTimingChange(kind, timing, zone, weekdayParam)
    val canSubmit = !busy && title.isNotBlank() && title.length <= 120 && prompt.isNotBlank() &&
        (target != "session" || targetSessionId != null) && rule != null
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
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(DsSpacing.comfortable),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.medium),
    ) {
        item {
            OutlinedTextField(
                title, { title = it },
                label = { Text(stringResource(R.string.schedule_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OutlinedTextField(
                prompt, { prompt = it },
                label = { Text(stringResource(R.string.schedule_instruction)) },
                // A stored instruction can be hundreds of lines; keep the field a field and let it
                // scroll instead of stretching one list item across the whole page.
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            ScheduleRuleKindField(kind) { picked ->
                kind = picked
                timing = ""
            }
        }
        item {
            OutlinedTextField(
                timing, { timing = it },
                label = { Text(stringResource(R.string.schedule_timing)) },
                supportingText = { Text(stringResource(scheduleTimingHint(kind))) },
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (kind in scheduleZoneKinds) item {
            OutlinedTextField(
                zone, { zone = it },
                label = { Text(stringResource(R.string.schedule_zone)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (kind == "weekly") item {
            ScheduleWeekdayField(weekdays) { day ->
                weekdays = if (day in weekdays) weekdays - day else weekdays + day
            }
        }
        item { ScheduleTargetField(target) { target = it } }
        if (target == "session") item {
            ScheduleSessionField(generationId, targetSessionId) { targetSessionId = it }
        }
        if (target == "new") limit?.let { value -> item {
            Text(
                stringResource(R.string.schedule_newapi_delivery_limit, value),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } }
        message?.let { value -> item {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        } }
        item {
            Button(
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val request = ScheduleCreateRequest(title.trim(), prompt.trim(), rule ?: return@Button, ScheduleTarget(target, if (target == "session") targetSessionId else null))
                    val generation = manager.generation
                    val api = manager.connectedApi
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
                },
            ) { Text(stringResource(R.string.schedule_newapi_create)) }
        }
        item {
            TextButton(onClick = onLegacyNew, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.schedule_newapi_legacy_new))
            }
        }
    }
}

@Composable
private fun ScheduleDetail(task: ScheduleCatalogEntry, generationId: String?, onOpenSession: (String) -> Unit, onChanged: (String?) -> Unit) {
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
    var weekdays by remember(task.id, task.updatedAt) { mutableStateOf(task.weekdays?.toSet() ?: setOf(1, 2, 3, 4, 5)) }
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
    val weekdayParam = weekdays.sorted().joinToString(",")
    val canSave = !busy && title.isNotBlank() && title.length <= 120 && prompt.isNotBlank() &&
        (!timingChanged || scheduleTimingChange(ruleKind, timing, zone, weekdayParam) != null)
    val canSaveTarget = !busy && targetChanged &&
        (targetKind != currentTarget?.kind || (targetKind == "session" && targetSessionId != currentTarget?.sessionId)) &&
        (targetKind != "session" || targetSessionId != null)
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
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(DsSpacing.comfortable),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.medium),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
                Text(task.title, style = MaterialTheme.typography.headlineMedium)
                Text(
                    "${scheduleKindLabel(task.kind)} · ${scheduleStatusLabel(task.status)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    formatScheduleTime(task.scheduledAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                task.rrule?.let { rule ->
                    Text(rule, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                if (task.status in setOf("active", "paused") && task.target != null) {
                    OutlinedButton(enabled = !busy, onClick = {
                        val generation = manager.generation
                        val api = manager.connectedApi
                        if (generation == null || generation.clientId != generationId || api == null) { message = unknown; return@OutlinedButton }
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
                OutlinedButton(enabled = !busy, onClick = {
                    val generation = manager.generation
                    val api = manager.connectedApi
                    if (generation == null || generation.clientId != generationId || api == null) { message = unknown; return@OutlinedButton }
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
            }
        }
        item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
        if (currentTarget != null) {
            item { ScheduleTargetField(targetKind) { picked ->
                targetKind = picked
                targetChanged = true
                if (picked != "session") targetSessionId = null
            } }
            if (targetKind == "session") item {
                ScheduleSessionField(generationId, targetSessionId) {
                    targetSessionId = it
                    targetChanged = true
                }
            }
            item {
                Button(enabled = canSaveTarget, onClick = {
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
                }) { Text(stringResource(R.string.schedule_newapi_save_target)) }
            }
        }
        item {
            Text(
                "${stringResource(R.string.schedule_task_id)}: ${task.id}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            OutlinedButton(enabled = sessionAvailable, onClick = { taskSessionId?.let(onOpenSession) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (sessionAvailable) R.string.schedule_open_session else R.string.schedule_session_unavailable))
            }
        }
        if (canEdit) {
            item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
            item {
                OutlinedTextField(
                    title, { title = it },
                    label = { Text(stringResource(R.string.schedule_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    prompt, { prompt = it },
                    label = { Text(stringResource(R.string.schedule_instruction)) },
                    minLines = 2,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item { ScheduleRuleKindField(ruleKind) { picked ->
                ruleKind = picked
                timing = ""
                timingChanged = false
            } }
            item {
                OutlinedTextField(
                    timing, { timing = it; timingChanged = true },
                    label = { Text(stringResource(R.string.schedule_timing)) },
                    supportingText = { Text(stringResource(scheduleTimingHint(ruleKind))) },
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (ruleKind in scheduleZoneKinds) item {
                OutlinedTextField(
                    zone, { zone = it; timingChanged = true },
                    label = { Text(stringResource(R.string.schedule_zone)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (ruleKind == "weekly") item {
                ScheduleWeekdayField(weekdays) { day ->
                    weekdays = if (day in weekdays) weekdays - day else weekdays + day
                    timingChanged = true
                }
            }
            item {
                Button(
                    enabled = canSave,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val generation = manager.generation
                        if (generation == null || generation.clientId != generationId || manager.connectedApi == null) { message = unknown; return@Button }
                        scope.launch {
                            busy = true
                            val change = if (timingChanged) scheduleTimingChange(ruleKind, timing, zone, weekdayParam) else null
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
                    },
                ) { Text(stringResource(R.string.schedule_save)) }
            }
        }
        item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
        item {
            TextButton(onClick = { showHistory = !showHistory }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.schedule_records))
            }
        }
        if (showHistory) {
            if (loading) item {
                Box(Modifier.fillMaxWidth().padding(vertical = DsSpacing.medium), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            if (history?.records?.isEmpty() == true) item {
                Text(
                    stringResource(R.string.schedule_no_records),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(history?.records.orEmpty(), key = { it.messageId }) { receipt ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(formatScheduleTime(receipt.deliveredAt), style = MaterialTheme.typography.bodyMedium)
                    receipt.prompt?.let { text ->
                        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
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
            history?.retention?.let { bounds -> item {
                Text(
                    stringResource(R.string.schedule_retention, bounds.records, bounds.days),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } }
        }
        message?.let { value -> item {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        item {
            TextButton(
                enabled = !busy,
                onClick = { confirmDelete = true },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
            ) {
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
