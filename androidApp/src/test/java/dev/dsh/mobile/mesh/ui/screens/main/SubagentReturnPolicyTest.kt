package dev.dsh.mobile.mesh.ui.screens.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubagentReturnPolicyTest {
    @Test
    fun `entering child from parent chat returns to parent`() {
        assertEquals("parent-session", subagentReturnSessionId("parent-session"))
    }

    @Test
    fun `entering child from session list has no parent chat return`() {
        assertNull(subagentReturnSessionId(null))
    }
}
