package dev.dsh.mobile.mesh.ui.screens.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTranscriptPagingTest {
    @Test
    fun `a full viewport at the top does not auto page`() {
        assertFalse(shouldPageAtTop(firstVisible = 0, fillsViewport = true, autoPages = 0, maxAutoPages = 1, userScrolling = false))
        assertTrue(shouldPageAtTop(firstVisible = 0, fillsViewport = true, autoPages = 0, maxAutoPages = 1, userScrolling = true))
    }

    @Test
    fun `a short transcript may fill one page`() {
        assertTrue(shouldPageAtTop(firstVisible = 0, fillsViewport = false, autoPages = 0, maxAutoPages = 1, userScrolling = false))
        assertFalse(shouldPageAtTop(firstVisible = 0, fillsViewport = false, autoPages = 1, maxAutoPages = 1, userScrolling = false))
    }

    @Test
    fun `away from the top never pages`() {
        assertFalse(shouldPageAtTop(firstVisible = 3, fillsViewport = false, autoPages = 0, maxAutoPages = 1, userScrolling = true))
    }
}
