package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertEquals
import org.junit.Test

class SshRecoveryPolicyTest {
    @Test
    fun `recovery uses one immediate retry while initial connect keeps full retries`() {
        assertEquals(2, SSH_RECOVERY_CONNECT_ATTEMPTS)
        assertEquals(3, SSH_CONNECT_ATTEMPTS)
    }
}
