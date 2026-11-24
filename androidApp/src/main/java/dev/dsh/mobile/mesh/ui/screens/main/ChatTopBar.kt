package dev.dsh.mobile.mesh.ui.screens.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.ui.components.FeatherIcons
import dev.dsh.mobile.mesh.ui.theme.DsSpacing

/** The two views of a session the harness offers. */
internal enum class ChatTab { Chat, Trajectory }

/**
 * The session chrome: a title row plus the Chat / Trajectory switch.
 *
 * The header carries the session title plus the changes, terminal and file entries — the changes and
 * terminal buttons appear only when the Host offers them; the row below carries the tab switch and
 * the background-job and subagent counts. The title and the preset share a column rather than
 * stacking, and the row disappears entirely when it would be empty: four stacked rows of chrome over
 * a white page ate a third of a phone screen before the first message.
 *
 * Styled as Material 3: theme typography and colours, icon buttons, an assist chip per counter, and
 * a single-choice segmented button for the tabs.
 */
@Composable
internal fun ChatTopBar(
    title: String,
    agentPresetLabel: String?,
    subagentCount: Int,
    jobCount: Int,
    detailsOpen: Boolean,
    tab: ChatTab,
    trajectoryAvailable: Boolean = true,
    changesAvailable: Boolean = false,
    onOpenDrawer: () -> Unit,
    onOpenPresets: () -> Unit,
    onOpenSubagents: () -> Unit,
    onOpenJobs: () -> Unit,
    onOpenDetails: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenChanges: () -> Unit,
    onOpenTerminal: () -> Unit,
    terminalAvailable: Boolean,
    onTabChange: (ChatTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onOpenDrawer) {
                Icon(
                    Icons.AutoMirrored.Outlined.List,
                    contentDescription = stringResource(R.string.chatlist_open),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                AgentLabel(agentPresetLabel, onOpenPresets)
            }
            if (changesAvailable) {
                IconButton(onClick = onOpenChanges) {
                    Icon(
                        FeatherIcons.GitPullRequest,
                        contentDescription = stringResource(R.string.forge_changes),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (terminalAvailable) {
                IconButton(onClick = onOpenTerminal) {
                    Icon(
                        Icons.Filled.Terminal,
                        contentDescription = stringResource(R.string.terminal_title),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onOpenFiles) {
                Icon(
                    Icons.Filled.Folder,
                    contentDescription = stringResource(R.string.workspace_files_title),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!detailsOpen) {
                IconButton(onClick = onOpenDetails) {
                    Icon(
                        FeatherIcons.Info,
                        contentDescription = stringResource(R.string.chat_details_title),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (trajectoryAvailable || subagentCount > 0 || jobCount > 0) {
            ChatTabRow(
                tab = tab,
                trajectoryAvailable = trajectoryAvailable,
                subagentCount = subagentCount,
                jobCount = jobCount,
                onTabChange = onTabChange,
                onOpenSubagents = onOpenSubagents,
                onOpenJobs = onOpenJobs,
            )
        }
    }
}

/** The preset label under the title: text that opens the preset picker. */
@Composable
private fun AgentLabel(label: String?, onClick: () -> Unit) {
    if (label == null) return
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/**
 * The tab strip, as two Material filter chips plus the two counters.
 *
 * Which tab is live is load-bearing, not decoration: the two views render a user message completely
 * differently — a right-aligned bubble in Chat, a `> line` of caption text in the Trajectory ledger —
 * so a reader who cannot tell at a glance concludes the chat itself is broken.
 */
@Composable
private fun ChatTabRow(
    tab: ChatTab,
    trajectoryAvailable: Boolean,
    subagentCount: Int,
    jobCount: Int,
    onTabChange: (ChatTab) -> Unit,
    onOpenSubagents: () -> Unit,
    onOpenJobs: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DsSpacing.medium, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        if (trajectoryAvailable) {
            // Material filter chips, not a bespoke segmented track: 32dp is the compact Material
            // control that still reads as a switch (the selected chip carries the check).
            FilterChip(
                selected = tab == ChatTab.Chat,
                onClick = { onTabChange(ChatTab.Chat) },
                label = { Text(stringResource(R.string.chat_tab)) },
            )
            FilterChip(
                selected = tab == ChatTab.Trajectory,
                onClick = { onTabChange(ChatTab.Trajectory) },
                label = { Text(stringResource(R.string.trajectory_title)) },
            )
        }
        if (subagentCount > 0) {
            AssistChip(
                onClick = onOpenSubagents,
                label = { Text("$subagentCount") },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Groups,
                        contentDescription = stringResource(R.string.subagents_title),
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }
        if (jobCount > 0) {
            AssistChip(
                onClick = onOpenJobs,
                label = { Text("$jobCount") },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Dashboard,
                        contentDescription = stringResource(R.string.jobs_title),
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

