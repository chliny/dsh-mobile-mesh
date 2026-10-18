package dev.dsh.mobile.mesh.ui.screens.main

import dev.dsh.mobile.mesh.core.wire.dto.JobView
import dev.dsh.mobile.mesh.core.wire.dto.SubagentListEntry
import dev.dsh.mobile.mesh.core.wire.dto.SubagentTimingProjection

/** Activity supplied by the legacy row or current Session summary, without guessing from label text. */
internal fun subagentActivityRunning(entry: SubagentListEntry, sessionRunning: Boolean): Boolean = when (entry) {
    is SubagentListEntry.ChildOneShot -> entry.activity == "running" || entry.activity == "active"
    is SubagentListEntry.ChildContinuable -> entry.activity == "running" || entry.activity == "active"
    is SubagentListEntry.Projected -> sessionRunning
    is SubagentListEntry.Diagnostic, is dev.dsh.mobile.mesh.core.wire.dto.UnknownSubagentListEntry -> false
}

/** Host timestamp interval for a background job, using the local clock only while it is live. */
internal fun jobElapsedMs(job: JobView, nowMs: Long): Long =
    ((job.finishedAt ?: nowMs) - job.startedAt).coerceAtLeast(0L)

/**
 * Active-turn duration folded by the Host for a subagent Session.
 *
 * Completed turns contribute `settledMs`. For the open turn the Host-provided `through` cut is
 * authoritative unless the child is currently running, when the display clock advances locally.
 */
internal fun subagentElapsedMs(
    timing: SubagentTimingProjection,
    running: Boolean,
    nowMs: Long,
): Long {
    val active = timing.active ?: return timing.settledMs.coerceAtLeast(0L)
    val end = if (running) nowMs else active.through
    return timing.settledMs + (end - active.since).coerceAtLeast(0L)
}
