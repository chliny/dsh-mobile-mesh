package dev.dsh.mobile.mesh.core.wire

import org.junit.Assert.assertEquals
import org.junit.Test

class HarnessSessionTokenTest {
    @Test
    fun `token parser accepts startup URL`() {
        assertEquals("new-token", HarnessSession.tokenFrom("https://harness.local/?token=new-token"))
    }

    @Test
    fun `token parser accepts bare token`() {
        assertEquals("new-token", HarnessSession.tokenFrom("new-token"))
    }
}
