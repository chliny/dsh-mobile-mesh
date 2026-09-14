package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the SSH forwarder lifecycle contract used to initiate event-driven recovery. */
class RelayTerminationGateTest {
    @Test
    fun `unexpected listener termination is reported once`() {
        val gate = RelayTerminationGate()

        assertTrue(gate.reportUnexpectedTermination())
        assertFalse(gate.reportUnexpectedTermination())
    }

    @Test
    fun `intentional relay replacement never reports a failure`() {
        val gate = RelayTerminationGate()

        gate.markClosing()
        assertFalse(gate.reportUnexpectedTermination())
    }
}
