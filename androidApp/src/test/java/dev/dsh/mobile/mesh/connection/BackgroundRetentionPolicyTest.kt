package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundRetentionPolicyTest {
    @Test
    fun `disabling retention while backgrounded suspends an active target`() {
        assertTrue(shouldSuspendAfterRetentionDisabled(
            keepConnectedInBackground = false, appInForeground = false,
            hasDesiredHost = true, activeFileTransfer = false, alreadySuspended = false,
        ))
        assertFalse(shouldSuspendAfterRetentionDisabled(true, false, true, false, false))
        assertFalse(shouldSuspendAfterRetentionDisabled(false, true, true, false, false))
        assertFalse(shouldSuspendAfterRetentionDisabled(false, false, false, false, false))
        assertFalse(shouldSuspendAfterRetentionDisabled(false, false, true, true, false))
        assertFalse(shouldSuspendAfterRetentionDisabled(false, false, true, false, true))
    }

    @Test
    fun `retention service starts only from foreground`() {
        assertTrue(shouldStartConnectionService(keepConnectedInBackground = true, appInForeground = true))
        assertFalse(shouldStartConnectionService(keepConnectedInBackground = true, appInForeground = false))
        assertFalse(shouldStartConnectionService(keepConnectedInBackground = false, appInForeground = true))
    }
}
