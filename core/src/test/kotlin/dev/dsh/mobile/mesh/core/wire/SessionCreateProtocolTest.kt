package dev.dsh.mobile.mesh.core.wire

import dev.dsh.mobile.mesh.core.wire.dto.SessionCreateRequest
import dev.dsh.mobile.mesh.core.wire.dto.SessionCreateValue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SessionCreateProtocolTest {
    private val json = Json { encodeDefaults = false; explicitNulls = false }

    @Test fun `new session request omits optional identity for old hosts`() {
        val payload = json.encodeToString(SessionCreateRequest(workspaceId = "workspace-a"))
        assertFalse(payload.contains("sessionId"))
        assertFalse(payload.contains("agentPreset"))
        assertEquals("workspace-a", json.decodeFromString<SessionCreateRequest>(payload).workspaceId)
    }

    @Test fun `host may select different session identity and omit preset`() {
        val response = json.decodeFromString<SessionCreateValue>("""{"sessionId":"session-new"}""")
        assertEquals("session-new", response.sessionId)
        assertEquals(null, response.agentPreset)
    }
}
