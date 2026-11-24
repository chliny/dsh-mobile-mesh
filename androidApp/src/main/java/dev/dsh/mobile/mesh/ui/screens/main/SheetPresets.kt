package dev.dsh.mobile.mesh.ui.screens.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.core.wire.dto.AgentPresetListValue
import dev.dsh.mobile.mesh.data.SessionStore
import dev.dsh.mobile.mesh.ui.components.StatusBadge
import dev.dsh.mobile.mesh.ui.components.AppBottomSheet
import dev.dsh.mobile.mesh.ui.theme.DsSpacing
import kotlinx.coroutines.launch

/**
 * The agent-preset picker.
 *
 * A preset determines which tools an agent has, so the harness pins it for the life of a session
 * and refuses a change once work has started. Rather than surfacing that as a failure after the
 * fact, the rows go inert with an explanation whenever the session is no longer blank.
 */
@Composable
internal fun PresetsSheet(
    presets: AgentPresetListValue?,
    currentPreset: String?,
    sessionBlank: Boolean,
    store: SessionStore,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    AppBottomSheet(
        title = stringResource(R.string.presets_title),
        subtitle = if (sessionBlank) null else stringResource(R.string.presets_locked),
        onDismiss = onDismiss,
    ) {
        val entries = presets?.presets.orEmpty()
        if (entries.isEmpty()) {
            Text(
                stringResource(R.string.presets_empty),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@AppBottomSheet
        }
        Column(
            modifier = Modifier
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            entries.forEach { entry ->
                val selected = entry.id == currentPreset
                val selectable = sessionBlank && entry.broken == null
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = selectable) {
                            scope.launch {
                                if (store.selectAgentPreset(entry.id)) onDismiss()
                            }
                        }
                        .padding(vertical = DsSpacing.small),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                entry.displayName(),
                                style = MaterialTheme.typography.titleSmall,
                                color = when {
                                    selected -> MaterialTheme.colorScheme.primary
                                    selectable -> MaterialTheme.colorScheme.onSurface
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            Spacer(Modifier.width(DsSpacing.small))
                            StatusBadge(
                                stringResource(
                                    if (entry.isShippedPreset()) R.string.presets_system else R.string.presets_user,
                                ),
                            )
                            if (entry.isDefault) {
                                Spacer(Modifier.width(DsSpacing.tiny))
                                StatusBadge(stringResource(R.string.presets_default))
                            }
                        }
                        entry.displayDescription()?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        entry.broken?.let {
                            Text(
                                stringResource(R.string.presets_broken, it),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    if (selected) {
                        Spacer(Modifier.width(DsSpacing.small))
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = stringResource(R.string.presets_current),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}
