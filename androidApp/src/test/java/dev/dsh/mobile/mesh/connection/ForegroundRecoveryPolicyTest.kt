package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundRecoveryPolicyTest {
    @Test
    fun `connected generation publishes only after live generation and lifecycle checks`() {
        assertTrue(shouldPublishConnectedGeneration(true, true))
        assertFalse(shouldPublishConnectedGeneration(false, true))
        assertFalse(shouldPublishConnectedGeneration(true, false))
    }

    @Test
    fun `brief healthy task switch skips the foreground probe`() {
        assertEquals(ForegroundRecoveryAction.NONE, action(ConnectionPhase.CONNECTED, 0))
        assertEquals(ForegroundRecoveryAction.NONE, action(ConnectionPhase.CONNECTED, 4_999))
    }

    @Test
    fun `longer healthy background stay verifies without teardown`() {
        assertEquals(ForegroundRecoveryAction.VERIFY, action(ConnectionPhase.CONNECTED, 5_000))
        assertEquals(ForegroundRecoveryAction.VERIFY, action(ConnectionPhase.CONNECTED, 15_000))
    }

    @Test
    fun `long retained background goes straight to carrier recovery`() {
        assertEquals(ForegroundRecoveryAction.RECOVER, action(ConnectionPhase.CONNECTED, 30_000))
    }

    @Test
    fun `network handover or non-connected state recovers immediately`() {
        assertEquals(ForegroundRecoveryAction.RECOVER, action(ConnectionPhase.CONNECTED, 10, networkChanged = true))
        assertEquals(ForegroundRecoveryAction.RECOVER, action(ConnectionPhase.RECONNECTING, 10))
    }

    @Test
    fun `duplicate resume is coalesced while probe or recovery runs`() {
        assertEquals(ForegroundRecoveryAction.NONE, action(ConnectionPhase.CONNECTED, 5_000, inFlight = true))
    }

    @Test
    fun `stale presentation can be cleared when no recovery remains`() {
        assertFalse(effectiveForegroundCheckPending(presentationPending = true, recoveryInFlight = false))
    }

    @Test
    fun `pending foreground check does not start a second recovery`() {
        assertEquals(ForegroundRecoveryAction.NONE, foregroundRecoveryAction(ForegroundRecoveryFacts(ConnectionPhase.RECONNECTING, true, false, 5_000, true, true)))
    }

    @Test
    fun `stale pending latch can be cleared for a healthy connected carrier`() {
        assertTrue(shouldClearConnectedRecoveryPresentation(ConnectionPhase.CONNECTED, false, false))
        assertFalse(shouldClearConnectedRecoveryPresentation(ConnectionPhase.RECONNECTING, false, false))
        assertFalse(shouldClearConnectedRecoveryPresentation(ConnectionPhase.CONNECTED, true, false))
        assertFalse(shouldClearConnectedRecoveryPresentation(ConnectionPhase.CONNECTED, false, true))
    }

    @Test
    fun `onResume immediately after onStart is coalesced`() {
        assertTrue(shouldCoalesceForegroundRecovery(true, 10_500, 10_000))
        assertFalse(shouldCoalesceForegroundRecovery(true, 10_751, 10_000))
        assertFalse(shouldCoalesceForegroundRecovery(false, 10_100, 10_000))
    }

    @Test
    fun `foreground resume rearms stranded reconnect after background cancelled retry`() {
        assertTrue(shouldStartForegroundRecovery(ForegroundRecoveryAction.RECOVER, false))
        assertFalse(shouldStartForegroundRecovery(ForegroundRecoveryAction.RECOVER, true))
        assertFalse(shouldStartForegroundRecovery(ForegroundRecoveryAction.VERIFY, false))
    }

    @Test
    fun `no active host does no recovery work`() {
        assertEquals(ForegroundRecoveryAction.NONE, foregroundRecoveryAction(ForegroundRecoveryFacts(ConnectionPhase.DISCONNECTED, false, false, 5_000, true)))
    }

    @Test
    fun `stale foreground presentation does not block recovery without in-flight work`() {
        assertFalse(effectiveForegroundCheckPending(presentationPending = true, recoveryInFlight = false))
        assertTrue(effectiveForegroundCheckPending(presentationPending = true, recoveryInFlight = true))
        assertFalse(effectiveForegroundCheckPending(presentationPending = false, recoveryInFlight = true))
    }

    @Test
    fun `forced interactive resume can rearm connected generation`() {
        assertTrue(shouldRearmPublishedGenerationAfterBackground(true, true))
    }

    @Test
    fun `new generation is not probed solely by recovery presentation`() {
        assertFalse(shouldProbePublishedGeneration(true, true, false))
        assertTrue(shouldProbePublishedGeneration(true, false, true))
    }

    @Test
    fun `retained connected carrier is probed after every background hop`() {
        assertTrue(shouldRearmPublishedGenerationAfterBackground(true, true))
        assertFalse(shouldRearmPublishedGenerationAfterBackground(true, false))
        assertFalse(shouldRearmPublishedGenerationAfterBackground(false, true))
    }

    @Test
    fun `retained reconnect keeps recovery presentation across a background hop`() {
        assertTrue(shouldPreserveRecoveryPresentationOnBackground(true, ConnectionPhase.RECONNECTING, true, false))
        assertTrue(shouldPreserveRecoveryPresentationOnBackground(true, ConnectionPhase.CONNECTING, true, false))
        assertTrue(shouldPreserveRecoveryPresentationOnBackground(true, ConnectionPhase.RECONNECTING, false, true))
        assertFalse(shouldPreserveRecoveryPresentationOnBackground(true, ConnectionPhase.CONNECTED, true, false))
        assertFalse(shouldPreserveRecoveryPresentationOnBackground(false, ConnectionPhase.RECONNECTING, true, false))
    }

    @Test
    fun `replacement loop opening does not trigger another carrier recovery`() {
        assertTrue(shouldIgnoreReplacementLoopReconnect(ConnectionPhase.RECONNECTING, true))
        assertFalse(shouldIgnoreReplacementLoopReconnect(ConnectionPhase.RECONNECTING, false))
        assertFalse(shouldIgnoreReplacementLoopReconnect(ConnectionPhase.CONNECTED, true))
    }

    @Test
    fun `foreground restart preserves reconnect only after an established generation`() {
        assertEquals(ConnectionPhase.RECONNECTING, foregroundRestartPhase(true))
        assertEquals(ConnectionPhase.CONNECTING, foregroundRestartPhase(false))
    }

    @Test
    fun `probe failure during an active operation is deferred to the operation boundary`() {
        assertTrue(shouldArmRecoveryAfterProbeFailure(appInForeground = true, operationInFlight = true))
        assertFalse(shouldArmRecoveryAfterProbeFailure(appInForeground = false, operationInFlight = true))
        assertFalse(shouldArmRecoveryAfterProbeFailure(appInForeground = true, operationInFlight = false))
    }

    @Test
    fun `pending foreground network gate gets its own Doze recheck`() {
        assertTrue(shouldScheduleForegroundNetworkRecheck(appInForeground = true, networkRecoveryPending = true))
        assertFalse(shouldScheduleForegroundNetworkRecheck(appInForeground = false, networkRecoveryPending = true))
        assertFalse(shouldScheduleForegroundNetworkRecheck(appInForeground = true, networkRecoveryPending = false))
    }

    @Test
    fun `quick retries stay fast before falling back to a slower cadence`() {
        assertEquals(500L, recoveryRetryDelayMs(0))
        assertEquals(500L, recoveryRetryDelayMs(2))
        assertEquals(5_000L, recoveryRetryDelayMs(3))
    }

    @Test
    fun `replacement network rearms only when disconnected with a desired host`() {
        assertTrue(shouldReArmOnReplacementNetwork(false, true, true))
        assertFalse(shouldReArmOnReplacementNetwork(true, true, true))
        assertFalse(shouldReArmOnReplacementNetwork(false, false, true))
        assertFalse(shouldReArmOnReplacementNetwork(false, true, false))
    }

    @Test
    fun `generation failure renews retained carrier in foreground or service background`() {
        assertTrue(shouldRenewCarrierAfterGenerationFailure(true, true, false, false))
        assertTrue(shouldRenewCarrierAfterGenerationFailure(true, false, true, false))
        assertFalse(shouldRenewCarrierAfterGenerationFailure(true, false, false, false))
        assertFalse(shouldRenewCarrierAfterGenerationFailure(true, true, true, true))
    }

    @Test
    fun `duplicate lifecycle callbacks are ignored`() {
        assertTrue(shouldHandleLifecycleTransition(false, true))
        assertTrue(shouldHandleLifecycleTransition(true, false))
        assertFalse(shouldHandleLifecycleTransition(true, true))
        assertFalse(shouldHandleLifecycleTransition(false, false))
    }

    private fun action(phase: ConnectionPhase, backgroundMs: Long, networkChanged: Boolean = false, inFlight: Boolean = false) =
        foregroundRecoveryAction(ForegroundRecoveryFacts(phase, true, inFlight, backgroundMs, networkChanged))
}
