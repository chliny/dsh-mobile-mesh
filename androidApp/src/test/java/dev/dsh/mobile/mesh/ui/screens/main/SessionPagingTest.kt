package dev.dsh.mobile.mesh.ui.screens.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPagingTest {
    @Test
    fun `workspace session list shows five sessions per page`() {
        val totalSessions = 6
        val firstPage = (1..totalSessions).take(SESSION_PAGE_SIZE)

        assertEquals(5, firstPage.size)
        assertTrue(firstPage.size < totalSessions)
    }

    @Test
    fun `workspace session list shows all sessions when at most five`() {
        val totalSessions = 5
        val firstPage = (1..totalSessions).take(SESSION_PAGE_SIZE)

        assertEquals(totalSessions, firstPage.size)
        assertEquals(totalSessions, firstPage.size.coerceAtMost(totalSessions))
    }
}
