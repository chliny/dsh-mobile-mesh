package dev.dsh.mobile.mesh.ui.screens.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainScreenTest {
    @Test
    fun `workspace files use workspace path when session cwd is absent`() {
        assertEquals("/home/me/project", workspaceFilesRootPath(null, "/home/me/project"))
        assertEquals("/home/me/project", workspaceFilesRootPath("  ", "/home/me/project"))
        assertEquals("/home/me/project", workspaceFilesRootPath("/home/me/project", "/other"))
        assertEquals(".", workspaceFilesRootPath(null, null))
    }

    @Test
    fun `preview paths normalize absolute paths against cwd`() {
        assertEquals("src/main.kt", safePreviewPath("/home/me/project/src/main.kt", "/home/me/project"))
        assertEquals("src/main.kt", safePreviewPath("C:\\work\\project\\src\\main.kt", "C:\\work\\project"))
    }

    @Test
    fun `preview paths accept relative paths`() {
        assertEquals("src/main.kt", safePreviewPath("src/main.kt", "/home/me/project"))
    }

    @Test
    fun `invalid preview paths are rejected`() {
        assertNull(safePreviewPath("", "/home/me/project"))
        assertNull(safePreviewPath(".", "/home/me/project"))
        assertNull(safePreviewPath("../secret.txt", "/home/me/project"))
    }
}
