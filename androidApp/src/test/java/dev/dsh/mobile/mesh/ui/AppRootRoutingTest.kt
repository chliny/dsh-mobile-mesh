package dev.dsh.mobile.mesh.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRootRoutingTest {
    @Test
    fun `already connected host opens session list without reconnect selection`() {
        assertTrue(shouldRouteToSessionListAfterConnection(hasConnected = true, editingConnection = false))
        assertFalse(shouldRouteToSessionListAfterConnection(hasConnected = false, editingConnection = false))
        assertFalse(shouldRouteToSessionListAfterConnection(hasConnected = true, editingConnection = true))
    }

    @Test
    fun `selected host routes only after matching connection is ready`() {
        assertTrue(shouldRouteSelectedConnection(true, "host:3080", "host:3080", false, true))
        assertFalse(shouldRouteSelectedConnection(true, "host:3080", "other:3080", false, true))
        assertFalse(shouldRouteSelectedConnection(false, "host:3080", "host:3080", false, true))
    }
}
