package dev.dsh.mobile.mesh.ui.screens.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTranscriptPagingTest {
    @Test
    fun `two distinct history pulls are required before paging`() {
        val first = nextTopPullCount(0, 0, 48f, 32f, canPage = true)
        assertEquals(1, first)
        assertEquals(2, nextTopPullCount(first, 0, 48f, 32f, canPage = true))
        assertEquals(1, nextTopPullCount(first, 0, 4f, 32f, canPage = true))
        assertEquals(1, nextTopPullCount(first, 3, 48f, 32f, canPage = true))
        assertEquals(1, nextTopPullCount(first, 0, 48f, 32f, canPage = false))
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
        assertTrue(shouldWaitForOlderPageAnchor(true, true, false, "new-first", "old-first"))
        assertFalse(shouldWaitForOlderPageAnchor(false, true, false, "new-first", "old-first"))
        assertFalse(shouldWaitForOlderPageAnchor(false, true, true, "old-first", "old-first"))
    }

    @Test
    fun `page completion restores prepend anchor only until the user starts a new drag`() {
        assertTrue(shouldRestoreOlderPageAnchor(capturedDragGeneration = 4, currentDragGeneration = 4, userDragging = false))
        assertFalse(shouldRestoreOlderPageAnchor(capturedDragGeneration = 4, currentDragGeneration = 5, userDragging = true))
        assertFalse(shouldRestoreOlderPageAnchor(capturedDragGeneration = 4, currentDragGeneration = 5, userDragging = false))
        assertFalse(shouldRestoreOlderPageAnchor(capturedDragGeneration = 4, currentDragGeneration = 4, userDragging = true))
    }

}
