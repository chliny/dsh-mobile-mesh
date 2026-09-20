package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportSwitchPolicyTest {
    @Test
    fun `same Tailscale transport is reused for faster reconnect`() {
        assertFalse(shouldStopMeshBeforeConnect(MeshTransport.TAILSCALE, MeshTransport.TAILSCALE, false))
    }

    @Test
    fun `same Tailscale transport renews relay without native teardown`() {
        assertTrue(shouldRenewTailscaleRelay(activeTransport = MeshTransport.TAILSCALE, nextTransport = MeshTransport.TAILSCALE))
        assertFalse(shouldRenewTailscaleRelay(activeTransport = MeshTransport.ZERO_TIER, nextTransport = MeshTransport.TAILSCALE))
        assertFalse(shouldRenewTailscaleRelay(activeTransport = MeshTransport.TAILSCALE, nextTransport = MeshTransport.ZERO_TIER))
    }

    @Test
    fun `same ZeroTier transport is reused for faster host switch`() {
        assertFalse(shouldStopMeshBeforeConnect(MeshTransport.ZERO_TIER, MeshTransport.ZERO_TIER, false))
    }

    @Test
    fun `cross transport switch fully stops previous stack`() {
        assertTrue(shouldStopMeshBeforeConnect(MeshTransport.TAILSCALE, MeshTransport.ZERO_TIER, false))
        assertTrue(shouldStopMeshBeforeConnect(MeshTransport.ZERO_TIER, MeshTransport.TAILSCALE, false))
    }

    @Test
    fun `authorization resume preserves pending native identity`() {
        assertFalse(shouldStopMeshBeforeConnect(MeshTransport.TAILSCALE, MeshTransport.TAILSCALE, true))
    }
}
