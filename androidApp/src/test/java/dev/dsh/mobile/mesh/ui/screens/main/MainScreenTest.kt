package dev.dsh.mobile.mesh.ui.screens.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainScreenTest {
    @Test
    fun `windows absolute file links keep the host address`() {
        assertEquals("C:/work/project/src/main.kt", safePreviewPath("C:\\work\\project\\src\\main.kt", "C:\\work\\project"))
    }

    @Test
    fun `preview paths accept relative paths`() {
        assertEquals("src/main.kt", safePreviewPath("src/main.kt", "/home/me/project"))
    }

    @Test
    fun `serializable main page route restores files and preview destinations`() {
        assertEquals("terminal", MainPageRoute(kind = "terminal").restoredKind())
        assertEquals("files", MainPageRoute(kind = "files", path = "src").restoredKind())
        assertEquals("preview", MainPageRoute(kind = "preview", path = "README.md", returnKind = "files").restoredKind())
        assertEquals("diff", MainPageRoute(kind = "diff", path = "src/Main.kt", seq = 14, index = 2, added = 8, deleted = 3).restoredKind())
        assertEquals("chat", MainPageRoute(kind = "unknown").restoredKind())
    }

    @Test
    fun `invalid preview paths are rejected`() {
        assertNull(safePreviewPath("", "/home/me/project"))
        assertNull(safePreviewPath(".", "/home/me/project"))
        assertNull(safePreviewPath("../secret.txt", "/home/me/project"))
    }
}
