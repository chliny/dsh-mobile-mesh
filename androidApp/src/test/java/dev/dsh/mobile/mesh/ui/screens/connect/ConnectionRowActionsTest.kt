package dev.dsh.mobile.mesh.ui.screens.connect

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionRowActionsTest {
    @Test
    fun `long press actions expose token update before edit and delete`() {
        assertEquals(
            listOf(ConnectionRowAction.UPDATE_TOKEN, ConnectionRowAction.EDIT, ConnectionRowAction.DELETE),
            connectionRowActionTitles(),
        )
    }
}
