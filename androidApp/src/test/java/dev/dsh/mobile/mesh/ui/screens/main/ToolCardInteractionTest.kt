package dev.dsh.mobile.mesh.ui.screens.main

import dev.dsh.mobile.mesh.core.session.ToolResultNode
import dev.dsh.mobile.mesh.core.wire.dto.WorkspaceDirectoryEntry
import dev.dsh.mobile.mesh.ui.components.ToolCardView
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCardInteractionTest {
    @Test
    fun `mention matching remains active for the latest whitespace-delimited token`() {
        assertEquals("src/main.kt", mentionQueryForDraft("please inspect @src/main.kt"))
        assertEquals("src", mentionQueryForDraft("@src"))
        assertEquals("", mentionQueryForDraft("@"))
        assertEquals(null, mentionQueryForDraft("@src/main.kt "))
    }

    @Test
    fun `waiting indicator is shown while running without assistant content`() {
        assertTrue(waitingForFirstResponse(running = true, hasAssistant = false))
        assertTrue(!waitingForFirstResponse(running = true, hasAssistant = true))
        assertTrue(!waitingForFirstResponse(running = false, hasAssistant = false))
    }

    @Test
    fun `mention candidates preserve server order and only cap rendered rows`() {
        val candidates = listOf(WorkspaceDirectoryEntry("server-ranked.txt", "file")) +
            (1..9).map { WorkspaceDirectoryEntry("src/lib/File$it.kt", "file") }
        assertEquals("server-ranked.txt", matchingMentionFiles(candidates).first().name)
        assertEquals(8, matchingMentionFiles(candidates).size)
    }

    @Test
    fun `retry delay is displayed as rounded-up seconds`() {
        assertEquals(1L, retryDelaySeconds(1L))
        assertEquals(2L, retryDelaySeconds(1_001L))
        assertEquals(3L, retryDelaySeconds(3_000L))
    }

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
