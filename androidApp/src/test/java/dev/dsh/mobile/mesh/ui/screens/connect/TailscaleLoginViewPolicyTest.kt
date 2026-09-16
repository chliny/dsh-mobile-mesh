package dev.dsh.mobile.mesh.ui.screens.connect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TailscaleLoginViewPolicyTest {
    @Test
    fun `redirected WebView is not reset to original login url`() {
        assertFalse(shouldLoadTailscaleLoginUrl("https://login.tailscale.com/a/redirect"))
    }

    @Test
    fun `new WebView loads login url`() {
        assertTrue(shouldLoadTailscaleLoginUrl(null))
        assertTrue(shouldLoadTailscaleLoginUrl(""))
    }
}
