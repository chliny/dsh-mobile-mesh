package dev.dsh.mobile.mesh.ui.components

import dev.snipme.highlights.model.SyntaxLanguage
import org.junit.Assert.assertEquals
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
    fun `falls back to default for unknown paths`() {
        assertEquals(SyntaxLanguage.DEFAULT, kodeviewLanguage("LICENSE"))
        assertEquals(SyntaxLanguage.DEFAULT, kodeviewLanguage("config.json"))
        assertEquals(SyntaxLanguage.DEFAULT, kodeviewLanguage(null))
    }
}
