package dev.dsh.mobile.mesh.ui

import dev.dsh.mobile.mesh.connection.ConnectionPhase
import dev.dsh.mobile.mesh.ui.screens.connect.ConnectFailure
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRecoveryPolicyTest {
    @Test
    fun `recovery overlay blocks the existing page through every non-connected phase`() {
        assertTrue(shouldShowConnectionRecoveryOverlay(true, ConnectionPhase.DISCONNECTED, false))
        assertTrue(shouldShowConnectionRecoveryOverlay(true, ConnectionPhase.RECONNECTING, false))
        assertTrue(shouldShowConnectionRecoveryOverlay(true, ConnectionPhase.RECONNECTING, false, recoveryOverlayVisible = true))
        assertTrue(shouldShowConnectionRecoveryOverlay(true, ConnectionPhase.CONNECTING, false))
        assertTrue(shouldShowConnectionRecoveryOverlay(true, ConnectionPhase.RECONNECTING, true))
        assertTrue(shouldShowConnectionRecoveryOverlay(true, ConnectionPhase.CONNECTED, true))
        assertFalse(shouldShowConnectionRecoveryOverlay(true, ConnectionPhase.CONNECTED, false))
        assertFalse(shouldShowConnectionRecoveryOverlay(false, ConnectionPhase.RECONNECTING, false))
        assertTrue(shouldShowConnectionRecoveryOverlay(true, ConnectionPhase.RECONNECTING, false))
    }

    @Test
    fun `initial connection never shows recovery overlay before first successful connection`() {
        assertFalse(shouldShowConnectionRecoveryOverlay(
            hasConnected = false,
            phase = ConnectionPhase.CONNECTING,
            foregroundCheckPending = false,
        ))
    }

    @Test
    fun `switch from established connection keeps recovery overlay out of the list`() {
        assertFalse(shouldShowConnectionRecoveryOverlay(
            hasConnected = true,
            phase = ConnectionPhase.RECONNECTING,
            foregroundCheckPending = true,
            recoveryOverlayVisible = true,
            showingConnectionList = true,
        ))
        assertFalse(shouldShowConnectionRecoveryOverlay(
            hasConnected = true,
            phase = ConnectionPhase.CONNECTING,
            foregroundCheckPending = false,
            showingConnectionList = true,
        ))
    }

    @Test
    fun `recovery overlay remains visible on session page`() {
        assertTrue(shouldShowConnectionRecoveryOverlay(
            hasConnected = true,
            phase = ConnectionPhase.RECONNECTING,
            foregroundCheckPending = true,
            recoveryOverlayVisible = true,
        ))
    }

    @Test
    fun `established pages keep the global overlay during reconnect`() {
        assertTrue(shouldShowConnectionRecoveryOverlay(
            hasConnected = true,
            phase = ConnectionPhase.RECONNECTING,
            foregroundCheckPending = false,
        ))
        assertTrue(shouldShowConnectionRecoveryOverlay(
            hasConnected = true,
            phase = ConnectionPhase.CONNECTING,
            foregroundCheckPending = false,
            recoveryOverlayVisible = true,
        ))
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

    @Test
    fun `an expired login during recovery hands the screen to the connection list`() {
        // The scrim consumes input and owns neither the failure block nor the token prompt, so a
        // 401 during recovery would leave the retry loop spinning with no way to sign in again.
        assertTrue(shouldShowConnectionListForRecoveryLogin(
            recoveryOverlayVisible = true,
            failure = ConnectFailure.Unauthenticated,
        ))
        // Failures that the list cannot fix either must not yank the user off the covered page.
        assertFalse(shouldShowConnectionListForRecoveryLogin(
            recoveryOverlayVisible = true,
            failure = ConnectFailure.Timeout,
        ))
        // Without the scrim there is no dead end to escape.
        assertFalse(shouldShowConnectionListForRecoveryLogin(
            recoveryOverlayVisible = false,
            failure = ConnectFailure.Unauthenticated,
        ))
    }
}
