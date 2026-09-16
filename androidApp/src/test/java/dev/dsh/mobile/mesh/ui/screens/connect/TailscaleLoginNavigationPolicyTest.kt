package dev.dsh.mobile.mesh.ui.screens.connect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TailscaleLoginNavigationPolicyTest {
    @Test
    fun `back cancels visible pending login`() {
        assertTrue(shouldCancelTailscaleLoginOnBack(loginVisible = true, authorizationPending = true))
    }

    @Test
    fun `back does not cancel when login is settled or absent`() {
        assertFalse(shouldCancelTailscaleLoginOnBack(loginVisible = false, authorizationPending = true))
        assertFalse(shouldCancelTailscaleLoginOnBack(loginVisible = true, authorizationPending = false))
    }
}
