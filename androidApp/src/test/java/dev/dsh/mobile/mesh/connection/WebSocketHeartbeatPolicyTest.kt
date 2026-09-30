package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertEquals
import org.junit.Test

class WebSocketHeartbeatPolicyTest {
    @Test
    fun `all transports derive a bounded interval from first latency`() {
        assertEquals(40_000L, webSocketPingIntervalMs(MeshTransport.TAILSCALE, 1_250L))
        assertEquals(40_000L, webSocketPingIntervalMs(null, 1_250L))
        assertEquals(30_008L, adaptivePingIntervalMs(1L))
        assertEquals(180_000L, adaptivePingIntervalMs(60_000L))
    }

    @Test
    fun `latency sample converts nanoseconds to milliseconds`() {
        assertEquals(1_250L, heartbeatLatencySample(10_000_000L, 1_260_000_000L))
    }

    @Test
    fun `ZeroTier relay heartbeat is not derived from slow initial latency`() {
        assertEquals(180_000L, webSocketPingIntervalMs(MeshTransport.ZERO_TIER, 5_299L))
    }

    @Test
    fun `other transports use the adaptive heartbeat`() {
        assertEquals(180_000L, webSocketPingIntervalMs(MeshTransport.TAILSCALE))
        assertEquals(180_000L, webSocketPingIntervalMs(null))
    }
}
