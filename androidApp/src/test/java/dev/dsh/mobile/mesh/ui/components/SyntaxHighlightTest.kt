package dev.dsh.mobile.mesh.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class SyntaxHighlightTest {
    @Test
    fun `maps common file extensions to highlighting languages`() {
        assertEquals("kotlin", syntaxLanguage("Main.kt"))
        assertEquals("javascript", syntaxLanguage("app.tsx"))
        assertEquals("json", syntaxLanguage("config.json"))
        assertEquals("markup", syntaxLanguage("layout.xml"))
        assertEquals("plain", syntaxLanguage("LICENSE"))
    }
}
