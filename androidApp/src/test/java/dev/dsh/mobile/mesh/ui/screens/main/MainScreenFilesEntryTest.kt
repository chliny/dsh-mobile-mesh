package dev.dsh.mobile.mesh.ui.screens.main

import org.junit.Assert.assertEquals
import org.junit.Test

class MainScreenFilesEntryTest {
    @Test
    fun `files entry always starts at workspace root`() {
        assertEquals(".", normalizeWorkspaceFilesPath("."))
        assertEquals(".", normalizeWorkspaceFilesPath(""))
    }
}
