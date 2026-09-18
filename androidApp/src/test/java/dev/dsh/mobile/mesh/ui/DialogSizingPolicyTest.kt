package dev.dsh.mobile.mesh.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DialogSizingPolicyTest {
    @Test
    fun `regular dialogs use content sizing`() {
        assertEquals(false, dialogUsesFullScreen(false))
        assertEquals(true, dialogUsesFullScreen(true))
    }
}

internal fun dialogUsesFullScreen(fullScreen: Boolean): Boolean = fullScreen
