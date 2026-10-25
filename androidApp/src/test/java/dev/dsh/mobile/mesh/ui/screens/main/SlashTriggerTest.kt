package dev.dsh.mobile.mesh.ui.screens.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlashTriggerTest {
    @Test fun `slash opens after existing text including newlines and with text after caret`() {
        assertEquals(SlashTrigger(6, 9, "pl"), slashTrigger("hello /pl done", 9))
        assertEquals(SlashTrigger(5, 6, ""), slashTrigger("hello/ world", 6))
        assertEquals(SlashTrigger(6, 9, "co"), slashTrigger("hello\n/co rest", 9))
    }

    @Test fun `each slash can open again and caret selects its own token`() {
        val draft = "first /plan then /comp end"
        assertEquals("plan", slashTrigger(draft, 11)?.query)
        assertEquals("comp", slashTrigger(draft, 22)?.query)
        assertNull(slashTrigger(draft, draft.length))
        assertNull(slashTrigger("plain words", 5))
        assertNull(slashTrigger("/plan done", 10))
        assertEquals("", slashTrigger("/", 1)?.query)
    }

    @Test fun `selection replaces only active command token without losing surrounding text`() {
        val draft = "before /pl after /co tail"
        val first = slashTrigger(draft, 10)!!
        assertEquals("before /plan  after /co tail", replaceSlashTrigger(draft, first, "/plan "))
        val second = slashTrigger(draft, 20)!!
        assertEquals("before /pl after /compact tail", replaceSlashTrigger(draft, second, "/compact"))
    }

    @Test fun `cancel preserves token and remains dismissed until next slash`() {
        val original = "/pl"
        val trigger = slashTrigger(original, original.length)!!
        val dismissed = SlashPickerState().dismiss(trigger)
        assertFalse(dismissed.visible(trigger))
        assertFalse(dismissed.onEdit(original, "/pla").visible(slashTrigger("/pla", 4)))
        assertFalse(dismissed.onEdit(original, "/p").visible(slashTrigger("/p", 2)))
        val next = "/pla /"
        assertTrue(dismissed.onEdit("/pla ", next).visible(slashTrigger(next, next.length)))
        assertEquals(0, dismissed.dismissedStart) // Dismissal retains the trigger, not a new draft.
    }
}
