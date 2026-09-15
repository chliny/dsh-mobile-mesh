package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A handover noticed while a recovery is already running must survive until the slot frees; dropping
 * it is what used to leave the app retrying forever against the retired transport.
 */
class NetworkRecoveryGateTest {
    @Test
    fun `handover is armed by network change`() {
        val gate = NetworkRecoveryGate()
        assertFalse(gate.isPending())
        gate.markPending()
        assertTrue(gate.isPending())
    }

    @Test
    fun `pending handover is not consumed while an operation is in flight`() {
        val gate = NetworkRecoveryGate()
        gate.markPending()
        assertFalse(gate.consumeIfCanStart(canStart = false))
        assertTrue(gate.isPending())
    }

    @Test
    fun `pending handover starts once the operation slot frees`() {
        val gate = NetworkRecoveryGate()
        gate.markPending()
        assertTrue(gate.consumeIfCanStart(canStart = true))
        assertFalse(gate.isPending())
    }

    @Test
    fun `clear drops a handover that can no longer be applied`() {
        val gate = NetworkRecoveryGate()
        gate.markPending()
        gate.clear()
        assertFalse(gate.isPending())
        assertFalse(gate.consumeIfCanStart(canStart = true))
    }
}
