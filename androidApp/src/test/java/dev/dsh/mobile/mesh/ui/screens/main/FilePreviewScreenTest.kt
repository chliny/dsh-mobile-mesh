package dev.dsh.mobile.mesh.ui.screens.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilePreviewScreenTest {
    @Test
    fun `markdown extensions render as markdown`() {
        assertTrue(isMarkdownPath("README.md"))
        assertTrue(isMarkdownPath("docs/guide.markdown"))
        assertTrue(isMarkdownPath("notes.mdown"))
        assertTrue(isMarkdownPath("analysis.rmd"))
    }

    @Test
    fun `raw html images become markdown images and destination cleanup is preserved`() {
        val converted = dev.dsh.mobile.mesh.ui.components.markdownHtmlAsMarkdown(
            "<p>logo</p><img src=\"assets/logo.svg\" alt=\"logo\">",
        )
        assertEquals("logo\n\n![logo](assets/logo.svg)", converted.trim())
        assertEquals("assets/logo.svg", dev.dsh.mobile.mesh.ui.components.markdownImagePath("<assets/logo.svg>"))
        assertEquals("assets/logo.svg", dev.dsh.mobile.mesh.ui.components.markdownImagePath("assets/logo.svg?size=2#icon"))
        assertEquals(
            "https://cdn.example.com/logo.svg?signature=abc#icon",
            dev.dsh.mobile.mesh.ui.components.markdownImagePath("https://cdn.example.com/logo.svg?signature=abc#icon"),
        )
    }

    @Test
    fun `markdown relative links resolve from the current file directory`() {
        assertEquals("docs/setup.md", resolveMarkdownFileTarget("README.md", "docs/setup.md#install"))
        assertEquals("guide/next.md", resolveMarkdownFileTarget("guide/README.md", "next.md"))
        assertEquals("/workspace/shared.md", resolveMarkdownFileTarget("README.md", "/workspace/shared.md"))
        assertEquals(null, resolveMarkdownFileTarget("README.md", "https://example.com"))
    }

    @Test
    fun `file links preserve requested source line and use raw server page for markdown`() {
        val target = "../src/Main.kt#L401-L410"
        assertEquals("src/Main.kt", resolveMarkdownFileTarget("guide/README.md", target))
        assertEquals(401, markdownFileLineTarget(target)?.line)
        assertFalse(renderMarkdownPreview("guide/README.md", 401))
        assertTrue(renderMarkdownPreview("guide/README.md", null))
    }

    @Test
    fun `selected preview lines cite source line numbers and the exact excerpt`() {
        val lines = listOf("first", "second", "third")
        assertEquals("12–14", previewLineRangeLabel(12, 14))
        assertEquals("12", previewLineRangeLabel(12, 12))
        val target = previewCommentTarget(lines, lineOffset = 12, range = PreviewLineRange(0, 2))!!
        assertEquals(12, target.startLine)
        assertEquals(14, target.endLine)
        assertEquals("first\nsecond\nthird", target.excerpt)
        assertEquals(null, previewCommentTarget(lines, lineOffset = 12, range = PreviewLineRange(1, 3)))
    }

    @Test
    fun `non markdown files use syntax highlighting`() {
        assertFalse(isMarkdownPath("src/Main.kt"))
        assertFalse(isMarkdownPath("config.json"))
        assertFalse(isMarkdownPath("README"))
    }
}
