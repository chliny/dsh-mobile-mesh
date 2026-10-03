package dev.dsh.mobile.mesh.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingRowExpandableContentTest {
    @Test
    fun `thinking row is not expandable without visible reasoning text`() {
        assertFalse(hasThinkingContent(null))
        assertFalse(hasThinkingContent(""))
        assertFalse(hasThinkingContent(" \n  "))
    }

    @Test
    fun `thinking row is expandable when reasoning text exists`() {
        assertTrue(hasThinkingContent("analysis details"))
    }
}
