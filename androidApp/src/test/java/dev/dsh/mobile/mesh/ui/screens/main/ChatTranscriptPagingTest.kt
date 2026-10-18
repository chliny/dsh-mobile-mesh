package dev.dsh.mobile.mesh.ui.screens.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTranscriptPagingTest {
    @Test
    fun `one distinct history pull at the top requests paging`() {
        assertTrue(shouldLoadOlderAfterPull(true, 0, 48f, 32f, canPage = true))
        assertTrue(shouldLoadOlderAfterPull(true, 2, 48f, 32f, canPage = true))
        assertFalse(shouldLoadOlderAfterPull(false, 0, 48f, 32f, canPage = true))
        assertFalse(shouldLoadOlderAfterPull(true, 3, 48f, 32f, canPage = true))
        assertFalse(shouldLoadOlderAfterPull(true, 0, 4f, 32f, canPage = true))
        assertFalse(shouldLoadOlderAfterPull(true, 0, 48f, 32f, canPage = false))
    }

    @Test
    fun `prepend anchor keeps the same visible message rather than jumping further into history`() {
        assertEquals(28, olderPageAnchorScrollOffset(-28))
        assertEquals(-42, olderPageAnchorScrollOffset(42))
    }

    @Test
    fun `tail intent catches up after a row grows before layout observer starts`() {
        assertTrue(shouldCatchUpTranscriptTail(
            tailIntent = true,
            measuredNearBottom = false,
            userDragging = false,
            scrollInProgress = false,
            canScrollForward = true,
        ))
        assertFalse(shouldCatchUpTranscriptTail(
            tailIntent = true,
            measuredNearBottom = false,
            userDragging = true,
            scrollInProgress = false,
            canScrollForward = true,
        ))
        assertFalse(shouldCatchUpTranscriptTail(
            tailIntent = false,
            measuredNearBottom = false,
            userDragging = false,
            scrollInProgress = false,
            canScrollForward = true,
        ))
        assertFalse(shouldCatchUpTranscriptTail(
            tailIntent = true,
            measuredNearBottom = true,
            userDragging = false,
            scrollInProgress = false,
            canScrollForward = true,
        ))
    }

    @Test
    fun `prepend anchor survives drag release and loading until a new first row arrives`() {
        assertTrue(shouldWaitForOlderPageAnchor(false, true, false, "old-first", "old-first"))
        assertTrue(shouldWaitForOlderPageAnchor(true, true, false, "old-first", "old-first"))
        assertFalse(shouldWaitForOlderPageAnchor(true, true, false, "new-first", "old-first"))
        assertFalse(shouldWaitForOlderPageAnchor(false, true, false, "new-first", "old-first"))
        assertFalse(shouldWaitForOlderPageAnchor(false, true, true, "old-first", "old-first"))
    }

    @Test
    fun `page anchor waits for triggering drag to stop instead of jumping to older page`() {
        assertFalse(shouldDiscardOlderPageAnchor(capturedDragGeneration = 4, currentDragGeneration = 4))
        assertFalse(shouldRestoreOlderPageAnchor(capturedDragGeneration = 4, currentDragGeneration = 4, userDragging = true))
        assertTrue(shouldRestoreOlderPageAnchor(capturedDragGeneration = 4, currentDragGeneration = 4, userDragging = false))
        assertTrue(shouldDiscardOlderPageAnchor(capturedDragGeneration = 4, currentDragGeneration = 5))
        assertFalse(shouldRestoreOlderPageAnchor(capturedDragGeneration = 4, currentDragGeneration = 5, userDragging = false))
    }

}
