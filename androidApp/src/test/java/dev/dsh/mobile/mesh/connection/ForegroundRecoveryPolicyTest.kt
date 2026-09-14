package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundRecoveryPolicyTest {
    @Test
    fun `brief healthy task switch avoids probe and UI churn`() {
        assertEquals(
            ForegroundRecoveryAction.NONE,
            action(ConnectionPhase.CONNECTED, backgroundMs = 500),
        )
    }

    @Test
    fun `longer healthy background stay actively verifies without teardown`() {
        assertEquals(
            ForegroundRecoveryAction.VERIFY,
            action(ConnectionPhase.CONNECTED, backgroundMs = 1_500),
        )
    }

    @Test
    fun `network handover or non-connected state recovers immediately`() {
        assertEquals(
            ForegroundRecoveryAction.RECOVER,
            action(ConnectionPhase.CONNECTED, backgroundMs = 10, networkChanged = true),
        )
        assertEquals(
            ForegroundRecoveryAction.RECOVER,
            action(ConnectionPhase.RECONNECTING, backgroundMs = 10),
        )
    }

    @Test
    fun `duplicate resume is coalesced while probe or recovery runs`() {
        assertEquals(
            ForegroundRecoveryAction.NONE,
            action(ConnectionPhase.CONNECTED, backgroundMs = 5_000, inFlight = true),
        )
    }

    @Test
    fun `pending foreground check does not start a second recovery`() {
        assertEquals(
            ForegroundRecoveryAction.NONE,
            foregroundRecoveryAction(
                ForegroundRecoveryFacts(
                    phase = ConnectionPhase.RECONNECTING,
                    hasActiveHost = true,
                    recoveryInFlight = false,
                    backgroundDurationMs = 5_000,
                    networkChanged = true,
                    foregroundCheckPending = true,
                ),
            ),
        )
    }

    @Test
    fun `no active host does no recovery work`() {
        assertEquals(
            ForegroundRecoveryAction.NONE,
            foregroundRecoveryAction(
                ForegroundRecoveryFacts(
                    phase = ConnectionPhase.DISCONNECTED,
                    hasActiveHost = false,
                    recoveryInFlight = false,
                    backgroundDurationMs = 5_000,
                    networkChanged = true,
                ),
            ),
        )
    }

    private fun action(
        phase: ConnectionPhase,
        backgroundMs: Long,
        networkChanged: Boolean = false,
        inFlight: Boolean = false,
    ) = foregroundRecoveryAction(
        ForegroundRecoveryFacts(
            phase = phase,
            hasActiveHost = true,
            recoveryInFlight = inFlight,
            backgroundDurationMs = backgroundMs,
            networkChanged = networkChanged,
        ),
    )
}
