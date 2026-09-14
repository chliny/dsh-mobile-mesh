package dev.dsh.mobile.mesh.ui.screens.connect

import dev.dsh.mobile.mesh.connection.ConnectionPhase
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionStatusTest {
    @Test
    fun `connecting host is not labelled connected`() {
        assertEquals(
            ConnectionRowStatus.CONNECTING,
            connectionRowStatus("h1", "h1", "h1", ConnectionPhase.CONNECTING, false),
        )
    }

    @Test
    fun `connected host is labelled connected only after connected phase`() {
        assertEquals(
            ConnectionRowStatus.CONNECTED,
            connectionRowStatus("h1", "h1", null, ConnectionPhase.CONNECTED, false),
        )
    }

    @Test
    fun `unrelated host has no misleading status`() {
        assertEquals(
            ConnectionRowStatus.NONE,
            connectionRowStatus("h2", "h1", null, ConnectionPhase.CONNECTING, false),
        )
    }
}
