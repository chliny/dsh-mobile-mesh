package dev.dsh.mobile.mesh.ui

import dev.dsh.mobile.mesh.connection.ConnectionPhase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRecoveryPolicyTest {
    @Test
    fun `recovery overlay stays on existing page while reconnecting`() {
        assertTrue(shouldShowConnectionRecoveryOverlay(true, ConnectionPhase.DISCONNECTED, false))
        assertTrue(shouldShowConnectionRecoveryOverlay(true, ConnectionPhase.RECONNECTING, false))
        assertTrue(shouldShowConnectionRecoveryOverlay(true, ConnectionPhase.CONNECTING, false))
        assertTrue(shouldShowConnectionRecoveryOverlay(true, ConnectionPhase.CONNECTED, true))
        assertFalse(shouldShowConnectionRecoveryOverlay(true, ConnectionPhase.CONNECTED, false))
        assertFalse(shouldShowConnectionRecoveryOverlay(false, ConnectionPhase.RECONNECTING, false))
    }
}
