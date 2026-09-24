package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertEquals
import org.junit.Test

class ZeroTierPlanetFallbackPolicyTest {
    @Test
    fun `remembering same ZeroTier host retains imported planet when caller omits optional value`() {
        assertEquals(
            "planet-hash",
            retainedZeroTierPlanetId(
                transport = MeshTransport.ZERO_TIER,
                requestedPlanetId = null,
                existingPlanetId = "planet-hash",
            ),
        )
    }

    @Test
    fun `explicit new planet replaces previous planet`() {
        assertEquals(
            "new-planet",
            retainedZeroTierPlanetId(MeshTransport.ZERO_TIER, "new-planet", "old-planet"),
        )
    }

    @Test
    fun `non ZeroTier transport drops planet`() {
        assertEquals(
            null,
            retainedZeroTierPlanetId(MeshTransport.TAILSCALE, "planet-hash", "planet-hash"),
        )
    }
}
