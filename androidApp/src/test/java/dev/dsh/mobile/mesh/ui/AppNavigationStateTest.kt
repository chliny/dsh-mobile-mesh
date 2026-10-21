package dev.dsh.mobile.mesh.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNavigationStateTest {
    @Test
    fun `notification extra wins and deep link session is parsed`() {
        assertEquals("extra", notificationSessionId(" extra ", "dshmobile://host/current/session/link"))
        assertEquals("link", notificationSessionId(null, "dshmobile://host/current/session/link"))
        assertNull(notificationSessionId(null, "dshmobile://host/current"))
    }

    @Test
    fun `each cold or new intent request can be consumed once`() {
        val state = AppNavigationState()
        state.requestSession("cold", "host-a")
        val cold = state.sessionRoute.value!!
        assertEquals("cold", cold.sessionId)
        assertEquals("host-a", cold.hostId)
        state.consume(cold)
        assertNull(state.sessionRoute.value)

        state.requestSession("warm", "host-b")
        val warm = state.sessionRoute.value!!
        assertEquals("warm", warm.sessionId)
        assertEquals("host-b", warm.hostId)
        assertTrue(warm.requestId > cold.requestId)
    }

    @Test
    fun `notification from another connection is rejected instead of opening missing session`() {
        val state = AppNavigationState()
        state.requestSession("session-on-a", "host-a")
        val route = state.sessionRoute.value!!
        assertEquals(
            NotificationRouteDecision.DIFFERENT_CONNECTION,
            notificationRouteDecision(route.hostId, "host-b", hasConnected = true),
        )
        state.consume(route)
        assertNull(state.sessionRoute.value)
        // Old notifications without an origin must not be routed onto an arbitrary host.
        assertEquals(
            NotificationRouteDecision.DIFFERENT_CONNECTION,
            notificationRouteDecision(null, "host-b", hasConnected = true),
        )
    }

    @Test
    fun `notification waits for its own connection to reconnect`() {
        assertEquals(
            NotificationRouteDecision.WAIT,
            notificationRouteDecision("host-a", null, hasConnected = false),
        )
        assertEquals(
            NotificationRouteDecision.WAIT,
            notificationRouteDecision("host-a", "host-a", hasConnected = false),
        )
        assertEquals(
            NotificationRouteDecision.OPEN,
            notificationRouteDecision("host-a", "host-a", hasConnected = true),
        )
        assertEquals(
            NotificationRouteDecision.DIFFERENT_CONNECTION,
            notificationRouteDecision("host-a", "host-b", hasConnected = false),
        )
    }

    @Test
    fun `notification suppression requires started activity and visible matching chat`() {
        val state = AppNavigationState()
        state.setVisibleChatSession("target")
        assertFalse(state.isChatVisible("target"))
        state.setActivityStarted(true)
        assertTrue(state.isChatVisible("target"))
        assertFalse(state.isChatVisible("other"))
        state.setVisibleChatSession(null)
        assertFalse(state.isChatVisible("target"))
    }
}
