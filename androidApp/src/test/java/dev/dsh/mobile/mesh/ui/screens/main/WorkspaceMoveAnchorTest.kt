package dev.dsh.mobile.mesh.ui.screens.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkspaceMoveAnchorTest {
    private val ids = listOf("first", "second", "third")

    @Test fun `moving up inserts before previous workspace`() {
        assertEquals("first", workspaceMoveAnchor(ids, "second", down = false))
        assertEquals("second", workspaceMoveAnchor(ids, "third", down = false))
    }

    @Test fun `moving down inserts before next next workspace or appends`() {
        assertEquals("third", workspaceMoveAnchor(ids, "first", down = true))
        assertNull(workspaceMoveAnchor(ids, "second", down = true))
    }

    @Test fun `missing workspace and boundary moves have no anchor`() {
        assertNull(workspaceMoveAnchor(ids, "missing", down = true))
        assertNull(workspaceMoveAnchor(ids, "first", down = false))
        assertNull(workspaceMoveAnchor(ids, "third", down = true))
    }
}
