package dev.dsh.mobile.mesh.ui.screens.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** An empty in-memory list before session/list completes is loading, never proof of a new account. */
class SessionListBaselinePresentationTest {
    @Test
    fun `empty drawer distinguishes loading failure authoritative empty and populated states`() {
        assertEquals(SessionListEmptyState.LOADING, sessionListEmptyState(false, false, false))
        assertEquals(SessionListEmptyState.LOADING, sessionListEmptyState(false, false, true))
        assertEquals(SessionListEmptyState.LOADING, sessionListEmptyState(false, true, false, generationMatches = false))
        assertEquals(SessionListEmptyState.FAILED, sessionListEmptyState(false, true, true))
        assertEquals(SessionListEmptyState.EMPTY, sessionListEmptyState(false, true, false))
        assertEquals(SessionListEmptyState.NOT_EMPTY, sessionListEmptyState(true, false, false))

        val source = java.io.File("src/main/java/dev/dsh/mobile/mesh/ui/screens/main/ChatListDrawer.kt").readText()
        val drawer = source.substringAfter("fun ChatListDrawer(").substringBefore("if (newWorkspaceOpen)")
        assertTrue(drawer.contains("chatlist_loading_sessions"))
        assertTrue(drawer.contains("chatlist_sessions_load_failed"))
        assertTrue(drawer.contains("chatlist_empty"))
    }
}
