package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundRecoveryPolicyTest {
    @Test
    fun `connected generation publishes only after live generation and lifecycle checks`() {
        assertEquals(true, shouldPublishConnectedGeneration(generationReady = true, lifecycleCurrent = true))
        assertEquals(false, shouldPublishConnectedGeneration(generationReady = false, lifecycleCurrent = true))
        assertEquals(false, shouldPublishConnectedGeneration(generationReady = true, lifecycleCurrent = false))
    }

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
    fun `foreground resume rearms stranded reconnect after background cancelled retry`() {
        assertEquals(
            true,
            shouldStartForegroundRecovery(ForegroundRecoveryAction.RECOVER, recoveryInFlight = false),
        )
    }

    @Test
    fun `foreground resume does not duplicate a live recovery job`() {
        assertEquals(
            false,
            shouldStartForegroundRecovery(ForegroundRecoveryAction.RECOVER, recoveryInFlight = true),
        )
    }

    @Test
    fun `verification action does not start transport recovery`() {
        assertEquals(
            false,
            shouldStartForegroundRecovery(ForegroundRecoveryAction.VERIFY, recoveryInFlight = false),
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

    @Test
    fun `quick retries stay fast before falling back to a slower cadence`() {
        assertEquals(1_500L, recoveryRetryDelayMs(0))
        assertEquals(1_500L, recoveryRetryDelayMs(1))
        assertEquals(1_500L, recoveryRetryDelayMs(2))
        assertEquals(8_000L, recoveryRetryDelayMs(3))
        assertEquals(8_000L, recoveryRetryDelayMs(9))
    }

    @Test
    fun `stranded recovery keeps a bounded slow cadence instead of stopping`() {
        // The attempt counter saturates at the fast-retry budget, so the delay never grows unbounded
        // and never becomes "stop retrying".
        assertEquals(
            recoveryRetryDelayMs(FOREGROUND_RECOVERY_FAST_ATTEMPTS),
            recoveryRetryDelayMs(FOREGROUND_RECOVERY_FAST_ATTEMPTS + 20),
        )
    }

    @Test
    fun `replacement network rearms a stranded recovery`() {
        assertEquals(
            true,
            shouldReArmOnReplacementNetwork(connected = false, networkChanged = true, hasDesiredHost = true),
        )
    }

    @Test
    fun `replacement network leaves a healthy session alone`() {
        assertEquals(
            false,
            shouldReArmOnReplacementNetwork(connected = true, networkChanged = true, hasDesiredHost = true),
        )
    }

    @Test
    fun `same network again does not rearm`() {
        assertEquals(
            false,
            shouldReArmOnReplacementNetwork(connected = false, networkChanged = false, hasDesiredHost = true),
        )
    }

    @Test
    fun `no desired host means no rearm`() {
        assertEquals(
            false,
            shouldReArmOnReplacementNetwork(connected = false, networkChanged = true, hasDesiredHost = false),
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
