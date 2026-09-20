package dev.dsh.mobile.mesh.ui.screens.main

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceFilesNavigationTest {
    @Test
    fun `blank initial path is normalized to workspace root`() {
        assertEquals(".", normalizeWorkspaceFilesPath(""))
        assertEquals(".", normalizeWorkspaceFilesPath("   "))
        assertEquals("src", normalizeWorkspaceFilesPath("src"))
    }
}
