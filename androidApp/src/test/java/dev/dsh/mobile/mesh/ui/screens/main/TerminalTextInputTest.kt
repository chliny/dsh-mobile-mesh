package dev.dsh.mobile.mesh.ui.screens.main

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalTextInputTest {
    @Test fun mixesTouchscreenControlWithPhoneKeyboard() {
        assertEquals("\u0003", terminalTextInput("c", ctrl = true, shift = false, alt = false))
        assertEquals("\u001b\u001a", terminalTextInput("z", ctrl = true, shift = true, alt = true))
        assertEquals("\u001ba", terminalTextInput("a", ctrl = false, shift = false, alt = true))
        assertEquals("A", terminalTextInput("a", ctrl = false, shift = true, alt = false))
    }

    @Test fun keepsImePhrasesEmojiAndPreencodedEscapeUntouched() {
        assertEquals("你好", terminalTextInput("你好", ctrl = true, shift = true, alt = true))
        assertEquals("😀", terminalTextInput("😀", ctrl = true, shift = true, alt = true))
        assertEquals("\u001b[A", terminalTextInput("\u001b[A", ctrl = true, shift = true, alt = true))
        assertEquals("\r", terminalTextInput("\r", ctrl = true, shift = true, alt = true))
    }
}
