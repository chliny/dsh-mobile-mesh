package dev.dsh.mobile.mesh.ui.screens.main

import dev.dsh.mobile.mesh.data.SessionRow
import dev.dsh.mobile.mesh.data.WorkspaceRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drawer search, as the web sidebar defines it: local title / Workspace / id
 * matching merged with the Host's message-content hits.
 *
 * `session/search` only searches message content. The regression these tests
 * guard is a title match disappearing because the app rendered only that Host
 * half and dropped the local half the web sidebar has always had.
 */
class SessionSearchTest {
    private fun session(
        id: String,
        title: String? = null,
        cwd: String? = null,
        updatedAt: Long = 0L,
        blank: Boolean = false,
        origin: String? = null,
        running: Boolean = false,
    ) = SessionRow(
        sessionId = id,
        title = title,
        running = running,
        blank = blank,
        parentSessionId = null,
        origin = origin,
        cwd = cwd,
        agentPreset = null,
        updatedAt = updatedAt,
        pendingInteraction = null,
    )

    private fun derive(
        sessions: List<SessionRow>,
        workspaces: List<WorkspaceRow> = emptyList(),
        archivedIds: Set<String> = emptySet(),
        archivedFilter: ArchivedSessionFilter = ArchivedSessionFilter.HIDE,
        query: String,
        content: List<Pair<String, String>> = emptyList(),
        contentHasMore: Boolean = false,
        limit: Int = SEARCH_RESULT_LIMIT,
    ) = deriveSearchResults(
        sessions = sessions,
        workspaces = workspaces,
        archivedIds = archivedIds,
        archivedFilter = archivedFilter,
        query = query,
        contentHits = content,
        contentHasMore = contentHasMore,
        limit = limit,
    )

    @Test
    fun `a title match is found without any content hit`() {
        val result = derive(
            sessions = listOf(session("a", title = "Release notes")),
            query = "release",
        )

        assertEquals(listOf("a"), result.items.map { it.session.sessionId })
        assertNull("a purely local match carries no content excerpt", result.items.single().snippet)
    }

    @Test
    fun `a workspace name matches when the title does not`() {
        val result = derive(
            sessions = listOf(session("a", title = "Untitled")),
            workspaces = listOf(WorkspaceRow("w", "/work/mobile", "Mobile", listOf("a"))),
            query = "mobile",
        )

        assertEquals(listOf("a"), result.items.map { it.session.sessionId })
        assertEquals("Mobile", result.items.single().workspaceLabel)
    }

    @Test
    fun `every query term must match in any order, like the web sidebar`() {
        val result = derive(
            sessions = listOf(session("a", title = "Fix the language changer")),
            query = "changer fix",
        )

        assertEquals(listOf("a"), result.items.map { it.session.sessionId })
    }

    @Test
    fun `word-start subsequences match like the web fuzzy index`() {
        val result = derive(
            sessions = listOf(session("a", title = "Weekly planning")),
            query = "wkpl",
        )

        assertEquals(listOf("a"), result.items.map { it.session.sessionId })
    }

    @Test
    fun `session ids match as case-insensitive substrings`() {
        val result = derive(
            sessions = listOf(
                session("3F9A2C71-session", title = "Ordinary title"),
                session("b7e0d4aa-session", title = "Another title"),
            ),
            query = "9a2c71",
        )

        assertEquals(listOf("3F9A2C71-session"), result.items.map { it.session.sessionId })
    }

    @Test
    fun `content hits follow local matches, deduplicate, and keep their excerpt`() {
        val result = derive(
            sessions = listOf(
                session("local", title = "Plan the migration", updatedAt = 10),
                session("remote", title = "Unrelated title", updatedAt = 999),
            ),
            query = "plan",
            content = listOf(
                "remote" to "…we should plan this…",
                "local" to "…the plan is approved…",
                "remote" to "ignored duplicate excerpt",
            ),
        )

        assertEquals(listOf("local", "remote"), result.items.map { it.session.sessionId })
        assertEquals("…the plan is approved…", result.items[0].snippet)
        assertEquals("…we should plan this…", result.items[1].snippet)
    }

    @Test
    fun `blank, completed subagent, and hidden archived rows never match`() {
        val sessions = listOf(
            session("blank", title = "Needle blank", blank = true),
            session("child", title = "Needle child", origin = "subagent"),
            session("archived", title = "Needle archived"),
        )
        val hidden = derive(
            sessions = sessions,
            archivedIds = setOf("archived"),
            query = "needle",
            content = listOf("blank" to "needle", "child" to "needle", "archived" to "needle"),
        )
        assertTrue(hidden.items.isEmpty())

        val shown = derive(
            sessions = sessions,
            archivedIds = setOf("archived"),
            archivedFilter = ArchivedSessionFilter.SHOW,
            query = "needle",
        )
        assertEquals(listOf("archived"), shown.items.map { it.session.sessionId })
    }

    @Test
    fun `results are capped with the web page size and never hide a known overflow`() {
        val sessions = (1..SEARCH_RESULT_LIMIT + 1).map { session("s$it", title = "plan $it") }

        val exactlyFull = derive(sessions.take(SEARCH_RESULT_LIMIT), query = "plan")
        assertEquals(SEARCH_RESULT_LIMIT, exactlyFull.items.size)
        assertFalse(exactlyFull.hasMore)

        val overflow = derive(sessions, query = "plan")
        assertEquals(SEARCH_RESULT_LIMIT, overflow.items.size)
        assertTrue(overflow.hasMore)

        val hostOverflow = derive(sessions.take(1), query = "plan", contentHasMore = true)
        assertTrue(hostOverflow.hasMore)
    }
}
