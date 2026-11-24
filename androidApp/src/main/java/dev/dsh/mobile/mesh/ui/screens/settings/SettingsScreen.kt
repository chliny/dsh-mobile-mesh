package dev.dsh.mobile.mesh.ui.screens.settings

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import dev.dsh.mobile.mesh.BuildConfig
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.connection.AppSettings
import dev.dsh.mobile.mesh.connection.ConnectionPhase
import dev.dsh.mobile.mesh.connection.ConnectionUiState
import dev.dsh.mobile.mesh.connection.ConnectionImportConflict
import dev.dsh.mobile.mesh.core.DshCore
import dev.dsh.mobile.mesh.core.wire.dto.PluginFiberPhase
import dev.dsh.mobile.mesh.core.wire.dto.PluginInventoryEntry
import dev.dsh.mobile.mesh.core.wire.dto.PluginInventorySnapshot
import dev.dsh.mobile.mesh.ui.components.DisclosureRow
import dev.dsh.mobile.mesh.ui.components.AppBottomSheet
import dev.dsh.mobile.mesh.ui.components.AppToastHost
import dev.dsh.mobile.mesh.ui.components.SectionHeader
import dev.dsh.mobile.mesh.ui.components.StateDot
import dev.dsh.mobile.mesh.ui.components.StateDotState
import dev.dsh.mobile.mesh.ui.components.ToggleRow
import dev.dsh.mobile.mesh.ui.components.rememberAppToast
import dev.dsh.mobile.mesh.ui.rememberSessionStore
import dev.dsh.mobile.mesh.ui.theme.statusColors
import dev.dsh.mobile.mesh.ui.theme.DsSpacing
import java.util.Calendar
import java.util.Date
import java.text.DateFormat as JavaDateFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * App settings, grouped into cards.
 *
 * Only the top group is genuinely the app's own; connection, harness facts and data are all about
 * the *link* to a harness. Keeping the read-only notice scoped to the harness group matters —
 * blanket-labelling the whole screen read-only, as it used to, tells users their own preferences
 * cannot be changed when they plainly can.
 */
