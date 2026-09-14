package dev.dsh.mobile.mesh.ui.screens.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import dev.dsh.mobile.mesh.ui.components.HTML_IMAGE_REGEX
import dev.dsh.mobile.mesh.ui.components.IMAGE_REGEX
import org.junit.Test

class FilePreviewScreenTest {
    @Test
    fun `markdown extensions render as markdown`() {
        assertTrue(isMarkdownPath("README.md"))
        assertTrue(isMarkdownPath("docs/guide.markdown"))
        assertTrue(isMarkdownPath("notes.mdown"))
    }

    @Test
    fun `markdown image syntax recognizes svg and html images`() {
        assertTrue(IMAGE_REGEX.matches("![logo](assets/logo.svg)"))
        assertTrue(HTML_IMAGE_REGEX.containsMatchIn("<img src=\"assets/logo.svg\" alt=\"logo\">"))
        assertEquals("assets/logo.svg", dev.dsh.mobile.mesh.ui.components.markdownImagePath("<assets/logo.svg>"))
    }

    @Test
    fun `non markdown files use syntax highlighting`() {
        assertFalse(isMarkdownPath("src/Main.kt"))
        assertFalse(isMarkdownPath("config.json"))
        assertFalse(isMarkdownPath("README"))
    }
}
