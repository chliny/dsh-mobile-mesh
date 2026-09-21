package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarrierRecoveryPolicyTest {
    @Test
    fun `ZeroTier-only generation failures do not churn a healthy relay`() {
        assertFalse(shouldRenewCarrierAfterLoopFailure(sshEnabled = false, networkRecoveryPending = false, recoveryInFlight = false))
        assertTrue(shouldRenewCarrierAfterLoopFailure(sshEnabled = false, networkRecoveryPending = true, recoveryInFlight = false))
    }

    @Test
    fun `SSH termination or network handover can renew the carrier`() {
        assertTrue(shouldRenewCarrierAfterLoopFailure(sshEnabled = true, networkRecoveryPending = false, recoveryInFlight = false))
        assertFalse(shouldRenewCarrierAfterLoopFailure(sshEnabled = true, networkRecoveryPending = false, recoveryInFlight = true))
    }
}
