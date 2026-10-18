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
    fun `reopen after retained head advances drops stale output prefix`() {
        val buffer = JobOutputBuffer()
        buffer.apply(JobFollowFrame.Opened(job = job(), from = 0))
        buffer.apply(JobFollowFrame.Output(chunks = listOf(JobChunk(at = 0, text = "old")), next = 3))
        buffer.apply(JobFollowFrame.Opened(job = job(earliest = 5), from = 3))
        assertEquals("", buffer.snapshot.text)
        assertEquals(5L, buffer.snapshot.cursor)
        assertTrue(buffer.snapshot.gapBefore)
        assertTrue(buffer.snapshot.lossy)
        buffer.apply(JobFollowFrame.Output(chunks = listOf(JobChunk(at = 5, text = "new")), next = 8))
        assertEquals("new", buffer.snapshot.text)
    }

    @Test
    fun `reopen past the last accepted cursor keeps only the new contiguous suffix`() {
        val buffer = JobOutputBuffer()
        buffer.apply(JobFollowFrame.Opened(job = job(), from = 0))
        buffer.apply(JobFollowFrame.Output(chunks = listOf(JobChunk(at = 0, text = "old")), next = 3))
        buffer.apply(JobFollowFrame.Opened(job = job(), from = 5))
        assertEquals("", buffer.snapshot.text)
        assertEquals(5L, buffer.snapshot.cursor)
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
    fun `bounded output never starts with half of a surrogate pair`() {
        val buffer = JobOutputBuffer(maxChars = 2)
        buffer.apply(JobFollowFrame.Opened(job = job(), from = 0))
        buffer.apply(JobFollowFrame.Output(chunks = listOf(JobChunk(at = 0, text = "😀X")), next = 5))
        assertEquals("X", buffer.snapshot.text)
        assertTrue(buffer.snapshot.gapBefore)
        buffer.apply(JobFollowFrame.Output(chunks = listOf(JobChunk(at = 5, text = "Y")), next = 6))
        assertEquals("XY", buffer.snapshot.text)
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
    fun `output gap does not join old and new text as a continuous log`() {
        val buffer = JobOutputBuffer()
        buffer.apply(JobFollowFrame.Opened(job = job(), from = 0))
        buffer.apply(JobFollowFrame.Output(chunks = listOf(JobChunk(at = 0, text = "old")), next = 3))
        buffer.apply(JobFollowFrame.Output(chunks = listOf(JobChunk(at = 5, text = "new")), next = 8))
        assertEquals("new", buffer.snapshot.text)
        assertTrue(buffer.snapshot.gapBefore)
    }

    @Test
    fun `empty output frame advancing cursor cannot join separated text`() {
        val buffer = JobOutputBuffer()
        buffer.apply(JobFollowFrame.Opened(job = job(), from = 0))
        buffer.apply(JobFollowFrame.Output(chunks = listOf(JobChunk(at = 0, text = "old")), next = 3))
        buffer.apply(JobFollowFrame.Output(chunks = emptyList(), next = 5, lossy = true))
        assertEquals("", buffer.snapshot.text)
        assertEquals(5L, buffer.snapshot.cursor)
        assertTrue(buffer.snapshot.gapBefore)
        buffer.apply(JobFollowFrame.Output(chunks = listOf(JobChunk(at = 5, text = "new")), next = 8))
        assertEquals("new", buffer.snapshot.text)
    }

    @Test
    fun `empty frame without cursor movement preserves current output`() {
        val buffer = JobOutputBuffer()
        buffer.apply(JobFollowFrame.Opened(job = job(), from = 0))
        buffer.apply(JobFollowFrame.Output(chunks = listOf(JobChunk(at = 0, text = "old")), next = 3))
        buffer.apply(JobFollowFrame.Output(chunks = emptyList(), next = 3))
        assertEquals("old", buffer.snapshot.text)
        assertFalse(buffer.snapshot.gapBefore)
    }

    @Test
    fun `gap inside one frame only shows the contiguous output suffix`() {
        val buffer = JobOutputBuffer()
        buffer.apply(JobFollowFrame.Opened(job = job(), from = 0))
        buffer.apply(JobFollowFrame.Output(chunks = listOf(JobChunk(at = 0, text = "old")), next = 3))
        buffer.apply(JobFollowFrame.Output(chunks = listOf(
            JobChunk(at = 3, text = "middle"),
            JobChunk(at = 20, text = "tail", gapBefore = true),
        ), next = 24))
        assertEquals("tail", buffer.snapshot.text)
        assertTrue(buffer.snapshot.gapBefore)
        assertTrue(buffer.snapshot.lossy)
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
