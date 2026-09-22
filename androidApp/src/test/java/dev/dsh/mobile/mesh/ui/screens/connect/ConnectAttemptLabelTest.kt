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
    fun `direct host uses Harness port`() {
        assertEquals(
            "198.51.100.10:3080",
            connectionAttemptAuthority("198.51.100.10", 3080, sshEnabled = false, sshPort = 36000),
        )
    }
}
