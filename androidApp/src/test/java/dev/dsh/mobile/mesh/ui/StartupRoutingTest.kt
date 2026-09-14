package dev.dsh.mobile.mesh.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupRoutingTest {
    @Test
    fun `cold start without an edit target shows connection list`() {
        assertTrue(shouldShowStartupConnections(hasConnected = false, editingConnection = false))
    }

    @Test
    fun `editing a saved connection does not use startup list routing`() {
        assertFalse(shouldShowStartupConnections(hasConnected = false, editingConnection = true))
    }

    @Test
    fun `connected app does not use startup list routing`() {
        assertFalse(shouldShowStartupConnections(hasConnected = true, editingConnection = false))
    }

    @Test
    fun `token update from remembered connection list opens root prompt`() {
        assertTrue(shouldShowConnectionTokenPrompt(
            showingConnections = true,
            startupConnections = false,
            signInOpen = true,
        ))
    }

    @Test
    fun `token prompt is not duplicated while editing connection form`() {
        assertFalse(shouldShowConnectionTokenPrompt(
            showingConnections = false,
            startupConnections = false,
            signInOpen = true,
        ))
    }
}
