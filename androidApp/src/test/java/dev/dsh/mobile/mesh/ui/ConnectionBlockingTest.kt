package dev.dsh.mobile.mesh.ui

import dev.dsh.mobile.mesh.connection.ConnectionPhase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionBlockingTest {
    @Test
    fun `connected main UI is not blocked`() {
        assertFalse(shouldBlockForConnectionRecovery(true, ConnectionPhase.CONNECTED))
    }

    @Test
    fun `reconnecting established session is blocked`() {
        assertTrue(shouldBlockForConnectionRecovery(true, ConnectionPhase.RECONNECTING))
        assertTrue(shouldBlockForConnectionRecovery(true, ConnectionPhase.CONNECTING))
        assertTrue(shouldBlockForConnectionRecovery(true, ConnectionPhase.DISCONNECTED))
    }

    @Test
    fun `green session with pending foreground check is blocked`() {
        assertTrue(shouldBlockForConnectionRecovery(true, ConnectionPhase.CONNECTED, foregroundCheckPending = true))
    }

    @Test
    fun `cold connect screen is not covered by main recovery overlay`() {
        assertFalse(shouldBlockForConnectionRecovery(false, ConnectionPhase.CONNECTING))
        assertFalse(shouldBlockForConnectionRecovery(false, ConnectionPhase.DISCONNECTED))
    }
}
