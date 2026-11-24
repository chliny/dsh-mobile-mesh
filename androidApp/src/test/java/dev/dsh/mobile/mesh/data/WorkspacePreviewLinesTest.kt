package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.core.wire.dto.WorkspaceFileText
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspacePreviewLinesTest {
    @Test
    fun `page text maps one to one onto displayed lines`() {
        assertEquals(listOf("alpha", "beta", "gamma"), previewPageLines("alpha\nbeta\ngamma"))
        assertEquals(listOf("alpha", ""), previewPageLines("alpha\n"))
        assertEquals(emptyList<String>(), previewPageLines(""))
    }

    @Test
    fun `a page holding only a blank line still shows that line`() {
        assertEquals(listOf(""), previewPageLines("", declaredLines = 1))
        assertEquals(emptyList<String>(), previewPageLines("", declaredLines = 0))
    }

    @Test
    fun `crlf and lone carriage returns do not leak into lines`() {
        assertEquals(listOf("alpha", "beta"), previewPageLines("alpha\r\nbeta"))
        assertEquals(listOf("alpha", "beta"), previewPageLines("alpha\rbeta"))
    }

    @Test
    fun `whole file lines do not invent a final empty line`() {
        assertEquals(listOf("alpha", "beta"), workspaceFileLines("alpha\nbeta\n"))
        assertEquals(listOf("alpha", ""), workspaceFileLines("alpha\n\n"))
        assertEquals(listOf("alpha"), workspaceFileLines("alpha"))
        assertEquals(emptyList<String>(), workspaceFileLines(""))
    }

    @Test
    fun `page boundaries keep adjacent lines separate`() {
        assertEquals("a\nb\nc\nd", appendWorkspacePageText("a\nb", "c\nd"))
        // A page whose last line is blank still needs one boundary newline before the next page.
        assertEquals(listOf("a", "", "", "c"), previewPageLines(appendWorkspacePageText("a\n", "\nc")))
        assertEquals("next", appendWorkspacePageText("", "next"))
        assertEquals("first", appendWorkspacePageText("first", ""))
    }

    @Test
    fun `preview state derives display lines from the server page`() {
        val page = WorkspaceFileText("/work/main.ts", "v1", null, 1, "one\ntwo", 2, false)
        val state = PreviewState.Text(page)
        assertEquals(listOf("one", "two"), state.displayLines)
    }

    @Test
    fun `line offset is preserved for pages opened at a source line`() {
        val page = WorkspaceFileText("/work/main.ts", "v1", null, 41, "forty-one\nforty-two", 2, false)
        val state = PreviewState.Text(page)
        assertEquals(41, state.value.offset)
        assertEquals(listOf("forty-one", "forty-two"), state.displayLines)
    }
}
