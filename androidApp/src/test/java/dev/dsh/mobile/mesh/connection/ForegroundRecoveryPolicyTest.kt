package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundRecoveryPolicyTest {
    @Test
    fun `duplicate lifecycle callbacks are ignored`() {
        assertTrue(shouldHandleLifecycleTransition(isForeground = false, targetForeground = true))
        assertTrue(shouldHandleLifecycleTransition(isForeground = true, targetForeground = false))
        assertFalse(shouldHandleLifecycleTransition(isForeground = true, targetForeground = true))
        assertFalse(shouldHandleLifecycleTransition(isForeground = false, targetForeground = false))
    }
}
