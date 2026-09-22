package dev.dsh.mobile.mesh.connection

import dev.dsh.mobile.mesh.core.wire.GenerationFailure
import dev.dsh.mobile.mesh.core.wire.TransportFailure
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarrierRecoveryPolicyTest {
    @Test
    fun `ZeroTier-only generation failures do not churn a healthy relay`() {
        assertFalse(shouldRenewCarrierAfterLoopFailure(sshEnabled = false, networkRecoveryPending = false, recoveryInFlight = false, failedAttempt = 1))
        assertTrue(shouldRenewCarrierAfterLoopFailure(sshEnabled = false, networkRecoveryPending = true, recoveryInFlight = false, failedAttempt = 1))
        assertTrue(shouldRenewCarrierAfterLoopFailure(sshEnabled = false, networkRecoveryPending = false, recoveryInFlight = false, failedAttempt = ZERO_TIER_RENEW_AFTER_FAILURES))
    }

    @Test
    fun `host authentication response never renews ZeroTier carrier`() {
        assertFalse(loopFailureCanRenewCarrier(GenerationFailure.MuxFailed(TransportFailure.UNAUTHENTICATED, "HTTP 401")))
        assertTrue(loopFailureCanRenewCarrier(GenerationFailure.MuxTimedOut(3_000)))
    }

    @Test
    fun `SSH termination or network handover can renew the carrier`() {
        assertTrue(shouldRenewCarrierAfterLoopFailure(sshEnabled = true, networkRecoveryPending = false, recoveryInFlight = false, failedAttempt = 1))
        assertFalse(shouldRenewCarrierAfterLoopFailure(sshEnabled = true, networkRecoveryPending = false, recoveryInFlight = true, failedAttempt = 1))
    }
}
