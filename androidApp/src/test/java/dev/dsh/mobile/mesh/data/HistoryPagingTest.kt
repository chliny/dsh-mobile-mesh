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
    fun `installing an older page requests a coalesced rebuild instead of folding under the receiver lock`() {
        assertTrue(shouldSchedulePageRebuild(pageSessionId = "session-1", currentSessionId = "session-1"))
        assertFalse(shouldSchedulePageRebuild(pageSessionId = "session-1", currentSessionId = "session-2"))
        assertFalse(shouldSchedulePageRebuild(pageSessionId = null, currentSessionId = "session-1"))
    }
}
