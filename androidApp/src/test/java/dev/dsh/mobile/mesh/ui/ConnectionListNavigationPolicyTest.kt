package dev.dsh.mobile.mesh.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionListNavigationPolicyTest {
    @Test
    fun `back preserves the page that opened connection list`() {
        assertEquals(ConnectionListOrigin.SESSION, connectionListBackTarget(ConnectionListOrigin.SESSION))
        assertEquals(ConnectionListOrigin.SETTINGS, connectionListBackTarget(ConnectionListOrigin.SETTINGS))
    }
}
