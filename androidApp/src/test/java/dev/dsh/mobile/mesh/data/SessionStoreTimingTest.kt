package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.core.session.SessionEventEnvelope
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionStoreTimingTest {
    @Test
    fun `running turn start is reconstructed from durable event time`() {
        val events = listOf(
            event("turn/start", 1_000L),
            event("assistant/message", 4_000L),
        )
        assertEquals(1_000L, runningTurnStartMillis(events))
    }

    @Test
    fun `closed turn has no active start`() {
        val events = listOf(event("turn/start", 1_000L), event("turn/end", 9_000L))
        assertNull(runningTurnStartMillis(events))
    }

    private fun event(type: String, time: Long) = SessionEventEnvelope(
        type = type,
        seq = time,
        time = time,
        data = buildJsonObject {},
    )
}
