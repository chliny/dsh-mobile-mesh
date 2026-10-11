package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SSH_CONNECT_ATTEMPTS = 3
private const val SSH_RECOVERY_CONNECT_ATTEMPTS = 2

class SshRecoveryPolicyTest {
    @Test
    fun `recovery uses a shorter bounded handshake budget without changing initial connect`() {
        assertEquals(25_000, sshHandshakeTimeoutMs(recovery = false))
        assertEquals(15_000, sshHandshakeTimeoutMs(recovery = true))
        assertEquals(25_000, SSH_INITIAL_HANDSHAKE_TIMEOUT_MS)
        // The incident log's SSH transport handshake completed in 13,124ms after background resume.
        assertEquals(15_000, SSH_RECOVERY_HANDSHAKE_TIMEOUT_MS)
        assertTrue(SSH_RECOVERY_HANDSHAKE_TIMEOUT_MS > 13_124)
        assertEquals(2, SSH_RECOVERY_CONNECT_ATTEMPTS)
        assertEquals(3, SSH_CONNECT_ATTEMPTS)
    }

    @Test
    fun `recovery uses one immediate retry while initial connect keeps full retries`() {
        assertEquals(2, SSH_RECOVERY_CONNECT_ATTEMPTS)
        assertEquals(3, SSH_CONNECT_ATTEMPTS)
    }
}
