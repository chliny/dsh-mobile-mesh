package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.core.wire.WireJson
import dev.dsh.mobile.mesh.core.wire.dto.JobFollowFrame
import dev.dsh.mobile.mesh.core.wire.dto.JobFollowFrameSerializer
import org.junit.Assert.assertEquals
import org.junit.Test

class JobFollowContractTest {
    @Test
    fun `opened output and terminal status preserve absolute byte cursors and loss markers`() {
        val opened = WireJson.decodeFromString(
            JobFollowFrameSerializer,
            """{"type":"opened","from":10,"job":{"id":"j","kind":"bash","label":"build","status":"running","startedAt":1,"output":{"earliest":8,"total":20}}}""",
        ) as JobFollowFrame.Opened
        assertEquals(10L, opened.from)

        val output = WireJson.decodeFromString(
            JobFollowFrameSerializer,
            """{"type":"output","chunks":[{"at":10,"text":"hello","channel":"stdout"}],"next":15,"lossy":true}""",
        ) as JobFollowFrame.Output
        assertEquals(15L, output.next)
        assertEquals(true, output.lossy)

        val status = WireJson.decodeFromString(
            JobFollowFrameSerializer,
            """{"type":"status","job":{"id":"j","kind":"bash","label":"build","status":"completed","startedAt":1,"finishedAt":2}}""",
        ) as JobFollowFrame.Status
        assertEquals("j", status.job.id)
    }
}
