package dev.dsh.mobile.mesh.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunningStatePolicyTest {
    @Test
    fun `known live running state survives a history snapshot`() {
        assertTrue(runningStateFromSnapshot(existing = true, snapshot = false))
        assertFalse(runningStateFromSnapshot(existing = false, snapshot = true))
    }

    @Test
    fun `history initializes running state when live status is unknown`() {
        assertTrue(runningStateFromSnapshot(existing = null, snapshot = true))
        assertFalse(runningStateFromSnapshot(existing = null, snapshot = false))
    }
}
