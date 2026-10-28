package dev.dsh.mobile.mesh.core.wire

import dev.dsh.mobile.mesh.core.wire.dto.SessionCreateRequest
import dev.dsh.mobile.mesh.core.wire.dto.SessionCreateValue
import dev.dsh.mobile.mesh.core.wire.dto.WorkspaceCreateRequest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    @Test fun `workspace creation sends the selected host path`() {
        assertEquals("""{"path":"/work/new"}""", json.encodeToString(WorkspaceCreateRequest("/work/new")))
    }

    @Test fun `workspace session creation sends id without cwd and home creation sends neither`() {
        val selected = json.parseToJsonElement(json.encodeToString(SessionCreateRequest(workspaceId = "workspace-a")))
            .jsonObject
        assertEquals("workspace-a", selected["workspaceId"]?.jsonPrimitive?.content)
        assertFalse("cwd" in selected)

        val home = json.parseToJsonElement(json.encodeToString(SessionCreateRequest())).jsonObject
        assertFalse("workspaceId" in home)
        assertFalse("cwd" in home)
    }

    @Test fun `host may select different session identity and omit preset`() {
        val response = json.decodeFromString<SessionCreateValue>("""{"sessionId":"session-new"}""")
        assertEquals("session-new", response.sessionId)
        assertEquals(null, response.agentPreset)
    }
}
