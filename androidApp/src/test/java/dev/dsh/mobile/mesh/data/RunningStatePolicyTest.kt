package dev.dsh.mobile.mesh.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunningStatePolicyTest {
    @Test
    fun `follow history snapshot cannot stop an already running session row`() {
        assertTrue(runningStateFromSnapshot(existing = true, snapshot = false))
        assertFalse(runningStateFromSnapshot(existing = false, snapshot = true))
    }

    @Test
    fun `status received during list request outranks its older response`() {
        assertTrue(runningStateFromList(listed = false, live = true, statusRevision = 12, requestStartRevision = 11))
        assertFalse(runningStateFromList(listed = true, live = false, statusRevision = 12, requestStartRevision = 11))
    }

    @Test
    fun `fresh list repairs stale running state from previous generation`() {
        assertFalse(runningStateFromList(listed = false, live = true, statusRevision = 10, requestStartRevision = 11))
        assertTrue(runningStateFromList(listed = true, live = false, statusRevision = null, requestStartRevision = 11))
    }

    @Test
    fun `history initializes running state when live status is unknown`() {
        assertTrue(runningStateFromSnapshot(existing = null, snapshot = true))
        assertFalse(runningStateFromSnapshot(existing = null, snapshot = false))
    }
}
