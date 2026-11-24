package dev.dsh.mobile.mesh.ui.screens.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.core.session.JobOutputSnapshot
import dev.dsh.mobile.mesh.core.wire.dto.JobStatus
import dev.dsh.mobile.mesh.core.wire.dto.JobView
import dev.dsh.mobile.mesh.ui.components.AppBottomSheet
import dev.dsh.mobile.mesh.ui.components.StateDot
import dev.dsh.mobile.mesh.ui.components.formatDurationMs
import dev.dsh.mobile.mesh.ui.theme.DsSpacing

/** The Host permits output observation for live jobs and settled jobs with retained bytes. */
internal fun canObserveJob(job: JobView): Boolean =
    job.status == JobStatus.RUNNING || job.status == JobStatus.STOPPING || job.output.total > 0

/** A separate background-job entry point; a row opens its Host-backed metadata and output. */
@Composable
internal fun JobsSheet(
    sessionId: String,
    jobs: List<JobView>,
    outputById: Map<String, JobOutputSnapshot>,
    onObserve: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedId by remember(sessionId) { mutableStateOf<String?>(null) }
    val selectedJob = jobs.firstOrNull { it.id == selectedId }
    val observableId = selectedJob?.takeIf(::canObserveJob)?.id
    var nowMs by remember(sessionId) { mutableLongStateOf(System.currentTimeMillis()) }
    val hasLiveJobs = jobs.any { it.status == JobStatus.RUNNING || it.status == JobStatus.STOPPING }
    LaunchedEffect(sessionId, hasLiveJobs) {
        if (!hasLiveJobs) return@LaunchedEffect
        while (true) {
            nowMs = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000L)
        }
    }
    val currentObserve by rememberUpdatedState(onObserve)
    // `job/follow` is opened only while the selected row is visible. Closing the sheet or
    // changing rows stops the stream; `job/list` remains the server-owned roster.
    DisposableEffect(sessionId, observableId) {
        observableId?.let { currentObserve(it, true) }
        onDispose { observableId?.let { currentObserve(it, false) } }
    }
    LaunchedEffect(jobs, selectedId) {
        if (selectedId != null && selectedJob == null) selectedId = null
    }
    AppBottomSheet(
        title = stringResource(R.string.jobs_title),
        subtitle = jobs.size.takeIf { it > 0 }?.toString(),
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
        ) {
            if (jobs.isEmpty()) {
                Text(stringResource(R.string.jobs_empty), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            jobs.forEach { job ->
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { selectedId = if (selectedId == job.id) null else job.id }
                            .padding(vertical = DsSpacing.xsmall),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StateDot(jobStatusDot(job.status))
                        Spacer(Modifier.width(DsSpacing.small))
                        Column(Modifier.weight(1f)) {
                            Text(
                                job.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                listOfNotNull(job.kind, job.progress ?: job.detail, formatDurationMs(jobElapsedMs(job, nowMs)))
                                    .joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            stringResource(jobStatusLabelRes(job.status)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (selectedId == job.id) {
                        val output = outputById[job.id]
                        Column(Modifier.fillMaxWidth().padding(start = DsSpacing.large, bottom = DsSpacing.small)) {
                            if (output?.gapBefore == true) {
                                Text(stringResource(R.string.jobs_output_gap), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (output?.lossy == true) {
                                Text(stringResource(R.string.jobs_output_lossy), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            output?.error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            output?.text?.takeIf { it.isNotEmpty() }?.let { text ->
                                Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            // Settled jobs with no retained output still expose the Host's final reason.
                            if (!canObserveJob(job)) job.detail?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
