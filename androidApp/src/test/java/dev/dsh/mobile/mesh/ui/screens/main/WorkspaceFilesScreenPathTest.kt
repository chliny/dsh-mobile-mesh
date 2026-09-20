package dev.dsh.mobile.mesh.ui.screens.main

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceFilesScreenPathTest {
    @Test
    fun `root child path never creates a leading empty segment`() {
        assertEquals("src", childWorkspacePath(".", "src"))
    }

    @Test
    fun `nested child path joins normalized server paths`() {
        assertEquals("src/main", childWorkspacePath("src/", "/main"))
    }

    @Test
    fun `blank child preserves the normalized parent`() {
        assertEquals(".", childWorkspacePath(".", " "))
        assertEquals("src", childWorkspacePath("src", " "))
    }
}