@Composable
fun SettingsScreen(
    onClose: () -> Unit,
    onOpenConnections: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.state.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val store = rememberSessionStore()
    val plugins by store.plugins.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val toast = rememberAppToast()
    val scope = rememberCoroutineScope()
    var showDisconnectDialog by remember { mutableStateOf(false) }
    var showLogsRangeDialog by remember { mutableStateOf(false) }
    var persistLogs by remember { mutableStateOf(AppLogCapture.isEnabled(context)) }
    // Null means the operator has not selected a custom range. Recompute the default whenever
    // the dialog opens, but preserve an explicitly selected range even when the dialog is closed.
    var customLogsRange by remember { mutableStateOf<AppLogTimeRange?>(null) }
    var logsExportRange by remember { mutableStateOf(AppLogTimeRange(0L, 0L)) }
    var pendingImport by remember { mutableStateOf<String?>(null) }
    var importConflicts by remember { mutableStateOf<List<ConnectionImportConflict>>(emptyList()) }
    var selectedReplaceIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var importError by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val payload = viewModel.exportConnections()
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(payload) }
                    ?: error("Could not open export file")
            }.onSuccess { toast.second(context.getString(R.string.settings_connections_exported)) }
                .onFailure { toast.second(context.getString(R.string.settings_connections_transfer_failed)) }
        }
    }
    val logsExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val logs = withContext(Dispatchers.IO) {
                    readAppLogs(context, logsExportRange)
                }
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(logs) }
                    ?: error("Could not open export file")
            }.onSuccess { toast.second(context.getString(R.string.settings_logs_exported)) }
                .onFailure { toast.second(context.getString(R.string.settings_logs_export_failed)) }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val payload = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("Could not read import file")
                payload
            }.onSuccess { payload ->
                pendingImport = payload
                importError = null
                scope.launch {
                    runCatching { viewModel.previewConnectionImport(payload) }
                        .onSuccess { conflicts ->
                            importConflicts = conflicts
                            selectedReplaceIds = conflicts.map { it.existing.id }.toSet()
                            if (conflicts.isEmpty()) {
                                runCatching { viewModel.importConnections(payload, emptySet()) }
                                    .onSuccess {
                                        pendingImport = null
                                        toast.second(context.getString(R.string.settings_connections_imported))
                                    }
                                    .onFailure {
                                        pendingImport = null
                                        toast.second(context.getString(R.string.settings_connections_transfer_failed))
                                    }
                            }
                        }
                        .onFailure {
                            pendingImport = null
                            toast.second(context.getString(R.string.settings_connections_transfer_failed))
                        }
                }
            }.onFailure { toast.second(context.getString(R.string.settings_connections_transfer_failed)) }
        }
    }
    var accountRefresh by remember { mutableIntStateOf(0) }
    var pluginsOpen by remember { mutableStateOf(false) }
    var pluginManagerOpen by remember { mutableStateOf(false) }
    var pluginDirectory by remember { mutableStateOf<PluginDirectory?>(null) }
    var hostSection by remember { mutableStateOf<HostSettingsSection?>(null) }
    BackHandler {
        when {
            hostSection != null -> hostSection = null
            pluginManagerOpen -> {
                pluginManagerOpen = false
                pluginDirectory = null
            }
            else -> onClose()
        }
    }

    // Fetched on open rather than kept live: the inventory only changes when the harness is
    // restarted with a different composition, and nothing pushes that over the wire.
    LaunchedEffect(Unit) { store.refreshPlugins() }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = DsSpacing.comfortable, vertical = DsSpacing.medium),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.comfortable),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                    Text(
                        stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                SettingsCard(stringResource(R.string.settings_general)) {
                    LanguageRow(settings) { tag -> viewModel.set { it.copy(localeOverride = tag) } }
                    AppearanceRow(settings) { mode -> viewModel.set { it.copy(themePreference = mode) } }
                }

                SettingsCard(stringResource(R.string.settings_connection)) {
                    ConnectionSection(connectionState, onDisconnect = { showDisconnectDialog = true })
                    OutlinedButton(
                        onClick = onOpenConnections,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.settings_open_connection)) }
                    OutlinedButton(
                        onClick = { exportLauncher.launch("dsh-connections.json") },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.settings_connections_export)) }
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.settings_connections_import)) }
                    ToggleRow(
                        stringResource(R.string.connect_auto_last),
                        settings.autoConnectLast,
                    ) { viewModel.set { it.copy(autoConnectLast = !it.autoConnectLast) } }
                    ToggleRow(
                        stringResource(R.string.connect_auto_loopback),
                        settings.autoConnectLoopback,
                    ) { viewModel.set { it.copy(autoConnectLoopback = !it.autoConnectLoopback) } }
                    ToggleRow(
                        stringResource(R.string.connect_auto_lan),
                        settings.autoConnectLan,
                    ) { viewModel.set { it.copy(autoConnectLan = !it.autoConnectLan) } }
                }

                SettingsCard(stringResource(R.string.settings_notifications)) {
                    ToggleRow(
                        stringResource(R.string.settings_notifications_turn),
                        settings.notifyTurnComplete,
                        stringResource(R.string.settings_notifications_turn_hint),
                    ) { viewModel.set { it.copy(notifyTurnComplete = !it.notifyTurnComplete) } }
                    // A child turn is a turn, so this is a refinement of the row above rather than
                    // a sibling preference: it only appears once turn notifications are on, and it
                    // stays off until asked for because a delegation fan-out can be dozens of
                    // completions the user is not waiting on.
                    if (settings.notifyTurnComplete) {
                        Box(Modifier.padding(start = DsSpacing.large)) {
                            ToggleRow(
                                stringResource(R.string.settings_notifications_subagent),
                                settings.notifySubagentComplete,
                                stringResource(R.string.settings_notifications_subagent_hint),
                            ) { viewModel.set { it.copy(notifySubagentComplete = !it.notifySubagentComplete) } }
                        }
                    }
                    ToggleRow(
                        stringResource(R.string.settings_notifications_goal),
                        settings.notifyGoal,
                        stringResource(R.string.settings_notifications_goal_hint),
                    ) { viewModel.set { it.copy(notifyGoal = !it.notifyGoal) } }
                    ToggleRow(
                        stringResource(R.string.settings_notifications_action),
                        settings.notifyNeedsAction,
                        stringResource(R.string.settings_notifications_action_hint),
                    ) { viewModel.set { it.copy(notifyNeedsAction = !it.notifyNeedsAction) } }
                    ToggleRow(
                        stringResource(R.string.settings_background),
                        settings.keepConnectedInBackground,
                        stringResource(R.string.settings_background_hint),
                    ) { viewModel.set { it.copy(keepConnectedInBackground = !it.keepConnectedInBackground) } }
                }

                if (connectionState.phase == ConnectionPhase.CONNECTED) {
                    SettingsCard(stringResource(R.string.settings_harness)) {
                        HostSettingsSection.entries.forEach { section ->
                            OutlinedButton(
                                onClick = { hostSection = section },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(section.title)) }
                        }
                    }
                }

                if (connectionState.phase == ConnectionPhase.CONNECTED && hostSection == null &&
                    !pluginManagerOpen && !pluginsOpen) {
                    val accountConnection by viewModel.accountConnection.collectAsStateWithLifecycle()
                    accountConnection?.let { connected ->
                        SettingsCard(
                            stringResource(R.string.account_host_title),
                            trailing = {
                                IconButton(onClick = { accountRefresh++ }) {
                                    Icon(
                                        Icons.Filled.Refresh,
                                        contentDescription = stringResource(R.string.account_host_refresh),
                                    )
                                }
                            },
                        ) {
                            key(connected.generation) {
                                HostAccountSettings(connected.api, refreshSignal = accountRefresh)
                            }
                        }
                    }
                }

                // Plugin management is its own page; the harness facts above remain read-only.
                plugins?.let { inventory ->
                    PluginsCard(
                        inventory,
                        onOpenList = { if (inventory.managementAvailable) pluginManagerOpen = true else pluginsOpen = true },
                        onOpenDirectory = { pluginDirectory = it; pluginManagerOpen = true },
                    )
                }

                SettingsCard(stringResource(R.string.settings_debug)) {
                    ToggleRow(
                        stringResource(R.string.settings_logs_persist),
                        persistLogs,
                        stringResource(R.string.settings_logs_persist_hint),
                    ) {
                        val enabled = !persistLogs
                        persistLogs = enabled
                        AppLogCapture.setEnabled(context, enabled)
                    }
                    OutlinedButton(
                        onClick = {
                            // The dialog displays seconds, so use second-aligned boundaries for
                            // the default range as well as for dates chosen by the operator.
                            val now = System.currentTimeMillis() / 1_000L * 1_000L
                            logsExportRange = customLogsRange ?: AppLogTimeRange(now - DEFAULT_LOG_WINDOW_MS, now)
                            showLogsRangeDialog = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.settings_logs_export)) }
                }

                SettingsCard(stringResource(R.string.settings_about)) {
                    // Beside the version, because that is what it is about — and off-switchable,
                    // since it is the one request this app makes to anything but the harness.
                    ToggleRow(
                        stringResource(R.string.settings_update_check),
                        settings.updateCheckEnabled,
                        stringResource(R.string.settings_update_check_hint),
                    ) { viewModel.set { it.copy(updateCheckEnabled = !it.updateCheckEnabled) } }
                    Text(
                        stringResource(
                            R.string.settings_about_version,
                            BuildConfig.VERSION_NAME,
                            DshCore.PROTOCOL_BASELINE,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.settings_api_compatibility),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(DsSpacing.xlarge))
            }
            AppToastHost(toast, modifier = Modifier.fillMaxWidth())
        }
    }

    plugins?.takeIf { pluginsOpen }?.let { inventory ->
        PluginsSheet(inventory = inventory, onDismiss = { pluginsOpen = false })
    }
    if (pluginManagerOpen) PluginManagerScreen(
        onClose = {
            pluginManagerOpen = false
            pluginDirectory = null
        },
        initialDirectory = pluginDirectory,
    )
    when (hostSection) {
        HostSettingsSection.Agent -> AgentSettings(onClose = { hostSection = null })
        HostSettingsSection.Models -> HostModelsSettings(onClose = { hostSection = null })
        null -> Unit
    }

    pendingImport?.let { payload ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text(stringResource(R.string.settings_connections_import_conflicts_title)) },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        stringResource(R.string.settings_connections_import_conflicts_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    importConflicts.forEach { conflict ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = conflict.existing.id in selectedReplaceIds,
                                onCheckedChange = { checked ->
                                    selectedReplaceIds = if (checked) selectedReplaceIds + conflict.existing.id
                                    else selectedReplaceIds - conflict.existing.id
                                },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(conflict.imported.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                val transportLabel = when (conflict.imported.meshTransport?.storedValue) {
                                    "zerotier" -> stringResource(R.string.connect_transport_zerotier)
                                    "tailscale" -> stringResource(R.string.connect_transport_tailscale)
                                    else -> stringResource(R.string.settings_connections_transport_direct)
                                }
                                val sshLabel = stringResource(
                                    if (conflict.imported.sshEnabled) {
                                        R.string.settings_connections_ssh_enabled
                                    } else {
                                        R.string.settings_connections_ssh_disabled
                                    },
                                )
                                Text(
                                    "${conflict.imported.displayAddress} · $transportLabel · $sshLabel",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    importError?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = statusColors.warningText) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { viewModel.importConnections(payload, selectedReplaceIds) }
                            .onSuccess {
                                pendingImport = null
                                toast.second(context.getString(R.string.settings_connections_imported))
                            }
                            .onFailure { importError = context.getString(R.string.settings_connections_transfer_failed) }
                    }
                }) { Text(stringResource(R.string.settings_connections_import_selected)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        scope.launch {
                            runCatching { viewModel.importConnections(payload, emptySet()) }
                                .onSuccess {
                                    pendingImport = null
                                    toast.second(context.getString(R.string.settings_connections_imported))
                                }
                                .onFailure { importError = context.getString(R.string.settings_connections_transfer_failed) }
                        }
                    }) { Text(stringResource(R.string.settings_connections_import_none)) }
                    TextButton(onClick = { pendingImport = null }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            },
        )
    }

    if (showDisconnectDialog) {
        AlertDialog(
            onDismissRequest = { showDisconnectDialog = false },
            title = { Text(stringResource(R.string.settings_connection_disconnect_confirm)) },
            text = { Text(stringResource(R.string.settings_connection_disconnect_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.disconnect()
                        showDisconnectDialog = false
                        onClose()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.settings_connection_disconnect)) }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
    if (showLogsRangeDialog) {
        LogsExportRangeDialog(
            initialRange = logsExportRange,
            onDismiss = { showLogsRangeDialog = false },
            onCustomRangeChanged = { customLogsRange = it },
            onExport = { range ->
                logsExportRange = range
                showLogsRangeDialog = false
                logsExportLauncher.launch("dsh-mobile-mesh-logs.txt")
            },
        )
    }
}

private enum class HostSettingsSection(@StringRes val title: Int) {
    Agent(R.string.host_agent_settings_title),
    Models(R.string.models_title),
}

@Composable
private fun AgentSettings(onClose: () -> Unit) {
    HostSettingsPage(stringResource(R.string.host_agent_settings_title), onClose) {
        SettingsBlock {
            HostPreferencesContentScreen()
            HostDefaultsContentScreen()
        }
    }
}

@Composable
private fun HostPreferencesContentScreen(viewModel: HostPreferencesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.refresh() }
    HostPreferencesContent(state, viewModel)
}

