package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundRecoveryPolicyTest {
    @Test
    fun `connected foreground carrier loss recovers immediately`() {
        assertEquals(
            ForegroundRecoveryAction.RECOVER,
            foregroundRecoveryAction(
                ForegroundRecoveryFacts(
                    phase = ConnectionPhase.CONNECTED,
                    hasActiveHost = true,
                    recoveryInFlight = false,
                    backgroundDurationMs = 0L,
                    networkChanged = true,
                ),
            ),
        )
    }
}
