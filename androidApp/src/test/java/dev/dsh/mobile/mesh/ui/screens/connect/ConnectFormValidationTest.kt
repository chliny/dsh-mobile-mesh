package dev.dsh.mobile.mesh.ui.screens.connect

import dev.dsh.mobile.mesh.connection.ConnectionDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectFormValidationTest {
    @Test
    fun `new connection defaults to direct transport instead of SSH`() {
        // The Harness listener on 3080 is HTTP/WebSocket. Starting SSH there produces
        // "Server closed connection during identification exchange" before Harness is reached.
        assertFalse(ConnectionDraft().sshEnabled)
    }

    @Test
    fun `SSH failure address names the SSH server port rather than Harness port`() {
        assertEquals("198.51.100.10:36000", connectionAttemptAuthority("198.51.100.10", 3080, true, 36000))
        assertEquals("198.51.100.10:3080", connectionAttemptAuthority("198.51.100.10", 3080, false, 22))
    }

    @Test
    fun `complete SSH form is connectable`() {
        assertTrue(isConnectFormValid("tailscale-harness.example", "3080", true, "testuser", "22", "127.0.0.1", "token"))
    }

    @Test
    fun `incomplete SSH form is not connectable`() {
        assertFalse(isConnectFormValid("tailscale-harness.example", "3080", true, "", "22", "127.0.0.1", "token"))
    }
}
