package dev.dsh.mobile.mesh.ui.screens.connect

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionSaveFeedbackTest {
    @Test
    fun `successful save uses success message`() {
        assertEquals("saved", connectionSaveFeedback(null, "saved", "failed"))
    }

    @Test
    fun `failed save includes the error message`() {
        assertEquals("failed: disk full", connectionSaveFeedback(IllegalStateException("disk full"), "saved", "failed"))
    }
}
