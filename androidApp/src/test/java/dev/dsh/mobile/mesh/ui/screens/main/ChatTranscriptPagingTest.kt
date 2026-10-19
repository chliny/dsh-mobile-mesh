package dev.dsh.mobile.mesh.ui.screens.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTranscriptPagingTest {
    @Test
    fun `pull spent at the top requests paging even when the drag reached the top first`() {
        // Scrolling back to the top is navigation, so that travel does not count...
        var distance = nextTopPullDistance(previous = 0f, deltaY = 240f, atTop = false)
        assertEquals(0f, distance, 0f)
        // ...but continuing to pull once the top is reached is the deliberate request.
        distance = nextTopPullDistance(previous = distance, deltaY = 48f, atTop = true)
        assertEquals(48f, distance, 0f)
        assertTrue(topPullRequestsOlderPage(distance, 32f, 0, canPage = true))
        assertTrue(topPullRequestsOlderPage(distance, 32f, 2, canPage = true))
    }

    @Test
    fun `top pull ignores incidental or unavailable paging`() {
        val pulled = nextTopPullDistance(previous = 0f, deltaY = 48f, atTop = true)
        assertFalse(topPullRequestsOlderPage(pulled, 32f, 3, canPage = true))
        assertFalse(topPullRequestsOlderPage(pulled, 32f, 0, canPage = false))
        assertFalse(
            topPullRequestsOlderPage(nextTopPullDistance(0f, 4f, atTop = true), 32f, 0, canPage = true),
        )
        // Moving back toward newer content loses the credit: only movement at the top counts.
        assertEquals(0f, nextTopPullDistance(pulled, -80f, atTop = true), 0f)
        assertEquals(0f, nextTopPullDistance(pulled, -80f, atTop = false), 0f)
    }

    @Test
    fun `a short transcript that cannot scroll still pages after a deliberate pull`() {
        // canScrollBackward stays false for a transcript shorter than the viewport, so every
        // downward move accumulates; 16 + 16 reaches the 32 px threshold exactly.
        var distance = nextTopPullDistance(0f, 16f, atTop = true)
        distance = nextTopPullDistance(distance, 16f, atTop = true)
        assertEquals(32f, distance, 0f)
        assertTrue(topPullRequestsOlderPage(distance, 32f, 0, canPage = true))
        // One pixel short of the threshold is not a pull.
        assertFalse(topPullRequestsOlderPage(distance - 1f, 32f, 0, canPage = true))
    }

    @Test
    fun `pull distance is measured only while the top is reached`() {
        // The accumulator is local to one gesture; these cases pin down how the moves inside it
        // combine. A detector restart does not "re-earn" a pull — it waits for a new down, which
        // is why the detector keys exclude streamed rows and paging flags.
        assertEquals(12f, nextTopPullDistance(0f, 12f, atTop = true), 0f)
        assertEquals(50f, nextTopPullDistance(30f, 20f, atTop = true), 0f)
        assertEquals(20f, nextTopPullDistance(50f, -30f, atTop = true), 0f)
        assertEquals(0f, nextTopPullDistance(20f, -30f, atTop = true), 0f)
        // Leaving the top discards whatever was accumulated below it.
        assertEquals(0f, nextTopPullDistance(50f, 10f, atTop = false), 0f)
    }

    @Test
    fun `a failed page is retried by the next deliberate pull, not only by the button`() {
        val failed = OlderPageGate(hasMore = true, loading = false, loadingOlder = false, failed = true)
        assertTrue(failed.allowsPull)
        assertTrue(OlderPageGate(true, false, false, failed = false).allowsPull)
        assertFalse(OlderPageGate(false, false, false, failed = false).allowsPull)
        assertFalse(OlderPageGate(true, loading = true, loadingOlder = false, failed = false).allowsPull)
        assertFalse(OlderPageGate(true, loading = false, loadingOlder = true, failed = false).allowsPull)
    }

    @Test
    fun `pull detector restarts only for a new session or threshold, never for a streamed row`() {
        // Regression guard for the bug: the detector used to be keyed on the transcript rows and
        // on `canPage`, so a streamed row or a settled page cancelled the pull in flight.
        val keys = transcriptPullDetectorKeys("session-1", 64f)
        assertEquals(listOf<Any?>("session-1", 64f), keys)
        assertEquals(keys, transcriptPullDetectorKeys("session-1", 64f))
        assertEquals(listOf<Any?>("session-2", 64f), transcriptPullDetectorKeys("session-2", 64f))
        assertEquals(listOf<Any?>("session-1", 96f), transcriptPullDetectorKeys("session-1", 96f))
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
