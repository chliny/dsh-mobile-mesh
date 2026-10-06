package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StaggeredDialPermitsTest {
    @Test fun `fast foreground dial stays single socket`() {
        var now = 0L
        val permits = StaggeredDialPermits(1_200) { now }
        assertEquals(1, permits.tryAcquire())
        now = 999_000_000L
        assertNull(permits.tryAcquire())
        permits.release()
        assertEquals(0, permits.inFlight)
    }

    @Test fun `stalled dial permits one delayed backup but no third socket`() {
        var now = 0L
        val permits = StaggeredDialPermits(1_200) { now }
        assertEquals(1, permits.tryAcquire())
        now = 1_199_999_999L
        assertNull(permits.tryAcquire())
        now = 1_200_000_000L
        assertEquals(2, permits.tryAcquire())
        assertNull(permits.tryAcquire())
        assertEquals(2, permits.inFlight)
        permits.release()
        permits.release()
        assertEquals(0, permits.inFlight)
    }

    @Test fun `after both sockets finish next recovery waits before hedging`() {
        var now = 0L
        val permits = StaggeredDialPermits(1_200) { now }
        permits.tryAcquire()
        now = 1_200_000_000L
        permits.tryAcquire()
        permits.release()
        permits.release()
        now = 2_000_000_000L
        assertEquals(1, permits.tryAcquire())
        assertNull(permits.tryAcquire())
    }

    @Test fun `quick failure resets delay for next attempt`() {
        var now = 0L
        val permits = StaggeredDialPermits(1_200) { now }
        permits.tryAcquire()
        permits.release()
        now = 1_300_000_000L
        assertEquals(1, permits.tryAcquire())
        assertNull(permits.tryAcquire())
    }
}
