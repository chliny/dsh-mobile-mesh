package dev.dsh.mobile.mesh.ui.screens.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.core.wire.dto.FULL_ACCESS_PRESET
import dev.dsh.mobile.mesh.core.wire.dto.PermissionSelect

import dev.dsh.mobile.mesh.ui.components.DsBottomSheet
import dev.dsh.mobile.mesh.ui.theme.DsSpacing

/** The permission-preset picker. `custom` is a derived state, so it is never offered as a target. */
@Composable
internal fun PermissionMenu(
    select: PermissionSelect,
    current: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    DsBottomSheet(
        title = stringResource(R.string.permission_preset),
        onDismiss = onDismiss,
    ) {
        select.selectable.forEach { option ->
            val selected = option.value == current
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(option.value) }
                    .padding(vertical = DsSpacing.small),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        permissionPresetLabel(option.value, option.name),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    val description = option.description
                        ?: if (option.value == FULL_ACCESS_PRESET) {
                            stringResource(R.string.permission_confirm_body)
                        } else {
                            null
                        }
                    if (description != null) {
                        Text(description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (selected) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        if (select.currentValue !in select.selectable.map { it.value }) {
            Text(
                stringResource(R.string.permission_custom_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
internal fun permissionPresetLabel(value: String, wireName: String): String = when (value) {
    "read-only", "read_only" -> stringResource(R.string.permission_read_only)
    "workspace-write", "workspace_write" -> stringResource(R.string.permission_workspace_write)
    FULL_ACCESS_PRESET -> stringResource(R.string.permission_full_access)
    else -> wireName
}

/**
 * Full access removes the approval prompt for every subsequent tool call, so it takes a deliberate
 * acknowledgement rather than a single tap — the same gate the desktop client puts on it.
 */
@Composable
internal fun FullAccessConfirmDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var acknowledged by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.permission_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                Text(
                    stringResource(R.string.permission_confirm_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { acknowledged = !acknowledged },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
                    Text(
                        stringResource(R.string.permission_confirm_ack),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = acknowledged,
                // Full access removes every approval prompt; the action keeps the danger colour.
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text(stringResource(R.string.common_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
