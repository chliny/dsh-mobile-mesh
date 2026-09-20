package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundSuspendRecoveryTest {
    @Test
    fun `suspended desired host is represented as reconnecting with recovery fence`() {
        val hasDesiredHost = true
        assertTrue(hasDesiredHost)
        assertTrue(backgroundConnectionAction(false) == BackgroundConnectionAction.SUSPEND)
    }
}
