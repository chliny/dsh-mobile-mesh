package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.core.wire.dto.SessionAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentContinuationPolicyTest {
    @Test
    fun `one-shot child is read-only`() {
        assertFalse(canContinue(SessionAddress.Subagent(parentSessionId = "parent", childSessionId = "child", mode = "one-shot")))
    }

    @Test
    fun `continuable child is writable`() {
        assertTrue(canContinue(SessionAddress.Subagent(parentSessionId = "parent", childSessionId = "child", mode = "continuable")))
    }

    @Test
    fun `prompt uses durable parent and child address`() {
        val request = subagentPromptRequest(
            SessionAddress.Subagent(parentSessionId = "parent", childSessionId = "child", mode = "continuable"),
            "request-1",
            "hello",
            "queue",
            "UTC",
        )
        assertEquals("parent", request.parentSessionId)
        assertEquals("child", request.childSessionId)
        assertEquals("continuable", request.mode)
        assertEquals("queue", request.delivery)
        assertEquals("hello", (request.content.single() as dev.dsh.mobile.mesh.core.wire.dto.PromptContentPart.Text).text)
    }
}