@Composable
private fun HostDefaultsContentScreen(viewModel: HostDefaultsSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.refresh() }
    HostDefaultsContent(state, viewModel)
}

@Composable
private fun LogsExportRangeDialog(
    initialRange: AppLogTimeRange,
    onDismiss: () -> Unit,
    onCustomRangeChanged: (AppLogTimeRange) -> Unit,
    onExport: (AppLogTimeRange) -> Unit,
) {
    val context = LocalContext.current
    var startEpochMs by remember(initialRange) { mutableStateOf(initialRange.startEpochMs) }
    var endEpochMs by remember(initialRange) { mutableStateOf(initialRange.endEpochMs) }
    var rangeEdited by remember(initialRange) { mutableStateOf(false) }
    val range = AppLogTimeRange(startEpochMs, endEpochMs)
    val dateTimeFormat = remember { JavaDateFormat.getDateTimeInstance(JavaDateFormat.MEDIUM, JavaDateFormat.MEDIUM) }
    val valid = range.isValid()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_logs_range_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall)) {
                TextButton(onClick = {
                    showDateTimePicker(context, startEpochMs) {
                        startEpochMs = it
                        rangeEdited = true
                    }
                }) {
                    Text("${stringResource(R.string.settings_logs_range_start)}: ${dateTimeFormat.format(Date(startEpochMs))}")
                }
                TextButton(onClick = {
                    showDateTimePicker(context, endEpochMs) {
                        endEpochMs = it
                        rangeEdited = true
                    }
                }) {
                    Text("${stringResource(R.string.settings_logs_range_end)}: ${dateTimeFormat.format(Date(endEpochMs))}")
                }
                if (!valid) {
                    Text(
                        stringResource(R.string.settings_logs_range_invalid),
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColors.warningText,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                if (rangeEdited) onCustomRangeChanged(range)
                onExport(range)
            }) {
                Text(stringResource(R.string.settings_logs_range_export))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

private fun showDateTimePicker(context: Context, currentEpochMs: Long, onSelected: (Long) -> Unit) {
    val current = Calendar.getInstance().apply { timeInMillis = currentEpochMs }
    DatePickerDialog(
        context,
        { _, year, month, day ->
            TimePickerDialog(
                context,
                { _, hour, minute ->
                    val selected = Calendar.getInstance().apply {
                        clear()
                        set(year, month, day, hour, minute, 0)
                    }.timeInMillis
                    onSelected(selected)
                },
                current.get(Calendar.HOUR_OF_DAY),
                current.get(Calendar.MINUTE),
                DateFormat.is24HourFormat(context),
            ).show()
        },
        current.get(Calendar.YEAR),
        current.get(Calendar.MONTH),
        current.get(Calendar.DAY_OF_MONTH),
    ).show()
}

/** One settings group as a raised card, so groups read as blocks rather than a running list. */
@Composable
internal fun SettingsCard(
    title: String,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        SectionHeader(title, trailing = trailing)
        SettingsBlock(content)
    }
}

@Composable
internal fun SettingsBlock(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
        ) { ProvideTextStyle(MaterialTheme.typography.bodyMedium, content) }
    }
}

