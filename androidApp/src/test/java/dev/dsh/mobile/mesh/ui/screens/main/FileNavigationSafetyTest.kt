package dev.dsh.mobile.mesh.ui.screens.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileNavigationSafetyTest {
    @Test
    fun `rejects absolute file paths outside the session cwd`() {
        assertNull(safePreviewPath("/tmp/secret.txt", "/home/me/project"))
        assertNull(safePreviewPath("C:/other/secret.txt", "C:/work/project"))
    }

    @Test
    fun `keeps relative paths and strips the session cwd`() {
        assertEquals("src/Main.kt", safePreviewPath("src/Main.kt", "/home/me/project"))
        assertEquals("src/Main.kt", safePreviewPath("/home/me/project/src/Main.kt", "/home/me/project"))
    }

    @Test
    fun `workspace path click normalizes absolute path before opening`() {
        var opened: Pair<String, String>? = null
        val context = ChatNodeContext(
            nodes = emptyList(),
            running = false,
            cwd = "/home/me/project",
            onOpenFile = { path, title -> opened = path to title },
            onOpenSubagent = {},
            onBranchFrom = {},
            onFeedback = { _, _ -> },
        )

        openWorkspacePath(context, "/home/me/project/src/Main.kt", "Main.kt", context.onOpenFile)

        assertEquals("src/Main.kt", opened?.first)
        assertEquals("Main.kt", opened?.second)
    }
}
