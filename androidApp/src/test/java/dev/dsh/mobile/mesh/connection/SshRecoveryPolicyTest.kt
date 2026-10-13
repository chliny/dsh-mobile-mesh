package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SshRecoveryPolicyTest {
    @Test
    fun `recovery uses one bounded SSH attempt and outer loop retries with fresh relay`() {
        assertEquals(25_000, sshHandshakeTimeoutMs(recovery = false))
        assertEquals(8_000, sshHandshakeTimeoutMs(recovery = true))
        assertEquals(25_000, SSH_INITIAL_HANDSHAKE_TIMEOUT_MS)
        assertEquals(8_000, SSH_RECOVERY_HANDSHAKE_TIMEOUT_MS)
        assertEquals(3, sshConnectAttempts(recovery = false))
        assertEquals(1, sshConnectAttempts(recovery = true))
    }

    @Test
    fun `target port native connect has a short recovery budget and preserves initial budget`() {
        assertEquals(30_000, zeroTierTargetConnectTimeoutMs(recovery = false))
        assertEquals(5_000, zeroTierTargetConnectTimeoutMs(recovery = true))
        assertTrue(SSH_RECOVERY_HANDSHAKE_TIMEOUT_MS > zeroTierTargetConnectTimeoutMs(recovery = true))
    }

    @Test
    fun `all resolved target addresses share one bounded connect budget`() {
        assertEquals(5_000, remainingConnectTimeoutMs(5_000, 1_000_000_000L, 1_000_000_000L))
        assertEquals(3_250, remainingConnectTimeoutMs(5_000, 1_000_000_000L, 2_750_000_000L))
        assertEquals(0, remainingConnectTimeoutMs(5_000, 1_000_000_000L, 7_000_000_000L))
    }
}
