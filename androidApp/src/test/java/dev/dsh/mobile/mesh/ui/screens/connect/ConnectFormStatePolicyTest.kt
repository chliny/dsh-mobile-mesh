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
    fun `connection mutation is blocked for connected or connecting host`() {
        assertFalse(shouldAllowConnectionMutation("host-a", "host-a", connecting = false))
        assertFalse(shouldAllowConnectionMutation("host-a", "host-b", connecting = true))
        assertTrue(shouldAllowConnectionMutation("host-a", "host-b", connecting = false))
        assertFalse(shouldAllowConnectionMutation(null, "host-b", connecting = false))
        assertFalse(shouldEnableConnectionSave("host-a", "host-a", connecting = false))
        assertFalse(shouldEnableConnectionSave("host-a", "host-b", connecting = true))
        assertTrue(shouldEnableConnectionSave(null, "host-b", connecting = true))
    }

    @Test
    fun `connect action is available only for a new form`() {
        assertTrue(shouldShowConnectAction(editingHostId = null, connectedHostId = "host-a"))
        assertFalse(shouldShowConnectAction(editingHostId = "host-a", connectedHostId = "host-a"))
        assertFalse(shouldShowConnectAction(editingHostId = "host-b", connectedHostId = "host-a"))
        assertTrue(shouldEnableConnectAction(fieldsEnabled = true, formValid = true))
        assertFalse(shouldEnableConnectAction(fieldsEnabled = true, formValid = false))
    }

    @Test
    fun `connect action is disabled while a request is in flight`() {
        assertFalse(shouldEnableConnectButton(connectInFlight = true, formValid = true))
        assertTrue(shouldEnableConnectButton(connectInFlight = false, formValid = true))
        assertFalse(shouldEnableConnectButton(connectInFlight = false, formValid = false))
    }
}
