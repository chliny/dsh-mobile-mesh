package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionTimeoutPolicyTest {
    @Test
    fun `authorization and transport callback budgets allow browser sign in`() {
        assertTrue(ConnectionTimeoutPolicy.authorizationResumeMs >= 60_000L)
        assertTrue(ConnectionTimeoutPolicy.transportReadyCallbackMs >= 60_000L)
    }
}

