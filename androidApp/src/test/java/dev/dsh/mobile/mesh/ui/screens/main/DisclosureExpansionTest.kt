package dev.dsh.mobile.mesh.ui.screens.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisclosureExpansionTest {
    @Test
    fun `transcript rows do not opt into sibling placement animation`() {
        // Expansion must be an in-place height change rather than a sibling placement animation.
        assertFalse(transcriptItemsAnimatePlacement())
        assertTrue(transcriptItemsAnimatePlacement() == false)
    }
}
