package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundServiceStartGateTest {
    @Test
    fun `stopping during a pending start waits until service enters foreground`() {
        val gate = ForegroundServiceStartGate()
        gate.requestStart()
        assertFalse(gate.requestStop())
        assertTrue(gate.foregroundStarted())
    }

    @Test
    fun `two queued starts both enter foreground before a deferred stop`() {
        val gate = ForegroundServiceStartGate()
        gate.requestStart()
        gate.requestStart()
        assertFalse(gate.requestStop())
        assertFalse(gate.foregroundStarted())
        assertTrue(gate.foregroundStarted())
    }

    @Test
    fun `failed final start releases a deferred stop`() {
        val gate = ForegroundServiceStartGate()
        gate.requestStart()
        assertFalse(gate.requestStop())
        assertTrue(gate.startFailed())
    }

    @Test
    fun `a renewed start cancels a deferred stop`() {
        val gate = ForegroundServiceStartGate()
        gate.requestStart()
        assertFalse(gate.requestStop())
        gate.requestStart()
        assertFalse(gate.foregroundStarted())
        assertFalse(gate.foregroundStarted())
        assertTrue(gate.requestStop())
    }
}
