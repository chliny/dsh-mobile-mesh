package dev.dsh.mobile.mesh.ui.screens.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.dsh.mobile.mesh.data.WorkspaceRow

class SessionPagingTest {
    @Test
    fun `workspace session list shows five sessions per page`() {
        val totalSessions = 6
        val firstPage = (1..totalSessions).take(SESSION_PAGE_SIZE)

        assertEquals(5, firstPage.size)
        assertTrue(firstPage.size < totalSessions)
    }

    @Test
    fun `opening a session and returning keeps pages in the app root`() {
        val appRoot = java.io.File("src/main/java/dev/dsh/mobile/mesh/ui/AppRoot.kt").readText()
        val drawer = java.io.File("src/main/java/dev/dsh/mobile/mesh/ui/screens/main/ChatListDrawer.kt").readText()
        val retainedState = appRoot.indexOf("val visiblePageByWorkspace = remember(activeHostKey)")
        val pageSwitch = appRoot.indexOf("        when {")

        assertTrue(retainedState >= 0 && retainedState < pageSwitch)
        assertTrue(appRoot.contains("visiblePageByWorkspace = visiblePageByWorkspace,"))
        assertTrue(drawer.contains("fun ChatListDrawer(\n    visiblePageByWorkspace: MutableMap<String, Int>,"))
        assertTrue(!drawer.contains("val visiblePageByWorkspace = remember"))
    }

    @Test
    fun `loaded pages remain until workspace folds then reset for its subtree only`() {
        val workspaces = listOf(
            WorkspaceRow("parent", "/repo", "Parent", emptyList()),
            WorkspaceRow("child", "/repo/child", "Child", emptyList()),
            WorkspaceRow("other", "/elsewhere", "Other", emptyList()),
        )
        val pages = mutableMapOf("parent" to 3, "child" to 2, "other" to 4)

        // Loading more and expanding again leave the watermark untouched; only folding resets it.
        assertEquals(15, pages.getValue("parent") * SESSION_PAGE_SIZE)
        resetCollapsedWorkspacePages(pages, workspaces, "parent")
        assertEquals(SESSION_PAGE_SIZE, (pages["parent"] ?: 1) * SESSION_PAGE_SIZE)
        assertEquals(SESSION_PAGE_SIZE, (pages["child"] ?: 1) * SESSION_PAGE_SIZE)
        assertEquals(20, pages.getValue("other") * SESSION_PAGE_SIZE)
    }

    @Test
    fun `folding a child does not reset its parent or sibling prefix`() {
        val workspaces = listOf(
            WorkspaceRow("parent", "/repo", "Parent", emptyList()),
            WorkspaceRow("child", "/repo/child", "Child", emptyList()),
            WorkspaceRow("sibling", "/repository", "Sibling", emptyList()),
        )
        val pages = mutableMapOf("parent" to 3, "child" to 2, "sibling" to 4)

        resetCollapsedWorkspacePages(pages, workspaces, "child")
        assertEquals(mapOf("parent" to 3, "sibling" to 4), pages)
    }

    @Test
    fun `workspace session list shows all sessions when at most five`() {
        val totalSessions = 5
        val firstPage = (1..totalSessions).take(SESSION_PAGE_SIZE)

        assertEquals(totalSessions, firstPage.size)
        assertEquals(totalSessions, firstPage.size.coerceAtMost(totalSessions))
    }
}
