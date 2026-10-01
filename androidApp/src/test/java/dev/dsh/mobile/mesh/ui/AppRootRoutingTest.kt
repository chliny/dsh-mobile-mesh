package dev.dsh.mobile.mesh.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRootRoutingTest {
    @Test
    fun `editing host id survives recreation and resolves from hosts flow`() {
        assertEquals("host-2", resolveEditingHostId("host-2", listOf("host-1", "host-2")))
        assertNull(resolveEditingHostId("removed", listOf("host-1", "host-2")))
    }

    @Test
    fun `selected host routes only after matching connection is ready`() {
        assertTrue(shouldRouteSelectedConnection(true, "host:3080", "host:3080", false, true))
        assertFalse(shouldRouteSelectedConnection(true, "host:3080", "other:3080", false, true))
        assertFalse(shouldRouteSelectedConnection(false, "host:3080", "host:3080", false, true))
    }

    @Test
    fun `killed app keeps pending first connection selection for successful recovery`() {
        // The selected host is saved with Compose state. On process recreation its successful
        // connection must still route to sessions rather than leave the user on startup connections.
        assertTrue(shouldRouteSelectedConnection(
            phaseConnected = true,
            selectedAuthority = "host:22",
            activeAuthority = "host:22",
            editing = false,
            awaitingSelectedConnection = true,
        ))
    }

    @Test
    fun `recovery without a pending selection does not route from connection list`() {
        assertFalse(shouldRouteSelectedConnection(
            phaseConnected = true,
            selectedAuthority = "host:22",
            activeAuthority = "host:22",
            editing = false,
            awaitingSelectedConnection = false,
        ))
    }

    @Test
    fun `recovery does not route existing detail page through session list`() {
        assertFalse(shouldRouteAfterRecovery(true, false))
        assertTrue(shouldRouteAfterRecovery(false, false))
        assertTrue(shouldRouteAfterRecovery(true, true))
    }
}
