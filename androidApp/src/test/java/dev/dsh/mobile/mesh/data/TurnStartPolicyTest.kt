package dev.dsh.mobile.mesh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TurnStartPolicyTest {
    @Test
    fun `completed session drops remembered turn clock after foreground refresh`() {
        assertNull(
            resolvedTurnStartMillis(
                running = false,
                eventStartMillis = null,
                rememberedStartMillis = 1_000L,
                nowMillis = 9_000L,
            ),
        )
    }

    @Test
    fun `running turn prefers durable event start then remembered start`() {
        assertEquals(1_000L, resolvedTurnStartMillis(true, 1_000L, 2_000L, 9_000L))
        assertEquals(2_000L, resolvedTurnStartMillis(true, null, 2_000L, 9_000L))
        assertEquals(9_000L, resolvedTurnStartMillis(true, null, null, 9_000L))
    }
}
