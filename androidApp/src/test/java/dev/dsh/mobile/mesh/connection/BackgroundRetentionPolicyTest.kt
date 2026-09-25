package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundRetentionPolicyTest {
    @Test
    fun `retention service starts only from foreground`() {
        assertTrue(shouldStartConnectionService(keepConnectedInBackground = true, appInForeground = true))
        assertFalse(shouldStartConnectionService(keepConnectedInBackground = true, appInForeground = false))
        assertFalse(shouldStartConnectionService(keepConnectedInBackground = false, appInForeground = true))
    }
}
