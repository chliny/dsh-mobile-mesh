package dev.dsh.mobile.mesh.ui.screens.settings

import androidx.compose.foundation.border
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.core.wire.dto.FULL_ACCESS_PRESET
import dev.dsh.mobile.mesh.ui.components.DsIconButton
import dev.dsh.mobile.mesh.ui.theme.DsTheme
import dev.dsh.mobile.mesh.ui.theme.DsType

/** Host-wide defaults are authoritative settings, never session-specific permission options. */
@Composable
fun HostDefaultsSettings(onClose: () -> Unit, viewModel: HostDefaultsSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = DsTheme.colors
    val permissionKey = state.permissionConfirmationKey
    var confirmFullAccess by remember(permissionKey) { mutableStateOf(false) }
    var acknowledged by remember(permissionKey, confirmFullAccess) { mutableStateOf(false) }
    LaunchedEffect(viewModel) { viewModel.refresh() }

    Surface(Modifier.fillMaxSize(), color = colors.bgBase) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                DsIconButton(Icons.AutoMirrored.Filled.ArrowBack,
                    stringResource(R.string.common_back), onClick = onClose)
                Text(stringResource(R.string.host_defaults_title), modifier = Modifier.weight(1f), style = DsType.large20)
                TextButton(onClick = viewModel::refresh, enabled = !state.busy && !state.loading) {
                    Text(stringResource(R.string.host_defaults_refresh))
                }
            }
            if (state.loading) Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator()
                Text(stringResource(R.string.host_defaults_loading), Modifier.padding(start = 12.dp))
            }
            state.message?.let { message ->
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f))
                    TextButton(onClick = viewModel::clearMessage) {
                        Text(stringResource(R.string.host_defaults_dismiss))
                    }
                }
            }
            state.error?.let { Text(it, Modifier.padding(12.dp), color = colors.error) }
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                item { Text(stringResource(R.string.host_defaults_permission), style = DsType.large20) }
                if (state.permissionOptions.isEmpty() && !state.loading) item {
                    Text(stringResource(R.string.host_defaults_no_permissions), color = colors.labelSecondary)
                }
                items(state.permissionOptions, key = { "permission:${it.value}" }) { preset ->
                    val selected = preset.value == state.defaultPermission
                    val enabled = !state.busy && state.permissionWritable
                    Row(Modifier.fillMaxWidth().border(1.dp, colors.borderL1)
                        .clickable(enabled = enabled && !selected) {
                            if (preset.value == FULL_ACCESS_PRESET) confirmFullAccess = true
                            else viewModel.setDefaultPermission(preset.value)
                        }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(preset.name)
                            preset.description?.let { Text(it, color = colors.labelSecondary) }
                        }
                        RadioButton(selected = selected, enabled = enabled && !selected, onClick = {
                            if (preset.value == FULL_ACCESS_PRESET) confirmFullAccess = true
                            else viewModel.setDefaultPermission(preset.value)
                        })
                    }
                }
                item { Text(stringResource(R.string.host_defaults_agent_presets),
                    Modifier.padding(top = 12.dp), style = DsType.large20) }
                if (state.agentPresets.isEmpty() && !state.loading) item {
                    Text(stringResource(R.string.host_defaults_no_agents), color = colors.labelSecondary)
                }
                items(state.agentPresets, key = { "agent:${it.id}" }) { preset ->
                    val selected = preset.id == state.selectedAgentPreset
                    Column(Modifier.fillMaxWidth().border(1.dp, colors.borderL1).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(preset.name ?: preset.id)
                        preset.description?.let { Text(it, color = colors.labelSecondary) }
                        preset.broken?.let { Text(it, color = colors.error) }
                        if (selected) Text(stringResource(R.string.host_defaults_current), color = colors.success)
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
