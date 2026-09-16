package dev.dsh.mobile.mesh.ui.screens.connect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoConnectPolicyTest {
    @Test
    fun `private network host is not eligible for automatic legacy SSH restore`() {
        assertTrue(shouldSkipLegacySshRestore(meshTransportStoredValue = "tailscale"))
        assertTrue(shouldSkipLegacySshRestore(meshTransportStoredValue = "zerotier"))
        assertFalse(shouldSkipLegacySshRestore(meshTransportStoredValue = null))
    }
}
