package dev.dsh.mobile.mesh.ui.screens.main

import dev.dsh.mobile.mesh.core.session.ToolResultNode
import dev.dsh.mobile.mesh.ui.components.DisclosureState
import dev.dsh.mobile.mesh.ui.components.ToolCardView
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolDisclosureStateTest {
    private fun result(error: Boolean = false) = ToolResultNode(2, "call", null, error, 1, 0)

    @Test fun `nonzero terminal exit and signal show error even with successful tool result`() {
        assertEquals(DisclosureState.Error, toolDisclosureState(ToolCardView.TerminalCard(exitCode = 2), result(), false))
        assertEquals(DisclosureState.Error, toolDisclosureState(ToolCardView.TerminalCard(signal = "SIGTERM"), result(), false))
        assertEquals(DisclosureState.Idle, toolDisclosureState(ToolCardView.TerminalCard(exitCode = 0), result(), false))
    }

    @Test fun `missing result remains running and transport error takes precedence`() {
        assertEquals(DisclosureState.Running, toolDisclosureState(ToolCardView.TerminalCard(), null, true))
        assertEquals(DisclosureState.Error, toolDisclosureState(ToolCardView.TerminalCard(exitCode = 0), result(true), false))
    }
}
