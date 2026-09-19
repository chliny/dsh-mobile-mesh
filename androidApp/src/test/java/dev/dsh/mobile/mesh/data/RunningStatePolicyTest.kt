package dev.dsh.mobile.mesh.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunningStatePolicyTest {
    @Test
    fun `history snapshot clears stale running state`() {
        assertFalse(runningStateFromSnapshot(existing = true, snapshot = false))
        assertTrue(runningStateFromSnapshot(existing = false, snapshot = true))
    }

    @Test
    fun `history initializes running state when live status is unknown`() {
        assertTrue(runningStateFromSnapshot(existing = null, snapshot = true))
        assertFalse(runningStateFromSnapshot(existing = null, snapshot = false))
    }
}
