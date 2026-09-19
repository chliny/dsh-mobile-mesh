package dev.dsh.mobile.mesh.ui

import dev.dsh.mobile.mesh.connection.ConnectionPhase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRootPagePolicyTest {
    @Test
    fun `previously rendered session page survives transient disconnect`() {
        assertTrue(shouldKeepSessionPageDuringRecovery(true, "host", ConnectionPhase.DISCONNECTED))
        assertTrue(shouldKeepSessionPageDuringRecovery(true, "host", ConnectionPhase.RECONNECTING))
        assertTrue(shouldKeepSessionPageDuringRecovery(true, "host", ConnectionPhase.CONNECTING))
        assertFalse(shouldKeepSessionPageDuringRecovery(false, "host", ConnectionPhase.CONNECTING))
        assertFalse(shouldKeepSessionPageDuringRecovery(true, null, ConnectionPhase.RECONNECTING))
    }
}
