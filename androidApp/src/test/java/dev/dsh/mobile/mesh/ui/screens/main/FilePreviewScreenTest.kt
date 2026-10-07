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
    fun `CommonMark renders markdown and raw html images`() {
        val markdownImage = dev.dsh.mobile.mesh.ui.components.renderCommonMarkFragment("![logo](assets/logo.svg)")
        val htmlImage = dev.dsh.mobile.mesh.ui.components.renderCommonMarkFragment("<img src=\"assets/logo.svg\" alt=\"logo\">")

        assertTrue(markdownImage.contains("<img src=\"assets/logo.svg\" alt=\"logo\""))
        assertTrue(htmlImage.contains("<img src=\"assets/logo.svg\" alt=\"logo\">"))
        assertEquals("assets/logo.svg", dev.dsh.mobile.mesh.ui.components.markdownImagePath("<assets/logo.svg>"))
    }

    @Test
    fun `markdown relative links resolve from the current file directory`() {
        assertEquals("docs/setup.md", resolveMarkdownFileTarget("README.md", "docs/setup.md#install"))
        assertEquals("guide/next.md", resolveMarkdownFileTarget("guide/README.md", "next.md"))
        assertEquals("/workspace/shared.md", resolveMarkdownFileTarget("README.md", "/workspace/shared.md"))
        assertEquals(null, resolveMarkdownFileTarget("README.md", "https://example.com"))
    }

    @Test
    fun `non markdown files use syntax highlighting`() {
        assertFalse(isMarkdownPath("src/Main.kt"))
        assertFalse(isMarkdownPath("config.json"))
        assertFalse(isMarkdownPath("README"))
    }
}
