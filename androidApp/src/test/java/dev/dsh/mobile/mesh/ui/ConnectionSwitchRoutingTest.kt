package dev.dsh.mobile.mesh.ui

import dev.dsh.mobile.mesh.ui.screens.connect.connectionAttemptAuthority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
    fun `routes imported ssh mesh profile using ssh endpoint authority`() {
        val profileHost = "gmk.tailscale.chliny.me"
        val selectedAuthority = connectionAttemptAuthority(
            host = profileHost,
            harnessPort = 3080,
            sshEnabled = true,
            sshPort = 22,
        )
        val activeAuthority = connectionAttemptAuthority(
            host = profileHost,
            harnessPort = 3080,
            sshEnabled = true,
            sshPort = 22,
        )

        assertEquals("$profileHost:22", selectedAuthority)
        assertNotEquals("$profileHost:3080", activeAuthority) // Harness port is not the SSH authority.
        assertFalse(shouldRouteSelectedConnection(
            phaseConnected = true,
            selectedAuthority = selectedAuthority,
            activeAuthority = "$profileHost:3080", // Regression: pre-fix production authority.
            editing = false,
            awaitingSelectedConnection = true,
        ))
        assertTrue(shouldRouteSelectedConnection(
            phaseConnected = true,
            selectedAuthority = selectedAuthority,
            activeAuthority = activeAuthority,
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
