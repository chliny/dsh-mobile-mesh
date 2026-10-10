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
    fun `a short initial transcript waits for an explicit older-page request`() {
        assertFalse(shouldPageAtTop(firstVisible = 0, fillsViewport = false, autoPages = 0, maxAutoPages = 0, userScrolling = false))
        assertTrue(shouldPageAtTop(firstVisible = 0, fillsViewport = false, autoPages = 0, maxAutoPages = 0, userScrolling = true))
    }

    @Test
    fun `page completion restores prepend anchor only until the user starts a new drag`() {
        assertTrue(shouldRestoreOlderPageAnchor(capturedDragGeneration = 4, currentDragGeneration = 4, userDragging = false))
        assertFalse(shouldRestoreOlderPageAnchor(capturedDragGeneration = 4, currentDragGeneration = 5, userDragging = true))
        assertFalse(shouldRestoreOlderPageAnchor(capturedDragGeneration = 4, currentDragGeneration = 5, userDragging = false))
        assertFalse(shouldRestoreOlderPageAnchor(capturedDragGeneration = 4, currentDragGeneration = 4, userDragging = true))
    }

    @Test
    fun `away from the top never pages`() {
        assertFalse(shouldPageAtTop(firstVisible = 3, fillsViewport = false, autoPages = 0, maxAutoPages = 1, userScrolling = true))
    }
}
