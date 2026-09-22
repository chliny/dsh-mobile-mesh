package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TailscaleActiveNetworkCapabilityTest {
    @Test
    fun `existing active network is accepted only with internet capability`() {
        assertTrue(isUsableTailscaleNetwork(hasActiveNetwork = true, hasInternetCapability = true))
        assertFalse(isUsableTailscaleNetwork(hasActiveNetwork = true, hasInternetCapability = false))
        assertFalse(isUsableTailscaleNetwork(hasActiveNetwork = false, hasInternetCapability = true))
    }
}
