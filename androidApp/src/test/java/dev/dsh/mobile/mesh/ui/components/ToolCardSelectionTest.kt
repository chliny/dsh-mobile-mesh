package dev.dsh.mobile.mesh.ui.components

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression guard: expanded tool bodies must remain selectable text, not painted output only. */
class ToolCardSelectionTest {
    @Test
    fun `tool card body is wrapped in a selection container`() {
        val source = File("src/main/java/dev/dsh/mobile/mesh/ui/components/ToolCards.kt").readText()
        val bodyStart = source.indexOf("private fun ToolCardBody")
        val bodyEnd = source.indexOf("private fun TerminalBody", bodyStart)
        val body = source.substring(bodyStart, bodyEnd)
        assertTrue(body.contains("SelectionContainer"))
    }

    @Test
    fun `chat transcript retains selection and observes the initial pull gesture`() {
        val source = File("src/main/java/dev/dsh/mobile/mesh/ui/screens/main/ChatTranscript.kt").readText()
        assertTrue(source.contains("SelectionContainer"))
        assertTrue(source.contains("awaitFirstDown"))
        assertTrue(source.contains("PointerEventPass.Initial"))
        assertTrue(source.contains("LazyColumn"))
    }
}
