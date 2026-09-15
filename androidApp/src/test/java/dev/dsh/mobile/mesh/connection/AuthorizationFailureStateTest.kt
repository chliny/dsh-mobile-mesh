package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertEquals
import org.junit.Test

class AuthorizationFailureStateTest {
    @Test
    fun `transport retry failure preserves pending authorization identity`() {
        val state = ConnectionUiState(
            authorizationPending = "approval pending",
            tailscaleLoginUrl = "https://login.tailscale.com/a/example",
        )
        val preserved = state.copy(
            phase = ConnectionPhase.RECONNECTING,
            failure = dev.dsh.mobile.mesh.ui.screens.connect.ConnectFailure.Other("temporary transport failure"),
            authorizationPending = state.authorizationPending,
            tailscaleLoginUrl = state.tailscaleLoginUrl,
        )
        assertEquals("approval pending", preserved.authorizationPending)
        assertEquals("https://login.tailscale.com/a/example", preserved.tailscaleLoginUrl)
    }
}
