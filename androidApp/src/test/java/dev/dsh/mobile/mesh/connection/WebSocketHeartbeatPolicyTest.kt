package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertEquals
import org.junit.Test

class WebSocketHeartbeatPolicyTest {
    @Test
    fun `ZeroTier starts with a long heartbeat window`() {
        assertEquals(60_000L, webSocketPingIntervalMs(MeshTransport.ZERO_TIER))
    }

    @Test
    fun `ZeroTier derives a bounded interval from first latency`() {
        assertEquals(40_000L, zeroTierPingIntervalMs(1_250L))
        assertEquals(30_008L, zeroTierPingIntervalMs(1L))
        assertEquals(180_000L, zeroTierPingIntervalMs(60_000L))
    }

    @Test
    fun `latency sample converts nanoseconds to milliseconds`() {
        assertEquals(1_250L, heartbeatLatencySample(10_000_000L, 1_260_000_000L))
    }

    @Test
    fun `Tailscale and direct transports retain default heartbeat`() {
        assertEquals(10_000L, webSocketPingIntervalMs(MeshTransport.TAILSCALE))
        assertEquals(10_000L, webSocketPingIntervalMs(null))
    }
}
