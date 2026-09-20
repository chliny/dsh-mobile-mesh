package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertEquals
import org.junit.Test

class TailscaleNetworkWaitPolicyTest {
    @Test
    fun `network wait uses bounded retry window`() {
        assertEquals(40, TAILSCALE_NETWORK_WAIT_ATTEMPTS)
        assertEquals(250L, TAILSCALE_NETWORK_WAIT_DELAY_MS)
    }
}
