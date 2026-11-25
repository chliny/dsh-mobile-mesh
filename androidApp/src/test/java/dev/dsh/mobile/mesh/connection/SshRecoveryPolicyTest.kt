package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `escalated recovery attempt outlasts the join that short dials timed out on`() {
        // A Wi-Fi/cellular switch or a long background expires the retained node's NAT bindings and
        // peer paths. Stale direct paths are now dropped before the first dial of a changed network
        // (see the handover test below); what remains is a genuinely slow rejoin, which must get one
        // attempt long enough to span it instead of failing on the same short deadline forever.
        assertEquals(5_000, zeroTierRecoveryTargetConnectTimeoutMs(0))
        assertEquals(8_000L, zeroTierRecoveryReadinessTimeoutMs(0))
        // One fast retry is tolerated; the attempt after it may span the rejoin.
        assertEquals(5_000, zeroTierRecoveryTargetConnectTimeoutMs(1))
        val patientDialMs = zeroTierRecoveryTargetConnectTimeoutMs(2)
        val patientWaitMs = zeroTierRecoveryReadinessTimeoutMs(2)
        assertEquals(15_000, patientDialMs)
        assertEquals(20_000L, patientWaitMs)
        assertTrue("a patient dial must span the join the fast dials never reached", patientDialMs >= 14_400)
        // Both staggered dials can finish inside the wait that owns them.
        assertTrue(patientWaitMs > patientDialMs + 1_200)
        assertTrue("an unresolved outage keeps the patient budget", zeroTierPatientRecovery(7))
    }

    @Test
    fun `a handover resets cached direct paths before the first retained-node dial`() {
        // Device trace (12:14:52-12:17:37): after a Wi-Fi -> cellular -> Wi-Fi handover the retained
        // node kept dialing the underlay it had left, every 5s/15s dial timed out for 2m45s, and the
        // same node reached the host in 329ms once those stale direct paths had expired and traffic
        // went over the relay. A reset must therefore precede the first dial of a changed network.
        assertTrue(
            "a default-network change must reset before the first dial",
            zeroTierShouldResetPeerPaths(
                defaultNetworkChanged = true,
                consecutiveNotReady = 0,
                alreadyResetThisRun = false,
            ),
        )
        assertTrue(
            "a failed dial without an observed handover must still reset",
            zeroTierShouldResetPeerPaths(
                defaultNetworkChanged = false,
                consecutiveNotReady = 1,
                alreadyResetThisRun = false,
            ),
        )
        assertFalse(
            "a healthy dial on an unchanged network must not touch paths",
            zeroTierShouldResetPeerPaths(
                defaultNetworkChanged = false,
                consecutiveNotReady = 0,
                alreadyResetThisRun = false,
            ),
        )
        assertFalse(
            "an ongoing outage must not churn paths on every attempt",
            zeroTierShouldResetPeerPaths(
                defaultNetworkChanged = true,
                consecutiveNotReady = 4,
                alreadyResetThisRun = true,
            ),
        )
    }
}
