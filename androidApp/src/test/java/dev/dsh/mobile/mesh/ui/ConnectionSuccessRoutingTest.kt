package dev.dsh.mobile.mesh.ui

import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionSuccessRoutingTest {
    @Test
    fun `successful non-edit connection routes to session list`() {
        assertTrue(shouldRouteToSessionListAfterConnection(hasConnected = true, editingConnection = false))
    }
}
