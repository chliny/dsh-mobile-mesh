package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultNetworkTrackerTest {
    @Test
    fun `replacement available before old lost produces one recovery`() {
        val tracker = DefaultNetworkTracker("A")
        tracker.onAvailable("B", connected = true)
        assertTrue(tracker.consumeRecoveryNeeded())
        tracker.onLost("A", connected = true)
        assertFalse(tracker.consumeRecoveryNeeded())
        assertEquals("B", tracker.current())
    }

    @Test
    fun `loss before replacement remains dirty until foreground consumes it`() {
        val tracker = DefaultNetworkTracker("A")
        tracker.onLost("A", connected = true)
        tracker.onAvailable("B", connected = true)
        assertTrue(tracker.consumeRecoveryNeeded())
        assertEquals("B", tracker.current())
    }

    @Test
    fun `duplicate and stale callbacks do not request recovery`() {
        val tracker = DefaultNetworkTracker("A")
        tracker.onAvailable("A", connected = true)
        tracker.onLost("B", connected = true)
        assertFalse(tracker.consumeRecoveryNeeded())
        assertEquals("A", tracker.current())
    }

    @Test
    fun `network changes before first connection do not create recovery debt`() {
        val tracker = DefaultNetworkTracker("A")
        tracker.onAvailable("B", connected = false)
        tracker.onLost("B", connected = false)
        assertFalse(tracker.consumeRecoveryNeeded())
    }
}
