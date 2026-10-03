package dev.dsh.mobile.mesh.ui.components

import dev.snipme.highlights.model.SyntaxLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression guard: file previews and tool cards must resolve a Highlights language. */
class SyntaxHighlightTest {
    @Test
    fun `maps common file extensions to highlighting languages`() {
        assertEquals(SyntaxLanguage.KOTLIN, kodeviewLanguage("Main.kt"))
        assertEquals(SyntaxLanguage.TYPESCRIPT, kodeviewLanguage("app.tsx"))
        assertEquals(SyntaxLanguage.SHELL, kodeviewLanguage("deploy.sh"))
        assertEquals(SyntaxLanguage.PYTHON, kodeviewLanguage("main.py"))
        assertEquals(SyntaxLanguage.JAVASCRIPT, kodeviewLanguage("index.mjs"))
    }

    @Test
    fun `highlights common configuration and markup files without changing their content`() {
        val json = "{\"enabled\": true, \"label\": \"demo\"}"
        val yaml = "enabled: true # comment"
        val html = "<div class=\"note\">text</div>"
        for ((path, code) in listOf("config.json" to json, "settings.yaml" to yaml, "index.html" to html,
            "theme.css" to "color: red;", "Dockerfile" to "FROM base")) {
            val highlighted = highlightCode(code, path, darkMode = false)
            assertEquals(code, highlighted.text)
            assertTrue("No syntax spans for $path", highlighted.spanStyles.isNotEmpty())
        }
        val keyColor = highlightConfigCode(json, "config.json", darkMode = false).spanStyles.first().item.color
        val darkColor = highlightConfigCode(json, "config.json", darkMode = true).spanStyles.first().item.color
        assertNotEquals(keyColor, darkColor)
    }

    @Test
    fun `unknown and oversized code render without tokenization`() {
        assertTrue(highlightCode("plain text", "notes.unknown", false).spanStyles.isEmpty())
        assertTrue(highlightCode("x".repeat(16_385), "settings.json", false).spanStyles.isEmpty())
    }

    @Test
    fun `recognizes Web language extensions and aliases where native grammars exist`() {
        assertEquals(SyntaxLanguage.TYPESCRIPT, kodeviewLanguage("src/index.mts"))
        assertEquals(SyntaxLanguage.CPP, kodeviewLanguage("include/engine.hxx"))
        assertEquals(SyntaxLanguage.PYTHON, kodeviewLanguage("types.pyi"))
        assertTrue(highlightCode("SELECT id FROM users WHERE id = 1;", "query.sql", false).spanStyles.isNotEmpty())
    }

    @Test
    fun `falls back to default for unknown paths`() {
        assertEquals(SyntaxLanguage.DEFAULT, kodeviewLanguage("LICENSE"))
        assertEquals(SyntaxLanguage.DEFAULT, kodeviewLanguage("config.json"))
        assertEquals(SyntaxLanguage.DEFAULT, kodeviewLanguage(null))
    }
}
