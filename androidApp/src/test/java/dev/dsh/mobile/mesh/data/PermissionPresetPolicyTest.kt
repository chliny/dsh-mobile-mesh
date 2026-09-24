package dev.dsh.mobile.mesh.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionPresetPolicyTest {
    @Test
    fun `successful current session command releases pending preset`() {
        assertTrue(shouldClearPendingPermission("session-a", "session-a", "workspace-write", "workspace-write"))
    }

    @Test
    fun `command result from another session does not clear current pending preset`() {
        assertFalse(shouldClearPendingPermission("session-b", "session-a", "workspace-write", "workspace-write"))
    }

    @Test
    fun `older command result does not clear a newer pending preset`() {
        assertFalse(shouldClearPendingPermission("session-a", "session-a", "full-access", "workspace-write"))
    }
}
