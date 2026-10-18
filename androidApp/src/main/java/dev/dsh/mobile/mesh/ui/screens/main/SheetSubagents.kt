package dev.dsh.mobile.mesh.ui.screens.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.core.wire.dto.SubagentListEntry
import dev.dsh.mobile.mesh.data.SessionRow
import dev.dsh.mobile.mesh.ui.components.DsBottomSheet
import dev.dsh.mobile.mesh.ui.components.formatDurationMs
import dev.dsh.mobile.mesh.ui.components.DsPill
import dev.dsh.mobile.mesh.ui.components.StateDot
import dev.dsh.mobile.mesh.ui.components.StateDotState
import dev.dsh.mobile.mesh.ui.theme.DsSpacing
import dev.dsh.mobile.mesh.ui.theme.DsTheme
import dev.dsh.mobile.mesh.ui.theme.DsType

/** The catalog entry point; selecting a child navigates to its session details. */
@Composable
internal fun SubagentsSheet(
    entries: List<SubagentListEntry>,
    sessions: List<SessionRow>,
    onOpenSubagent: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DsTheme.colors
    val sessionsById = remember(sessions) { sessions.associateBy { it.sessionId } }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val hasRunningTiming = entries.any { entry ->
        val row = subagentId(entry)?.let(sessionsById::get)
        row?.subagentTiming?.active != null && subagentActivityRunning(entry, row.running)
    }
    LaunchedEffect(hasRunningTiming) {
        if (!hasRunningTiming) return@LaunchedEffect
        while (true) {
            nowMs = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000L)
        }
    }
    DsBottomSheet(
        title = stringResource(R.string.subagents_title),
        subtitle = entries.size.takeIf { it > 0 }?.toString(),
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (entries.isEmpty()) {
                Text(
                    stringResource(R.string.subagents_empty),
                    style = DsType.caption11,
                    color = colors.labelTertiary,
                )
            }
            entries.forEach { entry ->
                val child = subagentId(entry)?.let(sessionsById::get)
                val running = subagentActivityRunning(entry, child?.running == true)
                val elapsedMs = child?.subagentTiming?.let { subagentElapsedMs(it, running, nowMs) }
                SubagentRow(
                    entry = entry,
                    running = running,
                    elapsedMs = elapsedMs,
                    onClick = { subagentId(entry)?.let(onOpenSubagent) },
                )
            }
        }
    }
}

@Composable
private fun SubagentRow(entry: SubagentListEntry, running: Boolean, elapsedMs: Long?, onClick: () -> Unit) {
    val colors = DsTheme.colors
    val modeLabel = when (entry) {
        is SubagentListEntry.ChildOneShot -> stringResource(R.string.subagents_oneshot)
        is SubagentListEntry.ChildContinuable -> stringResource(R.string.subagents_continuable)
        is SubagentListEntry.Projected -> when (entry.entry.mode) {
            "one-shot" -> stringResource(R.string.subagents_oneshot)
            "continuable" -> stringResource(R.string.subagents_continuable)
            else -> null
        }
        else -> null
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = canOpenSubagentTranscript(entry), onClick = onClick)
            .padding(vertical = DsSpacing.xsmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StateDot(if (running) StateDotState.Running else StateDotState.Idle)
        Spacer(Modifier.width(DsSpacing.small))
        Text(
            subagentLabel(entry) ?: subagentId(entry).orEmpty(),
            style = DsType.small13,
            color = colors.labelPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        elapsedMs?.let {
            Text(formatDurationMs(it), style = DsType.caption11, color = colors.labelTertiary)
            if (modeLabel != null) Spacer(Modifier.width(DsSpacing.xsmall))
        }
        modeLabel?.let { DsPill(text = it) }
    }
}
