package dev.dsh.mobile.mesh.ui.screens.connect

import dev.dsh.mobile.mesh.connection.ConnectStage
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStageDetailTest {
    @Test
    fun `all active connection stages have distinct progress positions`() {
        val stages = ConnectStage.entries.filter { it != ConnectStage.Idle && it != ConnectStage.Connected }
        assertTrue(stages.zipWithNext().all { (a, b) -> a.ordinal < b.ordinal })
    }
}
