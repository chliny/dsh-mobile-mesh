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
    fun `recreated connection list does not treat recovery as explicit selection`() {
        // awaitingSelectedConnection is transient and intentionally not saveable: after a process
        // recreation it resets to false, so successful recovery cannot impersonate a user tap.
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
