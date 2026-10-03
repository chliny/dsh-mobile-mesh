package dev.dsh.mobile.mesh.ui.screens.main

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression guard: expanded turn-change rows pass source text and file path to syntax highlighting. */
class ChangesRowHighlightTest {
    @Test
    fun `expanded changed-file preview highlights source lines with the file grammar`() {
        val source = File("src/main/java/dev/dsh/mobile/mesh/ui/screens/main/ChangesRow.kt").readText()
        assertTrue(source.contains("KodeViewCode("))
        assertTrue(source.contains("code = source"))
        assertTrue(source.contains("pathOrLanguage = value.path"))
        assertTrue(source.contains("line.drop(1)"))
    }
}
