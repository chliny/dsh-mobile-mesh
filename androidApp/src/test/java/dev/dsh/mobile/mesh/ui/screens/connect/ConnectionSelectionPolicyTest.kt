package dev.dsh.mobile.mesh.ui.screens.connect

import dev.dsh.mobile.mesh.connection.ConnectionPhase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionSelectionPolicyTest {
    @Test
    fun `selecting a remembered host resets only an active attempt`() {
        assertTrue(shouldResetBeforeSelectingHost(connecting = true))
        assertFalse(shouldResetBeforeSelectingHost(connecting = false))
    }

    @Test
    fun `current connected host opens sessions without reconnecting`() {
        assertTrue(shouldOpenSessionsForCurrentHost("a", "a", ConnectionPhase.CONNECTED))
        assertFalse(shouldOpenSessionsForCurrentHost("a", "b", ConnectionPhase.CONNECTED))
        assertFalse(shouldOpenSessionsForCurrentHost("a", "a", ConnectionPhase.RECONNECTING))
    }
}
