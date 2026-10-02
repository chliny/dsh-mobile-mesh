package dev.dsh.mobile.mesh.ui.screens.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalAutoCreateTest {
    @Test fun createsOnlyAfterSuccessfulEmptyInitialListAndReadyRenderer() {
        assertFalse(shouldAutoCreateTerminal(false, true, 0))
        assertFalse(shouldAutoCreateTerminal(true, false, 0))
        assertTrue(shouldAutoCreateTerminal(true, true, 0))
    }

    @Test fun reusesExistingTerminalAndNeverCreatesAfterInitialDecision() {
        assertFalse(shouldAutoCreateTerminal(true, true, 1))
        assertFalse(shouldAutoCreateTerminal(false, true, 0))
    }
}
