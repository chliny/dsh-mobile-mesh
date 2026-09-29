package dev.dsh.mobile.mesh.data

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryPagingTimeoutPolicyTest {
    @Test
    fun `page wait allows slow durable history but remains bounded`() {
        assertEquals(60_000L, SESSION_PAGE_TIMEOUT_MS)
        assertEquals(60_000L, pageTimeoutForTransport(SESSION_PAGE_TIMEOUT_MS))
        assertEquals(30_000L, pageTimeoutForTransport(8_000L))
        assertEquals(30_000L, pageTimeoutForTransport(30_000L))
        assertEquals(90_000L, pageTimeoutForTransport(90_000L))
    }
}
