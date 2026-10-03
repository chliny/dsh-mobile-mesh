package dev.dsh.mobile.mesh.ui.screens.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalInputFeedbackTest {
    @Test fun reportsInputThatCannotBeSentBeforeAttachOrWithoutControl() {
        assertEquals(TerminalInputFailure.UNAVAILABLE, terminalInputFailure(null, "cat .", 16))
    }

    @Test fun distinguishesFullQueueFromAcceptedInput() {
        val queue = TerminalInputQueue { }
        assertNull(terminalInputFailure(queue, ".", 16))
        repeat(255) { assertNull(terminalInputFailure(queue, "x", 16)) }
        assertEquals(TerminalInputFailure.FULL, terminalInputFailure(queue, "overflow", 16))
        queue.close()
    }
}
