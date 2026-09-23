package dev.dsh.mobile.mesh.ui.screens.connect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectSaveNavigationPolicyTest {
    @Test
    fun `new connection save returns to connection list when destination exists`() {
        assertTrue(shouldReturnToConnectionsAfterSave(isNewConnection = true, hasConnectionsDestination = true))
    }

    @Test
    fun `editing save stays on form and save without destination does not navigate`() {
        assertFalse(shouldReturnToConnectionsAfterSave(isNewConnection = false, hasConnectionsDestination = true))
        assertFalse(shouldReturnToConnectionsAfterSave(isNewConnection = true, hasConnectionsDestination = false))
    }
}
