package dev.dsh.mobile.mesh.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The page cursor and coalescing rules for scroll-driven history paging. */
class HistoryPagingTest {

    @Test
    fun `a page that added nothing ends the paging even when the host says otherwise`() {
        assertFalse(nextHasMore(freshCount = 0, hostHasMore = true, overDelivered = true))
        assertFalse(nextHasMore(freshCount = 0, hostHasMore = true, overDelivered = false))
        assertFalse(nextHasMore(freshCount = 0, hostHasMore = false, overDelivered = false))
    }

    @Test
    fun `a page that added events keeps the host's verdict`() {
        assertTrue(nextHasMore(freshCount = 12, hostHasMore = true, overDelivered = false))
        assertFalse(nextHasMore(freshCount = 12, hostHasMore = false, overDelivered = false))
    }

    @Test
    fun `a trimmed over-delivery counts as more to come`() {
        assertTrue(nextHasMore(freshCount = 60, hostHasMore = false, overDelivered = true))
    }

    @Test
    fun `a delayed page is rejected after session reopen changes cursor generation`() {
        val request = HistoryPageFence(
            sessionId = "session-1",
            generation = 7,
            throughSeq = 100,
            beforeSeq = 40,
        )

        assertTrue(shouldApplyHistoryPage(request, "session-1", 7, 100, 40))
        assertFalse(shouldApplyHistoryPage(request, "session-2", 7, 100, 40))
        assertFalse(shouldApplyHistoryPage(request, "session-1", 8, 100, 40))
        assertFalse(shouldApplyHistoryPage(request, "session-1", 7, 101, 40))
        assertFalse(shouldApplyHistoryPage(request, "session-1", 7, 100, 39))
    }

    @Test
    fun `old page completion cannot release a newer generation loading state`() {
        assertFalse(shouldReleaseHistoryPageLoading(requestGeneration = 7, loadingGeneration = 8))
        assertTrue(shouldReleaseHistoryPageLoading(requestGeneration = 8, loadingGeneration = 8))
        assertFalse(shouldReleaseHistoryPageLoading(requestGeneration = 8, loadingGeneration = null))
    }

    @Test
    fun `installing an older page requests a coalesced rebuild instead of folding under the receiver lock`() {
        assertTrue(shouldSchedulePageRebuild(pageSessionId = "session-1", currentSessionId = "session-1"))
        assertFalse(shouldSchedulePageRebuild(pageSessionId = "session-1", currentSessionId = "session-2"))
        assertFalse(shouldSchedulePageRebuild(pageSessionId = null, currentSessionId = "session-1"))
    }
}
