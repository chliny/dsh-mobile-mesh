package dev.dsh.mobile.mesh.ui

import dev.dsh.mobile.mesh.connection.ConnectionPhase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRecoveryPolicyTest {
    @Test
    fun `recovery overlay stays on existing page while reconnecting`() {
        assertFalse(shouldShowConnectionRecoveryOverlay(true, ConnectionPhase.DISCONNECTED, false))
        assertTrue(shouldShowConnectionRecoveryOverlay(true, ConnectionPhase.RECONNECTING, false))
        assertTrue(shouldShowConnectionRecoveryOverlay(true, ConnectionPhase.RECONNECTING, false, recoveryOverlayVisible = true))
        assertFalse(shouldShowConnectionRecoveryOverlay(true, ConnectionPhase.CONNECTING, false))
        assertTrue(shouldShowConnectionRecoveryOverlay(true, ConnectionPhase.RECONNECTING, true))
        assertTrue(shouldShowConnectionRecoveryOverlay(true, ConnectionPhase.CONNECTED, true))
        assertFalse(shouldShowConnectionRecoveryOverlay(true, ConnectionPhase.CONNECTED, false))
        assertFalse(shouldShowConnectionRecoveryOverlay(false, ConnectionPhase.RECONNECTING, false))
    }

    @Test
    fun `foreground reconnecting state keeps overlay visible when recovery callback races resume`() {
        assertTrue(shouldShowConnectionRecoveryOverlay(true, ConnectionPhase.RECONNECTING, true))
    }

    @Test
    fun `immediate resume after yellow reconnect keeps the global overlay visible`() {
        assertTrue(shouldShowConnectionRecoveryOverlay(
            hasConnected = true,
            phase = ConnectionPhase.RECONNECTING,
            foregroundCheckPending = true,
            recoveryOverlayVisible = true,
        ))
    }

    @Test
    fun `short resume re-arm keeps the global overlay visible`() {
        assertTrue(shouldRearmConnectionRecoveryOverlay(hasConnected = true, recoveryInFlight = true))
        assertTrue(shouldShowConnectionRecoveryOverlay(
            hasConnected = true,
            phase = ConnectionPhase.CONNECTED,
            foregroundCheckPending = true,
            recoveryOverlayVisible = true,
        ))
    }

    @Test
    fun `retained in-flight recovery is visible after foreground resume re-arms pending`() {
        // The manager clears pending while the retained transport continues in background, then
        // restores it when the activity resumes into that already-running recovery.
        assertTrue(shouldRearmConnectionRecoveryOverlay(hasConnected = true, recoveryInFlight = true))
        assertFalse(shouldRearmConnectionRecoveryOverlay(hasConnected = false, recoveryInFlight = true))
        assertTrue(shouldShowConnectionRecoveryOverlay(true, ConnectionPhase.RECONNECTING, true))
    }
}
