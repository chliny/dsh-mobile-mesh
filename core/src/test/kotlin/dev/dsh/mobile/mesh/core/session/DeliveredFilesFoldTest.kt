package dev.dsh.mobile.mesh.core.session

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class DeliveredFilesFoldTest {
    @Test
    fun `presented files are folded with descriptions and blank paths removed`() {
        val event = SessionEventEnvelope(
            type = "deliverables/presented",
            seq = 4,
            time = 4,
            data = buildJsonObject {
                put("turn", 1)
                put("files", buildJsonArray {
                    add(buildJsonObject { put("path", "out/report.md"); put("description", "Report") })
                    add(buildJsonObject { put("path", " ") })
                })
            },
        )

        val node = EventFold("session").fold(listOf(event)).nodes.single() as PresentedFilesNode
        assertEquals("out/report.md", node.files.single().path)
        assertEquals("Report", node.files.single().description)
    }
}
