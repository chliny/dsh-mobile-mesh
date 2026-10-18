package dev.dsh.mobile.mesh.ui.screens.main

import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.core.wire.dto.JobOutputView
import dev.dsh.mobile.mesh.core.wire.dto.JobStatus
import dev.dsh.mobile.mesh.core.wire.dto.JobView
import dev.dsh.mobile.mesh.core.wire.dto.SubagentCatalogProjectionEntry
import dev.dsh.mobile.mesh.core.wire.dto.SubagentListEntry
import dev.dsh.mobile.mesh.core.wire.dto.UnknownSubagentListEntry
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Jobs have observable output; only a real child Session has a navigable transcript. */
class WorkCatalogNavigationTest {
    private fun job(status: JobStatus, retained: Long = 0) = JobView(
        id = "job-a", kind = "bash", label = "Build", status = status, startedAt = 1L,
        output = JobOutputView(total = retained),
    )

    @Test fun `live job opens its Host output stream even before bytes arrive`() {
        assertTrue(canObserveJob(job(JobStatus.RUNNING)))
        assertTrue(canObserveJob(job(JobStatus.STOPPING)))
    }

    @Test fun `settled job observes only retained output while metadata remains visible`() {
        assertTrue(canObserveJob(job(JobStatus.COMPLETED, retained = 12)))
        assertFalse(canObserveJob(job(JobStatus.COMPLETED)))
        assertFalse(canObserveJob(job(JobStatus.KILLED)))
        assertFalse(canObserveJob(job(JobStatus.FAILED)))
    }

    @Test fun `diagnostic and external work cannot navigate as subagent Sessions`() {
        assertFalse(canOpenSubagentTranscript(SubagentListEntry.Diagnostic("child-a", "unreadable")))
        assertFalse(canOpenSubagentTranscript(UnknownSubagentListEntry("future", JsonObject(emptyMap()))))
        assertFalse(canOpenSubagentTranscript(projected("external")))
        assertFalse(canOpenSubagentTranscript(projected("unknown")))
    }

    @Test fun `real one shot and continuable children remain navigable`() {
        assertTrue(canOpenSubagentTranscript(SubagentListEntry.ChildOneShot("child-a", "running", false)))
        assertTrue(canOpenSubagentTranscript(SubagentListEntry.ChildContinuable("child-b", "inactive", false, label = "Helper")))
        assertTrue(canOpenSubagentTranscript(projected("one-shot")))
        assertTrue(canOpenSubagentTranscript(projected("continuable")))
    }

    @Test fun `background statuses use existing localized labels`() {
        assertEquals(R.string.jobs_running, jobStatusLabelRes(JobStatus.RUNNING))
        assertEquals(R.string.jobs_kill_requested, jobStatusLabelRes(JobStatus.STOPPING))
        assertEquals(R.string.jobs_completed, jobStatusLabelRes(JobStatus.COMPLETED))
        assertEquals(R.string.jobs_killed, jobStatusLabelRes(JobStatus.KILLED))
        assertEquals(R.string.jobs_failed, jobStatusLabelRes(JobStatus.FAILED))
    }

    private fun projected(mode: String) = SubagentListEntry.Projected(
        SubagentCatalogProjectionEntry(id = "child-a", createdAt = 1L, mode = mode),
    )
}
