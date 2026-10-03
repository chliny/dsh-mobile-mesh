package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun `background-enabled carrier recovery keeps connection service running`() {
        assertFalse(
            shouldStopConnectionServiceOnRetire(
                reconnecting = true,
                keepConnectedInBackground = true,
            ),
        )
    }

    @Test
    fun `ordinary replacement or disabled retention stops connection service`() {
        assertTrue(
            shouldStopConnectionServiceOnRetire(
                reconnecting = false,
                keepConnectedInBackground = true,
            ),
        )
        assertTrue(
            shouldStopConnectionServiceOnRetire(
                reconnecting = true,
                keepConnectedInBackground = false,
            ),
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
