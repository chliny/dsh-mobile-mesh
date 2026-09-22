package dev.dsh.mobile.mesh.ui.screens.connect

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectAttemptLabelTest {
    @Test
    fun `remembered SSH host uses persisted SSH port`() {
        assertEquals(
            "9.134.11.175:36000",
            connectionAttemptAuthority("9.134.11.175", 3080, sshEnabled = true, sshPort = 36000),
        )
    }

    @Test
    fun `direct host uses Harness port`() {
        assertEquals(
            "9.134.11.175:3080",
            connectionAttemptAuthority("9.134.11.175", 3080, sshEnabled = false, sshPort = 36000),
        )
    }
}
