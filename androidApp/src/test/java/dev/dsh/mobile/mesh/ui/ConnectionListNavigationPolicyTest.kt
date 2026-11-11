package dev.dsh.mobile.mesh.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionListNavigationPolicyTest {
    @Test
    fun `back preserves the page that opened connection list`() {
        assertEquals(ConnectionListOrigin.SESSION, connectionListBackTarget(ConnectionListOrigin.SESSION))
        assertEquals(ConnectionListOrigin.SETTINGS, connectionListBackTarget(ConnectionListOrigin.SETTINGS))
    }

    @Test
    fun `an active connect attempt leaves the list only toward settings`() {
        // Regression: Back was swallowed for every origin while connecting, so a user who opened
        // the list from settings could not get back to settings to watch the attempt continue.
        assertTrue(shouldAllowConnectionListBack(connecting = true, origin = ConnectionListOrigin.SETTINGS))
        // Otherwise Back must not reach a session page before the connection exists.
        assertFalse(shouldAllowConnectionListBack(connecting = true, origin = ConnectionListOrigin.SESSION))
        assertFalse(shouldAllowConnectionListBack(connecting = true, origin = ConnectionListOrigin.STARTUP))
    }

    @Test
    fun `back is unrestricted once the attempt has ended`() {
        assertTrue(shouldAllowConnectionListBack(connecting = false, origin = ConnectionListOrigin.SESSION))
        assertTrue(shouldAllowConnectionListBack(connecting = false, origin = ConnectionListOrigin.SETTINGS))
        assertTrue(shouldAllowConnectionListBack(connecting = false, origin = ConnectionListOrigin.STARTUP))
    }
}
