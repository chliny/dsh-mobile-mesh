package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.core.wire.dto.SessionAddress
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionAddressPolicyTest {
    @Test
    fun `ordinary session uses its own address`() {
        assertEquals(SessionAddress.Session(sessionId = "session-1"), sessionAddressFor("session-1", null, null))
    }

    @Test
    fun `subagent uses durable parent address`() {
        assertEquals(
            SessionAddress.Subagent(parentSessionId = "parent-1", childSessionId = "child-1", mode = "continuable"),
            sessionAddressFor("child-1", "subagent", "parent-1", "continuable"),
        )
    }
}
