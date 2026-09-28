package dev.dsh.mobile.mesh.ui.screens.main

import dev.dsh.mobile.mesh.data.SessionRow
import dev.dsh.mobile.mesh.data.WorkspaceRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ensures search presentation preserves the Harness's authoritative result order and scope. */
class SessionSearchTest {
    private fun session(id: String, title: String? = null, cwd: String? = null, updatedAt: Long = 0L) = SessionRow(
        sessionId = id,
        title = title,
        running = false,
        blank = false,
        parentSessionId = null,
        origin = null,
        cwd = cwd,
        agentPreset = null,
        updatedAt = updatedAt,
        pendingInteraction = null,
    )

    @Test
    fun `maps content hits in server order without local title matching or sorting`() {
        val sessions = listOf(
            session("newest", title = "plan later", updatedAt = 900),
            session("server-first", title = "unrelated", updatedAt = 100),
        )
        val results = mapSearchResults(
            sessions = sessions,
            workspaces = emptyList(),
            contentHits = listOf("server-first" to "server-ranked excerpt"),
        )
        assertEquals(listOf("server-first"), results.map { it.session.sessionId })
        assertEquals("server-ranked excerpt", results.single().snippet)
    }

    @Test
    fun `uses local session and workspace data only for result labels`() {
        val results = mapSearchResults(
            sessions = listOf(session("a", title = "Release notes", cwd = "/work/repo")),
            workspaces = listOf(WorkspaceRow("w", "/work/mobile", "Mobile", listOf("a"))),
            contentHits = listOf("a" to "matching message"),
        )
        assertEquals("Mobile", results.single().workspaceLabel)
        assertEquals("matching message", results.single().snippet)
    }

    @Test
    fun `does not synthesize title matches when server returns no content hits`() {
        val results = mapSearchResults(
            sessions = listOf(session("a", title = "Release notes")),
            contentHits = emptyList(),
            workspaces = emptyList(),
        )
        assertTrue(results.isEmpty())
    }

    @Test
    fun `does not fabricate a result for an id missing from the current session list`() {
        val results = mapSearchResults(
            sessions = emptyList(),
            workspaces = emptyList(),
            contentHits = listOf("missing" to "server snippet"),
        )
        assertNull(results.firstOrNull())
    }
}
