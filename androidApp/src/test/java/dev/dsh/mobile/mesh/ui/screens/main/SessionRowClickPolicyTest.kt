package dev.dsh.mobile.mesh.ui.screens.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRowClickPolicyTest {
    @Test
    fun `parent row click expands when it has collapsed children`() {
        assertTrue(shouldExpandChildrenOnSessionClick(childCount = 1, childrenExpanded = false))
    }

    @Test
    fun `parent row click does not re-expand already expanded children`() {
        assertFalse(shouldExpandChildrenOnSessionClick(childCount = 1, childrenExpanded = true))
    }

    @Test
    fun `leaf row click never expands`() {
        assertFalse(shouldExpandChildrenOnSessionClick(childCount = 0, childrenExpanded = false))
    }
}
