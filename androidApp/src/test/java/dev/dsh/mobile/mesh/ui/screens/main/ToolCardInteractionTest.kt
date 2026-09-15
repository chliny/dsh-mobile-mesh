package dev.dsh.mobile.mesh.ui.screens.main

import dev.dsh.mobile.mesh.core.session.ToolCallNode
import dev.dsh.mobile.mesh.ui.components.ToolCardView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCardInteractionTest {
    @Test
    fun `read and write cards expose a direct file target`() {
        val read = ToolCardView.ReadCard(label = "read", path = "README.md", totalLines = 12)
        val write = ToolCardView.DiffCard(diffs = listOf(dev.dsh.mobile.mesh.ui.components.DiffHunk("README.md", newText = "updated")))
        assertEquals("README.md", directFilePathForTool(ToolRowVariant.Read, read))
        assertEquals("README.md", directFilePathForTool(ToolRowVariant.Write, write))
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
