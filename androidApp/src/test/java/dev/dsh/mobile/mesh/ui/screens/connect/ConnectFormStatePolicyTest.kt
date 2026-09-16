package dev.dsh.mobile.mesh.ui.screens.connect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectFormStatePolicyTest {
    @Test
    fun `only the currently connected saved host is read-only`() {
        assertTrue(isEditingConnectedHost("host-a", "host-a"))
        assertFalse(isEditingConnectedHost("host-a", "host-b"))
        assertFalse(isEditingConnectedHost(null, "host-a"))
    }

    @Test
    fun `connect action is available only for a new form`() {
        assertTrue(shouldShowConnectAction(editingHostId = null, connectedHostId = "host-a"))
        assertFalse(shouldShowConnectAction(editingHostId = "host-a", connectedHostId = "host-a"))
        assertFalse(shouldShowConnectAction(editingHostId = "host-b", connectedHostId = "host-a"))
    }
}
