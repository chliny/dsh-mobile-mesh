package dev.dsh.mobile.mesh.data

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceFilesPathTest {
    @Test
    fun `workspace file list preserves the server path contract`() {
        assertEquals(".", normalizeWorkspaceFilesRequestPath("."))
        assertEquals("", normalizeWorkspaceFilesRequestPath(""))
        assertEquals(" src ", normalizeWorkspaceFilesRequestPath(" src "))
    }
}
