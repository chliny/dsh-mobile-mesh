package dev.dsh.mobile.mesh.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.util.Locale
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.core.wire.dto.FULL_ACCESS_PRESET
import dev.dsh.mobile.mesh.ui.screens.main.displayDescription
import dev.dsh.mobile.mesh.ui.screens.main.displayName
import dev.dsh.mobile.mesh.ui.screens.main.permissionPresetLabel
import dev.dsh.mobile.mesh.ui.theme.DsSpacing
import dev.dsh.mobile.mesh.ui.theme.DsTheme
import dev.dsh.mobile.mesh.ui.theme.DsType

private val conventionalPresetName = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")

/** Match Web's built-in labels without overriding names supplied by a Host administrator. */
@Composable
private fun defaultPermissionLabel(value: String, name: String): String {
    val builtInName = when (value) {
        "read-only", "read_only" -> "Read Only"
        "workspace-write", "workspace_write" -> "Workspace Write"
        FULL_ACCESS_PRESET -> "Full access"
        else -> null
    }
    if (builtInName != null && (name == value || name.equals(builtInName, ignoreCase = true))) {
        return permissionPresetLabel(value, name)
    }
    if (!conventionalPresetName.matches(name)) return name
    return name.split('-').joinToString(" ") { it.replaceFirstChar { ch -> ch.titlecase(Locale.ROOT) } }
}

/** Host-wide defaults are authoritative settings, never session-specific permission options. */
@Composable
internal fun HostDefaultsContent(state: HostDefaultsSettingsUiState, viewModel: HostDefaultsSettingsViewModel) {
    val colors = DsTheme.colors
    val permissionKey = state.permissionConfirmationKey
    var confirmFullAccess by remember(permissionKey) { mutableStateOf(false) }
    var acknowledged by remember(permissionKey, confirmFullAccess) { mutableStateOf(false) }
    var permissionMenuOpen by remember(permissionKey) { mutableStateOf(false) }
    var presetMenuOpen by remember { mutableStateOf(false) }
    var inspectedPresetId by remember(state.agentPresets, state.selectedAgentPreset) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall)) {
        if (state.loading) Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator()
            Text(stringResource(R.string.host_defaults_loading), Modifier.padding(start = 12.dp))
        }
        state.message?.let { message ->
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(message, Modifier.weight(1f))
                TextButton(onClick = viewModel::clearMessage) { Text(stringResource(R.string.host_defaults_dismiss)) }
            }
        }
        state.error?.let { Text(it, Modifier.padding(12.dp), color = colors.error) }
        val selectedPermission = state.permissionOptions.firstOrNull { it.value == state.defaultPermission }
        val canChoosePermission = state.permissionWritable && !state.loading && !state.busy
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.host_defaults_permission), Modifier.weight(1f), style = DsType.std14)
            if (state.permissionOptions.isNotEmpty()) Box(Modifier.weight(1f, fill = false)) {
                OutlinedButton(onClick = { permissionMenuOpen = true }, enabled = canChoosePermission) {
                    val label = state.defaultPermission?.let { value ->
                        defaultPermissionLabel(value, selectedPermission?.name ?: value)
                    } ?: stringResource(R.string.common_none)
                    Text(label)
                }
                DropdownMenu(expanded = permissionMenuOpen && canChoosePermission, onDismissRequest = { permissionMenuOpen = false }) {
                    state.permissionOptions.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(defaultPermissionLabel(preset.value, preset.name)) },
                            enabled = preset.value != state.defaultPermission,
                            onClick = {
                                permissionMenuOpen = false
                                if (preset.value == FULL_ACCESS_PRESET) confirmFullAccess = true
                                else viewModel.setDefaultPermission(preset.value)
                            },
                        )
                    }
                }
            }
        }
        if (state.permissionOptions.isEmpty() && !state.loading) {
            Text(stringResource(R.string.host_defaults_no_permissions), style = DsType.caption11, color = colors.labelSecondary)
        }
        val preset = state.agentPresets.firstOrNull { it.id == (inspectedPresetId ?: state.selectedAgentPreset) }
            ?: state.agentPresets.firstOrNull()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.host_defaults_agent_presets), Modifier.weight(1f), style = DsType.std14)
            if (preset != null) Box(Modifier.weight(1f, fill = false)) {
                OutlinedButton(onClick = { presetMenuOpen = true }, enabled = !state.loading && !state.busy) {
                    Text(preset.displayName())
                }
                DropdownMenu(expanded = presetMenuOpen && !state.loading && !state.busy,
                    onDismissRequest = { presetMenuOpen = false }) {
                    state.agentPresets.forEach { option ->
                        DropdownMenuItem(text = { Text(option.displayName()) }, onClick = {
                            inspectedPresetId = option.id
                            presetMenuOpen = false
                        })
                    }
                }
            }
        }
        if (preset == null && !state.loading) {
            Text(stringResource(R.string.host_defaults_no_agents), color = colors.labelSecondary)
        }
        if (preset != null) {
            val selected = preset.id == state.selectedAgentPreset
            Column(Modifier.fillMaxWidth().padding(vertical = DsSpacing.tiny),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
                preset.displayDescription()?.let { Text(it, style = DsType.caption11, color = colors.labelSecondary) }
                preset.broken?.let { Text(it, style = DsType.caption11, color = colors.error) }
                if (selected) Text(stringResource(R.string.host_defaults_current), style = DsType.caption11, color = colors.success)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { viewModel.readAgentPreset(preset.id) }, enabled = !state.busy) {
                        Text(stringResource(R.string.host_defaults_details))
                    }
                    Button(onClick = { viewModel.selectDefaultAgentPreset(preset.id) },
                        enabled = !state.busy && state.agentPresetWritable && !selected && preset.broken == null) {
                        Text(stringResource(R.string.host_defaults_set_default))
                    }
                }
            }
        }
    }

    if (confirmFullAccess) AlertDialog(
        onDismissRequest = { confirmFullAccess = false },
        title = { Text(stringResource(R.string.host_defaults_full_access_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.host_defaults_full_access_warning))
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { acknowledged = !acknowledged }) {
                    Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
                    Text(stringResource(R.string.host_defaults_full_access_ack))
                }
            }
        },
        confirmButton = {
            Button(enabled = acknowledged && !state.busy && state.permissionWritable,
                onClick = {
                    confirmFullAccess = false
                    viewModel.setDefaultPermission(FULL_ACCESS_PRESET, acceptFullAccess = true, confirmationKey = permissionKey)
                }) {
                Text(stringResource(R.string.host_defaults_confirm))
            }
        },
        dismissButton = { TextButton(onClick = { confirmFullAccess = false }) {
            Text(stringResource(R.string.host_defaults_cancel))
        } },
    )
    state.agentPresetDocument?.let { document -> AlertDialog(
        onDismissRequest = viewModel::clearAgentPresetDocument,
        title = { Text(document.name ?: document.agentPreset) },
        text = { Text(document.content) },
        confirmButton = { TextButton(onClick = viewModel::clearAgentPresetDocument) {
            Text(stringResource(R.string.host_defaults_close))
        } },
    ) }
}
