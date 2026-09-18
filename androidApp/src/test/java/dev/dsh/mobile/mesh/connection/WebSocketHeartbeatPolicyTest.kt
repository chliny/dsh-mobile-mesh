package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertEquals
import org.junit.Test

class WebSocketHeartbeatPolicyTest {
    @Test
    fun `ZeroTier gets a longer heartbeat window`() {
        assertEquals(30_000L, webSocketPingIntervalMs(MeshTransport.ZERO_TIER))
    }

    @Test
    fun `Tailscale and direct transports retain default heartbeat`() {
        assertEquals(10_000L, webSocketPingIntervalMs(MeshTransport.TAILSCALE))
        assertEquals(10_000L, webSocketPingIntervalMs(null))
    }
}
