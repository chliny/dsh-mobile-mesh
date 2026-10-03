package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.core.wire.dto.WorkspaceFileText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspacePreviewChunksTest {
    @Test fun `splits file pages at line boundaries within a bounded item size`() {
        val text = "alpha\nbeta\ngamma\n"
        val chunks = previewCodeChunks(text, maxChars = 8)
        assertEquals(text, chunks.joinToString(""))
        assertEquals(listOf("alpha\n", "beta\n", "gamma\n"), chunks)
    }

    @Test fun `long lines and empty files stay renderable without empty chunks`() {
        assertEquals(listOf(""), previewCodeChunks(""))
        val chunks = previewCodeChunks("a".repeat(20), maxChars = 8)
        assertEquals(listOf(8, 8, 4), chunks.map { it.length })
        assertTrue(chunks.none { it.isEmpty() })
    }

    @Test fun `preview state retains page chunks for lazy list rendering`() {
        val page = WorkspaceFileText("/work/main.ts", "v1", null, 1, "one\ntwo\n", 2, false)
        val state = PreviewState.Text(page, previewCodeChunks(page.text, maxChars = 4))
        assertEquals(page.text, state.chunks.joinToString(""))
        assertEquals(2, state.chunks.size)
    }
}