/**
 * The host's composed plugins, with the managed profile's directories one tap away.
 *
 * A stock web composition mounts around forty plugins. Inlined into a page that already scrolls,
 * that buries everything below it and gives the list no room of its own; the count is the part
 * worth seeing without asking, so the card carries that and the rest lives behind its entries.
 *
 * The inventory itself is read-only. When the Host has a managed profile, its Web plugin manager
 * provides the authoritative actions and dynamically registered configuration screens.
 */
@Composable
private fun PluginsCard(inventory: PluginInventorySnapshot, onOpenList: () -> Unit, onOpenDirectory: (PluginDirectory) -> Unit) {
    SettingsCard(stringResource(R.string.settings_plugins)) {
        if (inventory.entries.isEmpty() && !inventory.managementAvailable) {
            Text(
                stringResource(R.string.plugins_empty),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SettingsCard
        }
        if (inventory.managementAvailable) {
            OutlinedButton(
                onClick = { onOpenDirectory(PluginDirectory.BuiltIn) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.plugins_native_builtin)) }
            OutlinedButton(
                onClick = { onOpenDirectory(PluginDirectory.Experimental) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.plugins_native_experimental)) }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable(onClick = onOpenList)
                .padding(vertical = DsSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.settings_plugins_inventory),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                inventory.entries.size.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.width(DsSpacing.xsmall))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * The plugin list itself.
 *
 * A sheet rather than an expanding block: forty rows need their own scroll surface, and nesting one
 * inside the settings page's scroll means the list and the page fight over the same drag. The
 * filter sits above the scroll so it stays reachable however far down the list you are.
 *
 * Rows show the shortened module name, since the scope and `dsh-host-`/`dsh-client-` prefixes are
 * the same on nearly every row and push the part that differs off the end of a phone screen.
 */
@Composable
private fun PluginsSheet(inventory: PluginInventorySnapshot, onDismiss: () -> Unit) {
    var filter by remember { mutableStateOf("") }
    val matching = remember(inventory, filter) {
        val q = filter.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) {
            inventory.entries
        } else {
            inventory.entries.filter {
                it.moduleName.lowercase(Locale.ROOT).contains(q) ||
                    it.entryId.lowercase(Locale.ROOT).contains(q)
            }
        }
    }
    AppBottomSheet(
        title = stringResource(R.string.settings_plugins),
        subtitle = inventory.entries.size.toString(),
        onDismiss = onDismiss,
    ) {
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.plugins_search_hint)) },
            singleLine = true,
        )
        if (matching.isEmpty()) {
            Text(
                stringResource(R.string.plugins_empty),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@AppBottomSheet
        }
        LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
            items(matching, key = { it.entryId }) { entry -> PluginRow(entry) }
        }
    }
}

