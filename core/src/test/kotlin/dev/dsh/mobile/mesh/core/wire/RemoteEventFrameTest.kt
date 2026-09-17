package dev.dsh.mobile.mesh.core.wire

import dev.dsh.mobile.mesh.core.wire.dto.RemoteEventFrame
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteEventFrameTest {
    @Test
    fun waterfallKeepsGenerationClientIdOnlyInMemory() {
        val frame = RemoteEventFrame.Waterfall(
            event = "user-questions/request",
            eventId = "event-1",
            agentId = "session-1",
            request = JsonObject(emptyMap()),
            clientId = "client-1",
        )
        assertEquals("client-1", frame.clientId)
        val encoded = WireJson.encodeToString(RemoteEventFrame.serializer(), frame)
        assertEquals(false, encoded.contains("clientId"))
    }
}
