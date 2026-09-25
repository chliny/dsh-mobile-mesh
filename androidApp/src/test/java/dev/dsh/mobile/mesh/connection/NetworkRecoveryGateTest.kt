package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Versioned callbacks survive claim/start races without unbounded recovery churn. */
class NetworkRecoveryGateTest {
    @Test
    fun `handover is armed by network change`() {
        val gate = NetworkRecoveryGate()
        assertFalse(gate.isPending())
        gate.markPending()
        assertTrue(gate.isPending())
    }

    @Test
    fun `cannot claim while operation is in flight`() {
        val gate = NetworkRecoveryGate()
        gate.markPending()
        assertNull(gate.claimIfCanStart(canStart = false))
        assertTrue(gate.isPending())
    }

    @Test
    fun `claim acknowledges only captured events and leaves later handover pending`() {
        val gate = NetworkRecoveryGate()
        gate.markPending()
        val firstAttempt = gate.claimIfCanStart(canStart = true)
        assertNotNull(firstAttempt)
        assertNull(gate.claimIfCanStart(canStart = true))

        // Callback delivered after the recovery decision/claim must not be lost by its completion.
        gate.markPending()
        gate.acknowledge(checkNotNull(firstAttempt))
        assertTrue(gate.isPending())

        val followUp = gate.claimIfCanStart(canStart = true)
        assertNotNull(followUp)
        gate.acknowledge(checkNotNull(followUp))
        assertFalse(gate.isPending())
        // A completion without a newer callback does not arm an infinite follow-up chain.
        assertNull(gate.claimIfCanStart(canStart = true))
    }

    @Test
    fun `failed start releases claim without discarding event`() {
        val gate = NetworkRecoveryGate()
        gate.markPending()
        val claim = checkNotNull(gate.claimIfCanStart(canStart = true))
        gate.release(claim)
        assertTrue(gate.isPending())
        val retry = checkNotNull(gate.claimIfCanStart(canStart = true))
        gate.acknowledge(retry)
        assertFalse(gate.isPending())
    }

    @Test
    fun `failed transport attempt leaves handover eligible for retry`() {
        val gate = NetworkRecoveryGate()
        gate.markPending()
        val firstAttempt = checkNotNull(gate.claimIfCanStart(canStart = true))
        gate.complete(firstAttempt, transportSucceeded = false)
        assertTrue(gate.isPending())

        val retry = checkNotNull(gate.claimIfCanStart(canStart = true))
        gate.complete(retry, transportSucceeded = true)
        assertFalse(gate.isPending())
    }

    @Test
    fun `successful transport consumes only the handover events it claimed`() {
        val gate = NetworkRecoveryGate()
        gate.markPending()
        val attempt = checkNotNull(gate.claimIfCanStart(canStart = true))
        gate.markPending()
        gate.complete(attempt, transportSucceeded = true)
        assertTrue(gate.isPending())
    }

    @Test
    fun `clear drops pending and claimed events`() {
        val gate = NetworkRecoveryGate()
        gate.markPending()
        val claim = checkNotNull(gate.claimIfCanStart(canStart = true))
        gate.clear()
        assertFalse(gate.isPending())
        gate.acknowledge(claim)
        assertFalse(gate.isPending())
    }
}
