package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorizationWebViewLifecyclePolicyTest {
    @Test
    fun `pending authorization defers foreground recovery`() {
        assertTrue(shouldDeferForegroundRecoveryForAuthorization(true, null))
    }

    @Test
    fun `retained login URL defers recovery after activity recreation`() {
        assertTrue(shouldDeferForegroundRecoveryForAuthorization(false, "https://login.tailscale.com/a/example"))
    }

    @Test
    fun `ordinary disconnected state can recover`() {
        assertFalse(shouldDeferForegroundRecoveryForAuthorization(false, null))
    }
}
