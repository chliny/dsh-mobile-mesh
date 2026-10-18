package dev.dsh.mobile.mesh.data

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceFilesPathTest {
    @Test
    fun `workspace file list preserves the server path contract`() {
        assertEquals(".", normalizeWorkspaceFilesRequestPath("."))
        assertEquals(".", normalizeWorkspaceFilesRequestPath(""))
        assertEquals(" src ", normalizeWorkspaceFilesRequestPath(" src "))
        assertEquals(" ", normalizeWorkspaceFilesRequestPath(" "))
        assertEquals(" name ", normalizeWorkspaceFilesRequestPath("/ name "))
        assertEquals("src", normalizeWorkspaceFilesRequestPath("/src"))
        assertEquals(".", normalizeWorkspaceFilesRequestPath("/"))
        assertEquals("   ", normalizeWorkspaceFilesRequestPath("   "))
    }

    @Test
    fun `file reads reject empty paths but preserve whitespace in file names`() {
        assertEquals(null, validWorkspaceFilePath(""))
        assertEquals("   ", validWorkspaceFilePath("   "))
        assertEquals(" src/main.kt ", validWorkspaceFilePath(" src/main.kt "))
    }
}
