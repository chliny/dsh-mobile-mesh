package dev.dsh.mobile.mesh.ui.screens.main

import dev.dsh.mobile.mesh.core.session.ToolResultNode
import dev.dsh.mobile.mesh.ui.components.ToolCardView
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCardInteractionTest {
    @Test
    fun `produced files retain one row per file without a mobile cap`() {
        val paths = (1..8).map { "outputs/file-$it.txt" }
        assertEquals(paths, producedFileRows(paths))
    }

    @Test
    fun `read and write cards expose a direct file target`() {
        val read = ToolCardView.ReadCard(label = "read", path = "README.md", totalLines = 12)
        val write = ToolCardView.DiffCard(diffs = listOf(dev.dsh.mobile.mesh.ui.components.DiffHunk("README.md", newText = "updated")))
        assertEquals("README.md", directFilePathForTool(ToolRowVariant.Read, read))
        assertEquals("README.md", directFilePathForTool(ToolRowVariant.Write, write))
    }

    @Test
    fun `failed edit exposes the complete tool error`() {
        val result = ToolResultNode(
            seq = 1,
            callId = "edit-1",
            content = buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", "Error: old_string and new_string must differ")
                })
            },
            isError = true,
            turn = 1,
            step = 1,
        )
        assertEquals("Error: old_string and new_string must differ", toolResultText(result))
    }

    @Test
    fun `edit diff keeps both sides in one hunk`() {
        val card = ToolCardView.DiffCard(
            diffs = listOf(dev.dsh.mobile.mesh.ui.components.DiffHunk("main.kt", oldText = "old", newText = "new")),
        )
        val hunk = card.diffs.single()
        assertTrue(hunk.oldText != null && hunk.newText != null)
    }
}
