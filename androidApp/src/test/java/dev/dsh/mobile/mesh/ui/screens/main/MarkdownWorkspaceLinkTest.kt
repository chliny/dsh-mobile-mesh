package dev.dsh.mobile.mesh.ui.screens.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarkdownWorkspaceLinkTest {
    private fun context(onOpenFile: (String, String) -> Unit) = ChatNodeContext(
        nodes = emptyList(),
        cwd = "/home/me/project",
        running = false,
        onOpenFile = onOpenFile,
        onOpenSubagent = {},
        onBranchFrom = {},
        onFeedback = { _, _ -> },
    )

    @Test fun `workspace markdown links open through transcript file callback`() {
        val opened = mutableListOf<Pair<String, String>>()
        val context = context { path, title -> opened += path to title }
        val urls = mutableListOf<String>()
        listOf("src/Main.kt#L24", "/home/me/project/README.md#L2-L4", "file:///home/me/project/a%20b.txt").forEach {
            openMarkdownLink(context, it, urls::add)
        }
        assertEquals(listOf(
            "src/Main.kt" to "Main.kt",
            "/home/me/project/README.md" to "README.md",
            "/home/me/project/a b.txt" to "a b.txt",
        ), opened)
        assertEquals(emptyList<String>(), urls)
    }

    @Test fun `web and other scheme links remain external`() {
        val opened = mutableListOf<Pair<String, String>>()
        val urls = mutableListOf<String>()
        val context = context { path, title -> opened += path to title }
        listOf("https://example.com/a", "mailto:me@example.com").forEach { openMarkdownLink(context, it, urls::add) }
        assertEquals(listOf("https://example.com/a", "mailto:me@example.com"), urls)
        assertEquals(emptyList<Pair<String, String>>(), opened)
        assertNull(markdownWorkspaceLinkPath("#L24"))
        assertNull(markdownWorkspaceLinkPath("//example.com/file.txt"))
    }

    @Test fun `Windows drive paths are files rather than URI schemes`() {
        assertEquals("C:\\work\\src\\App.kt", markdownWorkspaceLinkPath("C:\\work\\src\\App.kt#L10"))
    }
}
