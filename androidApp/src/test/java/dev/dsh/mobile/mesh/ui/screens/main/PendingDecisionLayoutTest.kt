package dev.dsh.mobile.mesh.ui.screens.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PendingDecisionLayoutTest {
    @Test
    fun `pending decision replaces composer and docks instead of competing for screen height`() {
        val source = File("src/main/java/dev/dsh/mobile/mesh/ui/screens/main/ChatScreen.kt").readText()
        assertTrue(source.contains("if (!decisionPending) conversation?.let"))
        assertTrue(source.contains("if (!decisionPending) Composer("))
        assertTrue(source.contains("if (!decisionPending) StatsFooter("))
        assertTrue(source.indexOf("if (questions != null)") < source.indexOf("if (!decisionPending) Composer("))
    }

    @Test
    fun `the complete question and detail remain in the scrollable body above fixed actions`() {
        val source = File("src/main/java/dev/dsh/mobile/mesh/ui/components/QuestionComposer.kt").readText()
        val body = source.substringAfter("private fun QuestionBody(").substringBefore("private fun OptionRow(")
        assertTrue(body.contains("Text(question.question"))
        assertTrue(body.contains("MarkdownText(it)"))
        assertTrue(source.indexOf(".verticalScroll(bodyScroll)") < source.indexOf("QuestionFooter(", source.indexOf(".verticalScroll(bodyScroll)")))
    }

    @Test
    fun `long question header cannot displace the action footer`() {
        val source = File("src/main/java/dev/dsh/mobile/mesh/ui/components/QuestionComposer.kt").readText()
        val header = source.substringAfter("private fun QuestionHeader(").substringBefore("private fun QuestionBody(")
        assertTrue(header.contains("maxLines = 2"))
        assertFalse(header.contains("maxLines = if (minimized) 2 else Int.MAX_VALUE"))
    }
}
