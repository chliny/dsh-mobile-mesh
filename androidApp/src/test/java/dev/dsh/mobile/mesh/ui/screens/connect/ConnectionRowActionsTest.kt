package dev.dsh.mobile.mesh.ui.screens.connect

import dev.dsh.mobile.mesh.connection.ConnectionPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRowActionsTest {
    @Test
    fun `long press actions expose token update before edit and delete`() {
        assertEquals(
            listOf(ConnectionRowAction.DISCONNECT, ConnectionRowAction.UPDATE_TOKEN, ConnectionRowAction.EDIT, ConnectionRowAction.DELETE),
            connectionRowActionTitles(),
        )
    }

    @Test
    fun `edit and delete are disabled for connected or connecting row`() {
        assertFalse(canMutateConnectionRow("h1", "h1", null, ConnectionPhase.CONNECTED))
        assertFalse(canMutateConnectionRow("h1", null, "h1", ConnectionPhase.CONNECTING))
        assertTrue(canMutateConnectionRow("h2", "h1", null, ConnectionPhase.CONNECTED))
        assertTrue(canMutateConnectionRow("h1", null, null, ConnectionPhase.DISCONNECTED))
    }

    @Test
    fun `disconnect is enabled only for connected or connecting row`() {
        assertTrue(canDisconnectConnectionRow("h1", "h1", null, ConnectionPhase.CONNECTED))
        assertTrue(canDisconnectConnectionRow("h1", null, "h1", ConnectionPhase.CONNECTING))
        assertTrue(canDisconnectConnectionRow("h1", "h1", null, ConnectionPhase.RECONNECTING))
        assertFalse(canDisconnectConnectionRow("h2", "h1", null, ConnectionPhase.CONNECTED))
        assertFalse(canDisconnectConnectionRow("h1", null, null, ConnectionPhase.DISCONNECTED))
    }
}
