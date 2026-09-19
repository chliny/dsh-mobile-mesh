package dev.dsh.mobile.mesh.ui.screens.connect

import dev.dsh.mobile.mesh.connection.ConnectStage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionProgressPolicyTest {
    @Test
    fun `active stages are eligible for row progress`() {
        assertTrue(shouldShowConnectionProgress(ConnectStage.Validating))
        assertTrue(shouldShowConnectionProgress(ConnectStage.Reaching))
        assertTrue(shouldShowConnectionProgress(ConnectStage.OpeningStreams))
        assertTrue(shouldShowConnectionProgress(ConnectStage.Verifying))
    }

    @Test
    fun `idle and connected stages do not show progress`() {
        assertFalse(shouldShowConnectionProgress(ConnectStage.Idle))
        assertFalse(shouldShowConnectionProgress(ConnectStage.Connected))
    }
}
