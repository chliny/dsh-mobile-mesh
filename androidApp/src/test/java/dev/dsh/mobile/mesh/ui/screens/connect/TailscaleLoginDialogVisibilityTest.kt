package dev.dsh.mobile.mesh.ui.screens.connect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TailscaleLoginDialogVisibilityTest {
    @Test
    fun `dialog remains visible while login is pending`() {
        assertTrue(shouldShowTailscaleLogin("https://login.tailscale.com/a/example", authorizationPending = true))
    }

    @Test
    fun `dialog remains visible until login URL is cleared`() {
        assertTrue(shouldShowTailscaleLogin("https://login.tailscale.com/a/example", authorizationPending = false))
        assertFalse(shouldShowTailscaleLogin(null, authorizationPending = false))
    }

    @Test
    fun `dialog cannot show without login url`() {
        assertFalse(shouldShowTailscaleLogin(null, authorizationPending = true))
    }
}
