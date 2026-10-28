package dev.dsh.mobile.mesh.ui.screens.main

import dev.dsh.mobile.mesh.data.SessionRow
import dev.dsh.mobile.mesh.data.WorkspaceRow
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionListViewOptionsTest {
    private fun session(id: String, updatedAt: Long) = SessionRow(
        sessionId = id, title = id, running = false, blank = false,
        parentSessionId = null, origin = null, cwd = null, agentPreset = null,
        updatedAt = updatedAt, pendingInteraction = null,
    )

    @Test
    fun `manual order survives workspace pagination even when timestamps differ`() {
        val registry = listOf(
            session("first", 1), session("second", 99), session("third", 5),
            session("fourth", 10), session("fifth", 20), session("sixth", 100),
        )
        assertEquals(listOf("first", "second", "third", "fourth", "fifth"),
            orderedSessionRoots(registry, byRecency = false).take(SESSION_PAGE_SIZE).map { it.sessionId })
        assertEquals(listOf("sixth", "second", "fifth", "fourth", "third"),
            orderedSessionRoots(registry, byRecency = true).take(SESSION_PAGE_SIZE).map { it.sessionId })
    }

    @Test
    fun `workspace tree keeps siblings in registry order and indents descendants`() {
        val rows = listOf("/work", "/other", "/work/app", "/work/app/src", "/work/api")
            .mapIndexed { index, path -> WorkspaceRow(index.toString(), path, path, emptyList()) }
        assertEquals(listOf("/work" to 0, "/work/app" to 1, "/work/app/src" to 2,
            "/work/api" to 1, "/other" to 0),
            workspaceTreeRows(rows).map { it.first.path to it.second })
    }

    @Test
    fun `workspace tree attaches children registered before their parent`() {
        val rows = listOf("/work/app/src", "/other", "/work", "/work/app")
            .mapIndexed { index, path -> WorkspaceRow(index.toString(), path, path, emptyList()) }
        assertEquals(listOf("/other" to 0, "/work" to 0, "/work/app" to 1,
            "/work/app/src" to 2), workspaceTreeRows(rows).map { it.first.path to it.second })
    }

    @Test
    fun `empty workspace retains its collapsed header in grouped session lists`() {
        val emptyWorkspace = WorkspaceRow("new", "/work/new", "new", emptyList())
        for (grouping in listOf(SessionGrouping.WORKSPACE, SessionGrouping.WORKSPACE_TREE)) {
            assertEquals(true, showWorkspaceHeader(grouping, ArchivedSessionFilter.HIDE))
            assertEquals(true, showWorkspaceHeader(grouping, ArchivedSessionFilter.SHOW))
            assertEquals(listOf(emptyWorkspace), listOf(emptyWorkspace).filter {
                showWorkspaceHeader(grouping, ArchivedSessionFilter.HIDE)
            })
        }
        assertEquals(false, showWorkspaceHeader(SessionGrouping.FLAT, ArchivedSessionFilter.HIDE))
        assertEquals(false, showWorkspaceHeader(SessionGrouping.WORKSPACE, ArchivedSessionFilter.ONLY))
        val drawer = java.io.File("src/main/java/dev/dsh/mobile/mesh/ui/screens/main/ChatListDrawer.kt").readText()
        val workspaceLoop = drawer.substringAfter("for ((workspace, workspaceDepth) in displayedWorkspaces)")
            .substringBefore("// Sessions the harness never registered")
        assertEquals(false, workspaceLoop.contains("if (roots.isEmpty()"))
    }

    @Test
    fun `collapsed workspace hides its descendants`() {
        val rows = listOf("/work", "/work/app", "/work/app/src")
            .mapIndexed { index, path -> WorkspaceRow(index.toString(), path, path, emptyList()) }
        assertEquals(listOf("/work" to 0),
            workspaceTreeRows(rows, isExpanded = { it != "0" }).map { it.first.path to it.second })
    }
}
