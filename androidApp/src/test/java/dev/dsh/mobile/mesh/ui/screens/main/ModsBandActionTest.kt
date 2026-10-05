package dev.dsh.mobile.mesh.ui.screens.main

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModsBandActionTest {
    private fun node(json: String) = Json.parseToJsonElement(json)

    @Test fun `only actionable enabled buttons can send an action`() {
        assertEquals("a0", modsBandAction(node("""{"type":"Button","actionId":"a0","props":{"label":"Proceed"}}""")))
        assertNull(modsBandAction(node("""{"type":"Button","props":{"label":"Info"}}""")))
        assertNull(modsBandAction(node("""{"type":"Button","actionId":"a0","props":{"disabled":true}}""")))
        assertNull(modsBandAction(node("""{"type":"Text","actionId":"a0"}""")))
    }
}
