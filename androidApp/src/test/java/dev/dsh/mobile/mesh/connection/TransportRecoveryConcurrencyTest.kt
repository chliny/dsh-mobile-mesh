package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportRecoveryConcurrencyTest {
    @Test
    fun `recovery gate rejects a second scheduled recovery`() {
        assertTrue(transportRecoveryCanStart(operationInFlight = false, recoveryInFlight = false))
        assertFalse(transportRecoveryCanStart(operationInFlight = false, recoveryInFlight = true))
        assertFalse(transportRecoveryCanStart(operationInFlight = true, recoveryInFlight = false))
    }

    @Test
    fun `carrier failure stays armed while an operation is unwinding`() {
        assertTrue(shouldDeferCarrierRecovery(operationInFlight = true, recoveryInFlight = false))
        assertTrue(shouldDeferCarrierRecovery(operationInFlight = false, recoveryInFlight = true))
        assertFalse(shouldDeferCarrierRecovery(operationInFlight = false, recoveryInFlight = false))
    }
}

internal fun transportRecoveryCanStart(
    operationInFlight: Boolean,
    recoveryInFlight: Boolean,
): Boolean = !operationInFlight && !recoveryInFlight
