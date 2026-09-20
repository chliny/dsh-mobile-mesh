package dev.dsh.mobile.mesh.data

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceFilesPathTest {
    @Test
    fun `workspace file list preserves the server path contract`() {
        assertEquals(".", normalizeWorkspaceFilesRequestPath("."))
        assertEquals(".", normalizeWorkspaceFilesRequestPath(""))
        assertEquals("src", normalizeWorkspaceFilesRequestPath(" src "))
        assertEquals(".", normalizeWorkspaceFilesRequestPath("   "))
    }

    @Test
    fun `file reads reject blank paths before reaching the API`() {
        assertEquals(null, validWorkspaceFilePath(""))
        assertEquals(null, validWorkspaceFilePath("   "))
        assertEquals("src/main.kt", validWorkspaceFilePath(" src/main.kt "))
    }
}
