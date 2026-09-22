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
        state.requestSession("cold")
        val cold = state.sessionRoute.value!!
        assertEquals("cold", cold.sessionId)
        state.consume(cold)
        assertNull(state.sessionRoute.value)

        state.requestSession("warm")
        val warm = state.sessionRoute.value!!
        assertEquals("warm", warm.sessionId)
        assertTrue(warm.requestId > cold.requestId)
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
