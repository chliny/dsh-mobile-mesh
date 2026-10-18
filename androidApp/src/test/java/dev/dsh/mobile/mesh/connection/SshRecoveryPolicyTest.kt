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
    fun `black holed target cannot hold a cold connection for thirty seconds`() {
        assertEquals(15_000, zeroTierTargetConnectTimeoutMs(recovery = false))
        assertEquals(20_000L, zeroTierReadinessTimeoutMs(reusedNode = false))
        // Both staggered dials can finish before the outer wait expires.
        assertTrue(zeroTierReadinessTimeoutMs(reusedNode = false) >
            zeroTierTargetConnectTimeoutMs(recovery = false) + 1_200)
        assertEquals(5_000, zeroTierTargetConnectTimeoutMs(recovery = true))
        assertTrue(SSH_RECOVERY_HANDSHAKE_TIMEOUT_MS > zeroTierTargetConnectTimeoutMs(recovery = true))
    }

    @Test
    fun `retained node after background suspension uses recovery budgets even with no active relay`() {
        // MeshTransportManager.stop clears active, but ZeroTierConnector retains its native node.
        assertEquals(8_000L, zeroTierReadinessTimeoutMs(reusedNode = true))
        assertEquals(5_000, zeroTierTargetConnectTimeoutMs(recovery = true))
        assertEquals(20_000L, zeroTierReadinessTimeoutMs(reusedNode = false))
        assertEquals(15_000, zeroTierTargetConnectTimeoutMs(recovery = false))
    }

    @Test
    fun `all resolved target addresses share one bounded connect budget`() {
        assertEquals(5_000, remainingConnectTimeoutMs(5_000, 1_000_000_000L, 1_000_000_000L))
        assertEquals(3_250, remainingConnectTimeoutMs(5_000, 1_000_000_000L, 2_750_000_000L))
        assertEquals(0, remainingConnectTimeoutMs(5_000, 1_000_000_000L, 7_000_000_000L))
    }
}
