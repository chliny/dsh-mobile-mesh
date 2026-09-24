package dev.dsh.mobile.mesh.ui.screens.connect

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectAttemptLabelTest {
    @Test
    fun `remembered SSH host uses persisted SSH port`() {
        assertEquals(
            "198.51.100.10:36000",
            connectionAttemptAuthority("198.51.100.10", 3080, sshEnabled = true, sshPort = 36000),
        )
    }

    @Test
    fun `preflight SSH credential failure label uses configured SSH port`() {
        assertEquals(
            "tailscale-harness.example:2222",
            failureDisplayAuthority("tailscale-harness.example:22", sshEnabled = true, sshPort = 2222),
        )
    }

    @Test
    fun `SSH failure label leaves actual non-default SSH port unchanged`() {
        assertEquals(
            "tailscale-harness.example:2200",
            failureDisplayAuthority("tailscale-harness.example:2200", sshEnabled = true, sshPort = 2200),
        )
    }

    @Test
    fun `direct host uses Harness port`() {
        assertEquals(
            "198.51.100.10:3080",
            connectionAttemptAuthority("198.51.100.10", 3080, sshEnabled = false, sshPort = 36000),
        )
    }
}
