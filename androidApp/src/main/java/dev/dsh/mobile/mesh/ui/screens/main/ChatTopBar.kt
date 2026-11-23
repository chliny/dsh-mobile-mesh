package dev.dsh.mobile.mesh.ui.screens.main

import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.connection.ConnectionPhase
import dev.dsh.mobile.mesh.ui.components.FeatherIcons
import dev.dsh.mobile.mesh.ui.components.StateDot
import dev.dsh.mobile.mesh.ui.components.StateDotState
import dev.dsh.mobile.mesh.ui.theme.DsSpacing

/** The two views of a session the harness offers. */
internal enum class ChatTab { Chat, Trajectory }

/**
 * The session chrome: a title row plus the Chat / Trajectory switch.
 *
 * The header carries the session title and connection controls; the row below carries the tab
 * switch and the background-job and subagent counts. The title and the preset share a column rather
 * than stacking, and the row disappears entirely when it would be empty: four stacked rows of chrome
 * over a white page ate a third of a phone screen before the first message.
 *
 * Styled as Material 3: theme typography and colours, icon buttons, an assist chip per counter, and
 * a single-choice segmented button for the tabs.
 */
@Composable
internal fun ChatTopBar(
    title: String,
    connectionPhase: ConnectionPhase,
    agentPresetLabel: String?,
    subagentCount: Int,
    jobCount: Int,
    detailsOpen: Boolean,
    tab: ChatTab,
    trajectoryAvailable: Boolean = true,
    onOpenDrawer: () -> Unit,
    onOpenPresets: () -> Unit,
    onOpenSubagents: () -> Unit,
    onOpenJobs: () -> Unit,
    onOpenDetails: () -> Unit,
    onOpenFiles: () -> Unit,
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
            Box(
                modifier = Modifier.size(40.dp),
                contentAlignment = Alignment.Center,
            ) {
                ConnectionStatusDot(connectionPhase)
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

@Composable
private fun ConnectionStatusDot(phase: ConnectionPhase) {
    val state = when (phase) {
        ConnectionPhase.CONNECTED -> StateDotState.Done
        ConnectionPhase.RECONNECTING -> StateDotState.Warning
        ConnectionPhase.CONNECTING -> StateDotState.Running
        ConnectionPhase.DISCONNECTED -> StateDotState.Error
    }
    StateDot(state, size = 8.dp)
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
 * The tab strip, as a segmented control plus the two counters.
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
    ) {
        if (trajectoryAvailable) {
            // A 30dp segmented control, not Material's 40dp one: at the top of every conversation
            // the extra eight dp is pure chrome, and the tab strip is a switch, not a button row.
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(Modifier.height(30.dp)) {
                    TabSegment(
                        label = stringResource(R.string.chat_tab),
                        selected = tab == ChatTab.Chat,
                        onClick = { onTabChange(ChatTab.Chat) },
                    )
                    TabSegment(
                        label = stringResource(R.string.trajectory_title),
                        selected = tab == ChatTab.Trajectory,
                        onClick = { onTabChange(ChatTab.Trajectory) },
                    )
                }
            }
        }
        if (subagentCount > 0) {
            Spacer(Modifier.width(DsSpacing.small))
            MetaPill(
                icon = Icons.Outlined.Groups,
                label = "$subagentCount",
                contentDescription = stringResource(R.string.subagents_title),
                onClick = onOpenSubagents,
            )
        }
        if (jobCount > 0) {
            Spacer(Modifier.width(DsSpacing.small))
            MetaPill(
                icon = Icons.Outlined.Dashboard,
                label = "$jobCount",
                contentDescription = stringResource(R.string.jobs_title),
                onClick = onOpenJobs,
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** One half of the 30dp tab switch. */
@Composable
private fun TabSegment(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            )
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A 28dp counter pill; Material chips are 32dp and pad like buttons. */
@Composable
private fun MetaPill(
    icon: ImageVector,
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.height(28.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