@Composable
private fun PluginRow(entry: PluginInventoryEntry) {
    var expanded by remember(entry.entryId) { mutableStateOf(false) }
    DisclosureRow(
        title = moduleShortName(entry.moduleName),
        summary = stringResource(
            if (entry.enabled) R.string.plugins_enabled else R.string.plugins_disabled,
        ),
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        Column(
            modifier = Modifier.padding(start = DsSpacing.xlarge, bottom = DsSpacing.xsmall),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
        ) {
            Text(entry.entryId, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(entry.moduleName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // The mount phase only means anything for a plugin the composition asked for.
            if (entry.enabled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StateDot(
                        when (entry.fiberPhase) {
                            PluginFiberPhase.ACTIVE -> StateDotState.Done
                            PluginFiberPhase.LOADING, PluginFiberPhase.UNLOADING -> StateDotState.Running
                            PluginFiberPhase.FAILED -> StateDotState.Error
                            PluginFiberPhase.PENDING, null -> StateDotState.Idle
                        },
                    )
                    Spacer(Modifier.width(DsSpacing.xsmall))
                    Text(
                        stringResource(pluginPhaseLabel(entry.fiberPhase)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // A failed fiber's cause exists only on the Host that ran it, so show that message
                // verbatim. Old Hosts omit it and running entries have nothing to report: their rows
                // keep the exact look they had, rather than having a cause guessed for them.
                val diagnostic = entry.diagnostic
                if (entry.fiberPhase == PluginFiberPhase.FAILED && diagnostic != null) {
                    Text(
                        text = diagnostic,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColors.danger,
                    )
                }
            }
        }
    }
}

@StringRes
private fun pluginPhaseLabel(phase: PluginFiberPhase?): Int = when (phase) {
    PluginFiberPhase.PENDING -> R.string.plugins_phase_pending
    PluginFiberPhase.LOADING -> R.string.plugins_phase_loading
    PluginFiberPhase.ACTIVE -> R.string.plugins_phase_active
    PluginFiberPhase.FAILED -> R.string.plugins_phase_failed
    PluginFiberPhase.UNLOADING -> R.string.plugins_phase_unloading
    null -> R.string.plugins_phase_unmounted
}

/**
 * `@deepseek-ai/dsh-client-ui-plan` → `ui-plan`.
 *
 * Ported from the harness's own `moduleShortName`, prefix for prefix, so the two lists name the
 * same plugin the same way.
 */
private fun moduleShortName(moduleName: String): String {
    val prefixes = listOf("cordis:", "cordis-plugin-", "dsh-host-", "dsh-client-", "dsh-")
    var name = moduleName.substringAfterLast('/')
    for (prefix in prefixes) name = name.removePrefix(prefix)
    return name.ifBlank { moduleName }
}

@Composable
private fun ConnectionSection(connectionState: ConnectionUiState, onDisconnect: () -> Unit) {
    val isConnected = connectionState.phase == ConnectionPhase.CONNECTED ||
        connectionState.phase == ConnectionPhase.RECONNECTING

    if (isConnected && connectionState.host != null) {
        // Connected: the "connected to <host>" line is itself the status, so there is no separate
        // status row. The disconnect action sits on that line's right edge — not a full-width button
        // of its own — so it cannot be mistaken for the connection list entries beneath it.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.settings_connection_host),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    connectionState.host.authority,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(DsSpacing.small))
            OutlinedButton(
                onClick = onDisconnect,
                contentPadding = PaddingValues(horizontal = DsSpacing.medium, vertical = DsSpacing.tiny),
            ) { Text(stringResource(R.string.settings_connection_disconnect)) }
        }
    } else {
        // Not connected: no "connected to" line to carry the state, so show the dot and its label.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StateDot(
                when (connectionState.phase) {
                    ConnectionPhase.CONNECTED -> StateDotState.Done
                    ConnectionPhase.RECONNECTING, ConnectionPhase.CONNECTING -> StateDotState.Running
                    else -> StateDotState.Idle
                },
            )
            Spacer(Modifier.width(DsSpacing.small))
            Text(
                when (connectionState.phase) {
                    ConnectionPhase.CONNECTED -> stringResource(R.string.common_connected)
                    ConnectionPhase.RECONNECTING -> stringResource(R.string.common_reconnecting)
                    ConnectionPhase.CONNECTING -> stringResource(R.string.common_loading)
                    else -> stringResource(R.string.common_offline)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One choice in a settings dropdown: its stored value and its display label. */
private data class SettingOption<T>(val value: T, val label: String)

/**
 * A labelled dropdown row: name on the left, an outlined value button on the right.
 *
 * Matches the pickers on the Harness → Agent settings page (the default permission and agent-preset
 * rows) so every value picker in Settings looks the same: a [Row] with the label taking one half and
 * an [OutlinedButton] showing the current value in the other, opening a Material dropdown.
 */
@Composable
private fun <T> SettingDropdownRow(
    label: String,
    currentLabel: String,
    options: List<SettingOption<T>>,
    isSelected: (T) -> Boolean,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Box(Modifier.weight(1f, fill = false)) {
            OutlinedButton(onClick = { expanded = true }) {
                Text(currentLabel)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        enabled = !isSelected(option.value),
                        onClick = {
                            expanded = false
                            onSelect(option.value)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LanguageRow(settings: AppSettings, onSelect: (String?) -> Unit) {
    // A dropdown, not a grid of twelve cells. The grid spent four rows of a settings page on a
    // choice that is made once and then never touched, and at three per row the longer endonyms had
    // to be ellipsised to fit — so the control was both the largest thing on the screen and unable
    // to spell out its own options.
    val labels = LanguageOptions.associateWith { option ->
        option.label ?: option.labelRes?.let { stringResource(it) }.orEmpty()
    }
    val current = LanguageOptions.firstOrNull { it.tag == settings.localeOverride }
        ?: LanguageOptions.first()
    SettingDropdownRow(
        label = stringResource(R.string.settings_language),
        currentLabel = labels[current].orEmpty(),
        options = LanguageOptions.map { SettingOption(it.tag, labels[it].orEmpty()) },
        isSelected = { tag -> tag == settings.localeOverride },
        onSelect = onSelect,
    )
}

@Composable
private fun AppearanceRow(settings: AppSettings, onSelect: (String) -> Unit) {
    val options = listOf(
        SettingOption("light", stringResource(R.string.settings_appearance_light)),
        SettingOption("dark", stringResource(R.string.settings_appearance_dark)),
        SettingOption("system", stringResource(R.string.settings_appearance_system)),
    )
    SettingDropdownRow(
        label = stringResource(R.string.settings_appearance),
        currentLabel = options.firstOrNull { it.value == settings.themePreference }?.label
            ?: options.last().label,
        options = options,
        isSelected = { value -> value == settings.themePreference },
        onSelect = onSelect,
    )
}
