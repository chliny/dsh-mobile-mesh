package dev.dsh.mobile.mesh.ui.screens.connect

import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionSelectionPolicyTest {
    @Test
    fun `selecting a remembered host allows caller to reset stale attempt first`() {
        assertTrue(shouldResetBeforeSelectingHost(connecting = true))
        assertTrue(shouldResetBeforeSelectingHost(connecting = false))
    }
}
