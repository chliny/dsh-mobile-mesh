package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertEquals
import org.junit.Test

class TransportTimeoutPolicyTest {
    @Test
    fun `ZeroTier recovery budget covers online and address waits`() {
        assertEquals(45_000L, transportOperationTimeoutMs(zeroTierConfig(), authorizationResume = false))
    }

    private fun zeroTierConfig() = HostConfig(
        id = "test",
        name = "test",
        host = "192.0.2.1",
        port = 3080,
        isLoopback = false,
        useTls = false,
        meshTransport = MeshTransport.ZERO_TIER,
        zeroTierNetworkId = "0123456789abcdef",
    )
}
