package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundConnectionPolicyTest {
    @Test
    fun `background-disabled connection is suspended on stop`() {
        assertEquals(
            BackgroundConnectionAction.SUSPEND,
            backgroundConnectionAction(keepConnectedInBackground = false),
        )
    }

    @Test
    fun `background-enabled connection retains carrier on stop`() {
        assertEquals(
            BackgroundConnectionAction.RETAIN,
            backgroundConnectionAction(keepConnectedInBackground = true),
        )
    }
}
