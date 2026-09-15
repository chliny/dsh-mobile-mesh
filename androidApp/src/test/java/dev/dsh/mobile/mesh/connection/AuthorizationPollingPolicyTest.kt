package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorizationPollingPolicyTest {
    @Test
    fun `pending authorization polls inside bounded window`() {
        assertTrue(shouldContinueAuthorizationPolling(10_000, true, false, false, 120_000))
    }

    @Test
    fun `authorization polling stops at timeout`() {
        assertFalse(shouldContinueAuthorizationPolling(120_000, true, false, false, 120_000))
    }

    @Test
    fun `authorization polling stops after success or failure`() {
        assertFalse(shouldContinueAuthorizationPolling(10_000, false, true, false, 120_000))
        assertFalse(shouldContinueAuthorizationPolling(10_000, true, false, true, 120_000))
    }
}
