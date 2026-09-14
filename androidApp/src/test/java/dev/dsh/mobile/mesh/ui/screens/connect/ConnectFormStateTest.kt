package dev.dsh.mobile.mesh.ui.screens.connect

import dev.dsh.mobile.mesh.connection.HostConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectFormStateTest {
    @Test
    fun `new connection does not restore the draft when explicitly disabled`() {
        assertFalse(shouldRestoreConnectionDraft(null, restoreDraft = false, draftAvailable = true))
    }

    @Test
    fun `new connection restores an available draft by default`() {
        assertTrue(shouldRestoreConnectionDraft(null, restoreDraft = true, draftAvailable = true))
    }

    @Test
    fun `editing an existing connection never restores the new connection draft`() {
        val host = HostConfig(id = "saved", name = "Saved", host = "192.0.2.1", port = 3080)
        assertFalse(shouldRestoreConnectionDraft(host, restoreDraft = true, draftAvailable = true))
    }
}
