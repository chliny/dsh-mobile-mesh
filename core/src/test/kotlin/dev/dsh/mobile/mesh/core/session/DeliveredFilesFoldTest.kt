package dev.dsh.mobile.mesh.core.session

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class DeliveredFilesFoldTest {
    @Test
    fun `presented files preserve server paths and remove only empty names`() {
        val event = SessionEventEnvelope(
            type = "deliverables/presented",
            seq = 4,
            time = 4,
            data = buildJsonObject {
                put("turn", 1)
                put("files", buildJsonArray {
                    add(buildJsonObject { put("path", "out/report.md"); put("description", "Report") })
                    add(buildJsonObject { put("path", " out/report with spaces .md "); put("description", "Exact path") })
                    add(buildJsonObject { put("path", " ") })
                    add(buildJsonObject { put("path", "") })
                })
            },
        )

        val node = EventFold("session").fold(listOf(event)).nodes.single() as PresentedFilesNode
        assertEquals(listOf("out/report.md", " out/report with spaces .md ", " "), node.files.map { it.path })
        assertEquals(listOf("Report", "Exact path", null), node.files.map { it.description })
    }
}
