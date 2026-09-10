package dev.dsh.mobile.mesh.ui.screens.main

import androidx.annotation.StringRes
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.core.wire.dto.AgentPresetEntry
import dev.dsh.mobile.mesh.core.wire.dto.AgentPresetListValue
import dev.dsh.mobile.mesh.core.wire.dto.AgentPresetTrust
import dev.dsh.mobile.mesh.core.wire.dto.GoalPhase
import dev.dsh.mobile.mesh.core.wire.dto.JobStatus
import dev.dsh.mobile.mesh.core.wire.dto.SubagentListEntry
import dev.dsh.mobile.mesh.ui.components.StateDotState
import dev.dsh.mobile.mesh.ui.theme.DsTheme

/** Shared label and status mappings for the chat surface. */

@StringRes
internal fun goalPhaseLabelRes(phase: GoalPhase): Int = when (phase) {
    GoalPhase.ACTIVE -> R.string.goal_phase_active
    GoalPhase.PAUSED -> R.string.goal_phase_paused
    GoalPhase.BLOCKED -> R.string.goal_phase_blocked
    GoalPhase.COMPLETE -> R.string.goal_phase_complete
}

internal fun todoStatusDot(status: String): StateDotState = when (status) {
    "completed" -> StateDotState.Done
    "in_progress" -> StateDotState.Running
    else -> StateDotState.Idle
}

internal fun jobStatusDot(status: JobStatus): StateDotState = when (status) {
    JobStatus.RUNNING, JobStatus.STOPPING -> StateDotState.Running
    JobStatus.COMPLETED -> StateDotState.Done
    JobStatus.KILLED, JobStatus.FAILED -> StateDotState.Error
}

@Composable
internal fun workflowStatusLabel(status: String?): String? = when (status) {
    "running" -> stringResource(R.string.workflow_running)
    "completed" -> stringResource(R.string.workflow_completed)
    "failed", "error", "cancelled" -> stringResource(R.string.workflow_failed)
    else -> null
}

internal fun workflowMemberDot(status: String?): StateDotState = when (status) {
    "running" -> StateDotState.Running
    "completed" -> StateDotState.Done
    "failed", "error", "cancelled" -> StateDotState.Error
    else -> StateDotState.Idle
}

// ---------------------------------------------------------------------------
// Subagents
// ---------------------------------------------------------------------------

internal fun subagentId(entry: SubagentListEntry): String? = when (entry) {
    is SubagentListEntry.ChildOneShot -> entry.id
    is SubagentListEntry.ChildContinuable -> entry.id
    is SubagentListEntry.Diagnostic -> entry.id
    else -> null
}

internal fun subagentLabel(entry: SubagentListEntry): String? = when (entry) {
    is SubagentListEntry.ChildOneShot -> entry.label
    is SubagentListEntry.ChildContinuable -> entry.label
    is SubagentListEntry.Diagnostic -> entry.reason
    else -> null
}

internal fun subagentRunning(entry: SubagentListEntry): Boolean = when (entry) {
    is SubagentListEntry.ChildOneShot -> entry.activity == "running" || entry.activity == "active"
    is SubagentListEntry.ChildContinuable -> entry.activity == "running" || entry.activity == "active"
    else -> false
}

// ---------------------------------------------------------------------------
// Agent presets
// ---------------------------------------------------------------------------

/**
 * Localized copy for the four presets the harness ships.
 *
 * The host does *not* localize these. It reads `name`/`description` straight out of each preset's
 * `preset.yml`, and those files are written in Chinese — so `agentPreset.list` answers `标准模式`
 * whatever language the client is in. The harness's own web UI covers this by overriding the
 * built-ins with its own translations, and this is the same table for the same four ids.
 *
 * Keyed on `trust == SYSTEM` exactly as the reference is: a preset someone wrote themselves and
 * happened to call `standard` is theirs, and keeps the name they gave it.
 */
private fun builtInPresetStrings(id: String): Pair<Int, Int>? = when (id) {
    "standard" -> R.string.preset_standard_name to R.string.preset_standard_desc
    "code" -> R.string.preset_code_name to R.string.preset_code_desc
    "minimal" -> R.string.preset_minimal_name to R.string.preset_minimal_desc
    "cordis" -> R.string.preset_cordis_name to R.string.preset_cordis_desc
    else -> null
}

private fun builtInPresetStrings(entry: AgentPresetEntry): Pair<Int, Int>? =
    if (entry.trust == AgentPresetTrust.SYSTEM) builtInPresetStrings(entry.id) else null

/**
 * What to call the preset a session is pinned to, given only its id.
 *
 * The roster is host-scoped and arrives on its own schedule, and the chip renders as soon as the
 * session does — so without this the top bar showed a raw wire id (`standard`) until something
 * happened to fetch the list. Falling back to the shipped name for a shipped id is right in every
 * deployment that has not replaced that preset, and corrects itself the moment the roster lands.
 */
@Composable
internal fun agentPresetLabel(id: String, roster: AgentPresetListValue?): String {
    val entry = roster?.presets?.firstOrNull { it.id == id }
    if (entry != null) return entry.displayName()
    return builtInPresetStrings(id)?.let { stringResource(it.first) } ?: id
}

/**
 * What to call a preset: the app's own translation for a shipped one, else whatever the deployment
 * named it, else the raw id.
 */
@Composable
internal fun AgentPresetEntry.displayName(): String =
    builtInPresetStrings(this)?.let { stringResource(it.first) }
        ?: name?.takeIf { it.isNotBlank() }
        ?: id

/** The preset's one-line summary, translated for the shipped four. Null when there is none. */
@Composable
internal fun AgentPresetEntry.displayDescription(): String? =
    builtInPresetStrings(this)?.let { stringResource(it.second) }
        ?: description?.takeIf { it.isNotBlank() }

// ---------------------------------------------------------------------------
// Shared field styling
// ---------------------------------------------------------------------------

@Composable
internal fun dialogTextFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = DsTheme.colors.bgLayer1,
    unfocusedContainerColor = DsTheme.colors.bgLayer1,
    focusedIndicatorColor = DsTheme.colors.accent,
    unfocusedIndicatorColor = DsTheme.colors.borderL2,
    cursorColor = DsTheme.colors.accent,
)
