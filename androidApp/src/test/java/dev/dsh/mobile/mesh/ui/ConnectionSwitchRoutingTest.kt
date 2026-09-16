package dev.dsh.mobile.mesh.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionSwitchRoutingTest {
    @Test
    fun `does not route while old connection is still connected to another host`() {
        assertFalse(shouldRouteSelectedConnection(
            phaseConnected = true,
            selectedAuthority = "new-host:8080",
            activeAuthority = "old-host:8080",
            editing = false,
            awaitingSelectedConnection = true,
        ))
    }

    @Test
    fun `routes only when selected host is the active connected host`() {
        assertTrue(shouldRouteSelectedConnection(
            phaseConnected = true,
            selectedAuthority = "new-host:8080",
            activeAuthority = "new-host:8080",
            editing = false,
            awaitingSelectedConnection = true,
        ))
    }

    @Test
    fun `background reconnect preserves the current page`() {
        assertFalse(shouldRouteSelectedConnection(
            phaseConnected = true,
            selectedAuthority = "host:8080",
            activeAuthority = "host:8080",
            editing = false,
            awaitingSelectedConnection = false,
        ))
    }
}
