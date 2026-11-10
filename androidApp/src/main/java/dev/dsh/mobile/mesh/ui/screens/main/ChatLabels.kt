package dev.dsh.mobile.mesh.ui.screens.main

import androidx.annotation.StringRes
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.core.wire.dto.AgentPresetEntry
import dev.dsh.mobile.mesh.core.wire.dto.AgentPresetListValue
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

@StringRes
internal fun jobStatusLabelRes(status: JobStatus): Int = when (status) {
    JobStatus.RUNNING -> R.string.jobs_running
    JobStatus.STOPPING -> R.string.jobs_kill_requested
    JobStatus.COMPLETED -> R.string.jobs_completed
    JobStatus.KILLED -> R.string.jobs_killed
    JobStatus.FAILED -> R.string.jobs_failed
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
    is SubagentListEntry.Projected -> entry.entry.id
    is SubagentListEntry.Diagnostic -> entry.id
    else -> null
}

internal fun subagentLabel(entry: SubagentListEntry): String? = when (entry) {
    is SubagentListEntry.ChildOneShot -> entry.label
    is SubagentListEntry.ChildContinuable -> entry.label
    is SubagentListEntry.Projected -> entry.entry.label
    is SubagentListEntry.Diagnostic -> entry.reason
    else -> null
}

/** Host-provided display name; session operations continue to use the catalog id. */
internal fun subagentName(entry: SubagentListEntry): String? = when (entry) {
    is SubagentListEntry.ChildContinuable -> entry.name
    is SubagentListEntry.Projected -> entry.entry.name
    else -> null
}?.takeIf { it.isNotBlank() }

internal fun canOpenSubagentTranscript(entry: SubagentListEntry): Boolean = when (entry) {
    is SubagentListEntry.ChildOneShot, is SubagentListEntry.ChildContinuable -> true
    is SubagentListEntry.Projected -> entry.entry.mode == "one-shot" || entry.entry.mode == "continuable"
    is SubagentListEntry.Diagnostic, is dev.dsh.mobile.mesh.core.wire.dto.UnknownSubagentListEntry -> false
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
 * Current Host rosters leave a shipped preset's name unset; an explicitly named row owns its
 * own copy and must not be translated even when it reuses a shipped id. The old `code` id remains
 * supported alongside the current Web `ptc` id.
 */
private fun builtInPresetStrings(id: String): Pair<Int, Int>? = when (id) {
    "standard" -> R.string.preset_standard_name to R.string.preset_standard_desc
    "ptc" -> R.string.preset_code_name to R.string.preset_code_desc
    "code" -> R.string.preset_code_name to R.string.preset_code_desc
    "minimal" -> R.string.preset_minimal_name to R.string.preset_minimal_desc
    "cordis" -> R.string.preset_cordis_name to R.string.preset_cordis_desc
    else -> null
}

internal fun AgentPresetEntry.isShippedPreset(): Boolean =
    name == null && builtInPresetStrings(id) != null

private fun builtInPresetStrings(entry: AgentPresetEntry): Pair<Int, Int>? =
    if (entry.isShippedPreset()) builtInPresetStrings(entry.id) else null

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
