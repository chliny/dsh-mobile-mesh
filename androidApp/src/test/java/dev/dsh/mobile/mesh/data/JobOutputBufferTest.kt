package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.core.session.JobOutputBuffer
import dev.dsh.mobile.mesh.core.wire.dto.JobChunk
import dev.dsh.mobile.mesh.core.wire.dto.JobFollowFrame
import dev.dsh.mobile.mesh.core.wire.dto.JobOutputView
import dev.dsh.mobile.mesh.core.wire.dto.JobStatus
import dev.dsh.mobile.mesh.core.wire.dto.JobView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JobOutputBufferTest {
    private fun job(status: JobStatus = JobStatus.RUNNING, earliest: Long = 0) = JobView(
        id = "j1", kind = "bash", label = "build", status = status, startedAt = 1,
        output = JobOutputView(earliest = earliest),
    )

    @Test
    fun `opened resumes from cursor and output advances byte offset`() {
        val buffer = JobOutputBuffer()
        buffer.apply(JobFollowFrame.Opened(job = job(), from = 6))
        assertEquals(6L, buffer.snapshot.cursor)
        buffer.apply(JobFollowFrame.Output(chunks = listOf(JobChunk(at = 6, text = "abc")), next = 9))
        assertEquals("abc", buffer.snapshot.text)
        assertEquals(9L, buffer.snapshot.cursor)
        assertTrue(buffer.snapshot.gapBefore)
    }

    @Test
    fun `fresh follow at retained head reports prior output gap`() {
        val buffer = JobOutputBuffer()
        buffer.apply(JobFollowFrame.Opened(job = job(earliest = 5), from = 5))
        assertTrue(buffer.snapshot.gapBefore)
    }

    @Test
    fun `local render truncation reports omitted output gap`() {
        val buffer = JobOutputBuffer(maxChars = 4)
        buffer.apply(JobFollowFrame.Opened(job = job(), from = 0))
        buffer.apply(JobFollowFrame.Output(chunks = listOf(JobChunk(at = 0, text = "123456")), next = 6))
        assertEquals("3456", buffer.snapshot.text)
        assertTrue(buffer.snapshot.gapBefore)
    }

    @Test
    fun `lossy and gap markers stay visible`() {
        val buffer = JobOutputBuffer()
        buffer.apply(JobFollowFrame.Opened(job = job(earliest = 5), from = 0))
        assertTrue(buffer.snapshot.gapBefore)
        buffer.apply(JobFollowFrame.Output(chunks = emptyList(), next = 5, lossy = true))
        assertTrue(buffer.snapshot.lossy)
        buffer.apply(JobFollowFrame.Output(chunks = listOf(JobChunk(at = 5, text = "x", gapBefore = true)), next = 6))
        assertTrue(buffer.snapshot.gapBefore)
    }

    @Test
    fun `output remains bounded and status updates job projection`() {
        val buffer = JobOutputBuffer(maxChars = 4)
        buffer.apply(JobFollowFrame.Opened(job = job(), from = 0))
        buffer.apply(JobFollowFrame.Output(chunks = listOf(JobChunk(at = 0, text = "123456")), next = 6))
        assertEquals("3456", buffer.snapshot.text)
        assertTrue(buffer.snapshot.gapBefore)
        buffer.apply(JobFollowFrame.Status(job = job(JobStatus.COMPLETED)))
        assertEquals(JobStatus.COMPLETED, buffer.snapshot.job?.status)
    }
}
