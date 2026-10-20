package dev.dsh.mobile.mesh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceFilesListRequestTest {
    @Test
    fun `refresh replaces an in-flight list even for the same directory`() {
        val request = DirectoryListRequest("workspace", "session", "src")
        assertTrue(shouldReuseDirectoryList(request, request, reload = false))
        assertFalse(shouldReuseDirectoryList(request, request, reload = true))
        assertFalse(shouldReuseDirectoryList(request, request.copy(path = "test"), reload = false))
    }

    @Test
    fun `late previous list cannot overwrite newer server cache`() {
        val fence = RequestGenerationFence()
        val old = fence.next()
        val fresh = fence.next()
        var cached = ""
        assertTrue(fence.runIfCurrent(fresh) { cached = "new server result" })
        assertFalse(fence.runIfCurrent(old) { cached = "stale server result" })
        assertEquals("new server result", cached)
    }
}
