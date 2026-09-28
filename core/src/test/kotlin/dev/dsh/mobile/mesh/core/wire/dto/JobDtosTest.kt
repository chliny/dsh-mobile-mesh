package dev.dsh.mobile.mesh.core.wire.dto

import dev.dsh.mobile.mesh.core.wire.WireJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JobDtosTest {
    @Test
    fun `job list rows decode open producer fields and whole-set roster`() {
        val frame = WireJson.decodeFromString(
            JobListFrameSerializer,
            """{"type":"rows","jobs":[{"id":"bash-1","kind":"bash","label":"build","status":"running","startedAt":12,"owner":"s1","progress":"2/4","output":{"total":8,"earliest":2,"spillPaths":["/tmp/out"]},"producerExtra":true}]}""",
        )
        assertEquals("bash-1", frame.jobs.single().id)
        assertEquals("2/4", frame.jobs.single().progress)
        assertEquals(8L, frame.jobs.single().output.total)
        assertEquals(listOf("/tmp/out"), frame.jobs.single().output.spillPaths)
    }

    @Test
    fun `job follow decodes opened output status and preserves unknown frame`() {
        val opened = WireJson.decodeFromString(
            JobFollowFrameSerializer,
            """{"type":"opened","job":{"id":"bash-1","kind":"bash","label":"build","status":"running","startedAt":12},"from":3}""",
        ) as JobFollowFrame.Opened
        assertEquals(3L, opened.from)

        val output = WireJson.decodeFromString(
            JobFollowFrameSerializer,
            """{"type":"output","chunks":[{"at":3,"text":"ok","channel":"log","gapBefore":true}],"next":5,"lossy":true}""",
        ) as JobFollowFrame.Output
        assertEquals("log", output.chunks.single().channel)
        assertTrue(output.chunks.single().gapBefore == true)

        val status = WireJson.decodeFromString(
            JobFollowFrameSerializer,
            """{"type":"status","job":{"id":"bash-1","kind":"bash","label":"build","status":"completed","startedAt":12,"finishedAt":20}}""",
        ) as JobFollowFrame.Status
        assertEquals(JobStatus.COMPLETED, status.job.status)

        val unknown = WireJson.decodeFromString(JobFollowFrameSerializer, """{"type":"future","v":1}""")
        assertTrue(unknown is JobFollowFrame.Unknown)
    }
}
