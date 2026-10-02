package dev.dsh.mobile.mesh.ui.screens.main

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalKeysTest {
    @Test fun controlCharactersAndShiftTab() {
        assertEquals("\u0003", terminalKey("C", ctrl = true))
        assertEquals("c", terminalKey("C"))
        assertEquals("C", terminalKey("C", shift = true))
        assertEquals("\u001b", terminalKey("Esc"))
        assertEquals("\u001b[Z", terminalKey("Tab", shift = true))
        assertEquals("\u001c", terminalKey("\\", ctrl = true))
        assertEquals("\u007f", terminalKey("?", ctrl = true))
    }

    @Test fun arrowModifiersAndFunctionKeys() {
        assertEquals("\u001b[A", terminalKey("Up"))
        assertEquals("\u001b[1;6D", terminalKey("Left", ctrl = true, shift = true))
        assertEquals("\u001bOP", terminalKey("F1"))
        assertEquals("\u001b[24~", terminalKey("F12"))
        assertEquals("\u001b[1;3A", terminalKey("Up", alt = true))
        assertEquals("\u001b[3;6~", terminalKey("Delete", ctrl = true, shift = true))
        assertEquals("\u001b[1;6P", terminalKey("F1", ctrl = true, shift = true))
        assertEquals("\u001b[24;8~", terminalKey("F12", ctrl = true, shift = true, alt = true))
        assertEquals("\u001b\u0003", terminalKey("C", ctrl = true, alt = true))
    }
}
