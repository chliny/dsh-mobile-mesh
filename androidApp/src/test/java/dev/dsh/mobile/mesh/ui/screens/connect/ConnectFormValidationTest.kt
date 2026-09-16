package dev.dsh.mobile.mesh.ui.screens.connect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectFormValidationTest {
    @Test
    fun `complete SSH form is connectable`() {
        assertTrue(isConnectFormValid("gmk.tailscale.chliny.me", "3080", true, "testuser", "22", "127.0.0.1", "token"))
    }

    @Test
    fun `incomplete SSH form is not connectable`() {
        assertFalse(isConnectFormValid("gmk.tailscale.chliny.me", "3080", true, "", "22", "127.0.0.1", "token"))
    }
}
