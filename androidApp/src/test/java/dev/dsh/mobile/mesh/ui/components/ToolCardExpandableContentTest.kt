package dev.dsh.mobile.mesh.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCardExpandableContentTest {
    @Test
    fun `empty generic card has no expandable content`() {
        assertFalse(ToolCardView.GenericCard(title = "unknown tool").hasExpandableContent())
    }

    @Test
    fun `generic card with raw input has expandable content`() {
        assertTrue(ToolCardView.GenericCard(rawInput = "{}").hasExpandableContent())
    }

    @Test
    fun `blank generic input is not expandable`() {
        assertFalse(ToolCardView.GenericCard(rawInput = "  ").hasExpandableContent())
    }

    @Test
    fun `empty generic output blocks are not expandable`() {
        val card = ToolCardView.GenericCard(
            locations = listOf(" "),
            content = listOf(ContentBlockView.TextBlock("\n  ")),
        )
        assertFalse(card.hasExpandableContent())
    }
}
