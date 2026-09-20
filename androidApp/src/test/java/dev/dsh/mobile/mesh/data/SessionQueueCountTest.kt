package dev.dsh.mobile.mesh.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionQueueCountTest {
    @Test
    fun `queue count follows authoritative queued items`() {
        val row = SessionRow(
            sessionId = "session-1",
            title = "Session",
            running = true,
            blank = false,
            parentSessionId = null,
            origin = null,
            cwd = null,
            agentPreset = null,
            updatedAt = 0L,
            pendingInteraction = null,
            queuedCount = 3,
        )

        assertEquals(3, row.queuedCount)
    }
}
