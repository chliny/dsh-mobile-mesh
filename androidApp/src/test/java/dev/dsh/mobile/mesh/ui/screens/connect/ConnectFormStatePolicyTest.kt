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
        assertFalse(
            shouldAllowConnectionMutation(
                "host-a", "host-a", connecting = false,
                phase = dev.dsh.mobile.mesh.connection.ConnectionPhase.CONNECTED,
            ),
        )
        assertFalse(shouldAllowConnectionMutation("host-a", "host-b", connecting = true))
        assertTrue(shouldAllowConnectionMutation("host-a", "host-b", connecting = false))
        assertFalse(shouldAllowConnectionMutation(null, "host-b", connecting = false))
        assertFalse(
            shouldEnableConnectionSave(
                "host-a", "host-a", connecting = false,
                phase = dev.dsh.mobile.mesh.connection.ConnectionPhase.CONNECTED,
            ),
        )
        assertFalse(shouldEnableConnectionSave("host-a", "host-b", connecting = true))
        assertFalse(shouldEnableConnectionSave(null, "host-b", connecting = true))
    }

    @Test
    fun `same host can be edited after disconnect or failed connection`() {
        assertTrue(shouldAllowConnectionMutation("host-a", "host-a", connecting = false))
        assertTrue(shouldEnableConnectionSave("host-a", "host-a", connecting = false))
        assertTrue(
            shouldAllowConnectionMutation(
                "host-a",
                "host-a",
                connecting = false,
                phase = dev.dsh.mobile.mesh.connection.ConnectionPhase.CONNECTING,
            ),
        )
        assertFalse(
            shouldAllowConnectionMutation(
                "host-a",
                "host-a",
                connecting = false,
                phase = dev.dsh.mobile.mesh.connection.ConnectionPhase.RECONNECTING,
            ),
        )
        assertFalse(
            shouldAllowConnectionMutation(
                "host-a", "host-a", connecting = false,
                phase = dev.dsh.mobile.mesh.connection.ConnectionPhase.CONNECTED,
            ),
        )
        assertFalse(shouldEnableConnectionSave(null, "host-a", connecting = true))
        assertTrue(
            shouldAllowConnectionMutation(
                "host-b", "host-a", connecting = false,
                phase = dev.dsh.mobile.mesh.connection.ConnectionPhase.CONNECTED,
            ),
        )
    }

    @Test
    fun `connecting another host does not lock this host editor`() {
        assertFalse(
            shouldLockConnectionEditor(
                isEditingCurrentConnection = false,
                isEditingAttemptedConnection = false,
                phase = dev.dsh.mobile.mesh.connection.ConnectionPhase.CONNECTING,
            ),
        )
        assertTrue(
            shouldLockConnectionEditor(
                isEditingCurrentConnection = false,
                isEditingAttemptedConnection = true,
                phase = dev.dsh.mobile.mesh.connection.ConnectionPhase.CONNECTING,
            ),
        )
        assertTrue(
            shouldLockConnectionEditor(
                isEditingCurrentConnection = true,
                isEditingAttemptedConnection = false,
                phase = dev.dsh.mobile.mesh.connection.ConnectionPhase.CONNECTED,
            ),
        )
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
