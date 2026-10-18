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
    fun `svg image paths distinguish absolute from document-relative references`() {
        assertEquals("guide/assets/logo.svg", markdownSvgWorkspacePath("guide/README.md", "assets/logo.svg"))
        assertEquals("/workspace/shared.svg", markdownSvgWorkspacePath("guide/README.md", "/workspace/shared.svg"))
        assertEquals("C:/work/shared.svg", markdownSvgWorkspacePath("guide/README.md", "C:\\work\\shared.svg"))
        assertEquals(null, markdownSvgWorkspacePath("guide/README.md", "assets/photo.png"))
        assertEquals(null, markdownSvgWorkspacePath("guide/README.md", "https://example.com/logo.svg"))
        assertEquals(null, markdownSvgWorkspacePath("guide/README.md", "HTTPS://example.com/logo.svg?signature=abc"))
    }

    @Test
    fun `non markdown files use syntax highlighting`() {
        assertFalse(isMarkdownPath("src/Main.kt"))
        assertFalse(isMarkdownPath("config.json"))
        assertFalse(isMarkdownPath("README"))
    }
}
