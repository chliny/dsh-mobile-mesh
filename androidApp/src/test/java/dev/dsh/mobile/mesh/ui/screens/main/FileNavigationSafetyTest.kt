package dev.dsh.mobile.mesh.ui.screens.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileNavigationSafetyTest {
    @Test
    fun `rejects absolute file paths outside the session cwd`() {
        assertNull(safePreviewPath("/tmp/secret.txt", "/home/me/project"))
        assertNull(safePreviewPath("/home/me/project2/secret.txt", "/home/me/project"))
        assertNull(safePreviewPath("/home/me/PROJECT/secret.txt", "/home/me/project"))
        assertNull(safePreviewPath("C:/other/secret.txt", "C:/work/project"))
    }

    @Test
    fun `keeps relative paths without rewriting absolute host addresses`() {
        assertEquals("src/Main.kt", safePreviewPath("src/Main.kt", "/home/me/project"))
        assertEquals("/home/me/project/src/Main.kt", safePreviewPath("/home/me/project/src/Main.kt", "/home/me/project"))
        assertEquals("/home/me/project/ folder / report .md ", safePreviewPath("/home/me/project/ folder / report .md ", "/home/me/project"))
        assertEquals(" ", safePreviewPath(" ", "/home/me/project"))
    }

    @Test
    fun `workspace path click keeps the absolute address when opening`() {
        var opened: Pair<String, String>? = null
        val context = ChatNodeContext(
            nodes = emptyList(),
            disclosures = DisclosureScope(TranscriptDisclosures(), "session-1"),
            running = false,
            cwd = "/home/me/project",
            onOpenFile = { path, title -> opened = path to title },
            onOpenSubagent = {},
            onBranchFrom = {},
            onFeedback = { _, _ -> },
        )

        openWorkspacePath(context, "/home/me/project/src/Main.kt", "Main.kt", context.onOpenFile)

        assertEquals("/home/me/project/src/Main.kt" to "Main.kt", opened)
        opened = null
        openWorkspacePath(context, "/home/me/project2/secret.txt", "secret.txt", context.onOpenFile)
        assertNull(opened)
        openWorkspacePath(context, "/home/me/project/ report .md ", " report .md ", context.onOpenFile)
        assertEquals("/home/me/project/ report .md " to " report .md ", opened)
    }
}
