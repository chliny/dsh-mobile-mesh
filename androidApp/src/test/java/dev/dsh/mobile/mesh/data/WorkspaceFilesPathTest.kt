package dev.dsh.mobile.mesh.data

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceFilesPathTest {
    @Test
    fun `workspace file list path never stays blank`() {
        assertEquals(".", normalizeWorkspaceFilesRequestPath(""))
        assertEquals(".", normalizeWorkspaceFilesRequestPath("  "))
        assertEquals("src", normalizeWorkspaceFilesRequestPath(" src "))
        assertEquals("src\\main", normalizeWorkspaceFilesRequestPath("src\\main"))
    }
}
