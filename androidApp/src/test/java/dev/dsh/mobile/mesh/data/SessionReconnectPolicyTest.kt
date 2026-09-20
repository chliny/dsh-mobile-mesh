package dev.dsh.mobile.mesh.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionReconnectPolicyTest {
    @Test
    fun `selected session reopens only after a new connection generation exists`() {
        assertTrue(shouldReopenSessionAfterReconnect(true, true))
        assertFalse(shouldReopenSessionAfterReconnect(true, false))
        assertFalse(shouldReopenSessionAfterReconnect(false, true))
    }
}
