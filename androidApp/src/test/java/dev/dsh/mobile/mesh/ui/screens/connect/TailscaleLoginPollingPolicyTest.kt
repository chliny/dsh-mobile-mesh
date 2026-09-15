package dev.dsh.mobile.mesh.ui.screens.connect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TailscaleLoginPollingPolicyTest {
    @Test
    fun `login URL starts automatic authorization polling`() {
        assertTrue(shouldPollTailscaleLogin("https://login.tailscale.com/a/example", pollActive = false))
    }

    @Test
    fun `active polling is not duplicated`() {
        assertFalse(shouldPollTailscaleLogin("https://login.tailscale.com/a/example", pollActive = true))
    }

    @Test
    fun `missing login URL does not poll`() {
        assertFalse(shouldPollTailscaleLogin(null, pollActive = false))
    }
}
