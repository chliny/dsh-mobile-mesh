package dev.dsh.mobile.mesh.ui.screens.main

import dev.dsh.mobile.mesh.core.wire.dto.WorkspaceDirectoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MentionTriggerTest {
    @Test fun `at references work amid preceding and following text and at earlier caret`() {
        val draft = "open @src/ma next @docs/hi end"
        assertEquals(MentionTrigger(5, 12, "src/ma", false), mentionTrigger(draft, 12))
        assertEquals(MentionTrigger(18, 26, "docs/hi", false), mentionTrigger(draft, 26))
        assertNull(mentionTrigger(draft, draft.length))
        assertEquals("", mentionQueryForDraft("before@"))
    }

    @Test fun `directory selection keeps slash open to descend through nested directories`() {
        val original = "please @sr later"
        val trigger = mentionTrigger(original, 10)!!
        val directory = fileMention(WorkspaceDirectoryEntry("src", "directory"), trigger.quoted)!!
        assertEquals("@src/", directory)
        val next = replaceMentionTrigger(original, trigger, directory, complete = false)
        assertEquals("please @src/ later", next)
        assertEquals("src/", mentionTrigger(next, 12)?.query)
        assertTrue(mentionTrigger("@src/", 5) != null)
        assertTrue(File("src/main/java/dev/dsh/mobile/mesh/ui/screens/main/Composer.kt").readText()
            .contains("val commandPickerOpen = activeMention == null && slashPickerState.visible(activeSlash)"))
        assertEquals("@src/main/", fileMention(WorkspaceDirectoryEntry("src/main", "directory"), false))
        assertEquals("@src/main.kt", fileMention(WorkspaceDirectoryEntry("src/main.kt", "file"), false))
    }

    @Test fun `quoted directories retain open quote and files finish it`() {
        val trigger = mentionTrigger("look @\"my dir/fi later", 16)!!
        assertEquals("my dir/fi", trigger.query)
        assertEquals("look @\"my dir/ later", replaceMentionTrigger("look @\"my dir/fi later", trigger, "@\"my dir/", complete = false))
        assertEquals("@\"my dir/", fileMention(WorkspaceDirectoryEntry("my dir", "directory"), trigger.quoted))
        assertEquals("@\"my dir/file.kt\"", fileMention(WorkspaceDirectoryEntry("my dir/file.kt", "file"), trigger.quoted))
        assertNull(fileMention(WorkspaceDirectoryEntry("bad\"path", "file"), false))
    }

    @Test fun `completed references replace only token and avoid duplicate space`() {
        val draft = "prefix @fi suffix"
        val trigger = mentionTrigger(draft, 10)!!
        assertEquals("prefix @file.kt suffix", replaceMentionTrigger(draft, trigger, "@file.kt", complete = true))
        assertEquals("prefix @file.kt ", replaceMentionTrigger("prefix @fi", mentionTrigger("prefix @fi", 10)!!, "@file.kt", complete = true))
        assertEquals("prefix @[Other](dsh-session:id) suffix", replaceMentionTrigger(draft, trigger, "@[Other](dsh-session:id)", complete = true))
    }

    @Test fun `dismissal leaves text unchanged and new at reopens picker`() {
        val draft = "before @a after"
        val trigger = mentionTrigger(draft, 9)!!
        val dismissed = MentionPickerState().dismiss(trigger)
        assertFalse(dismissed.visible(trigger))
        assertFalse(dismissed.onEdit(draft, "before @ab after").visible(mentionTrigger("before @ab after", 10)))
        val next = "before @ab @"
        assertTrue(dismissed.onEdit("before @ab ", next).visible(mentionTrigger(next, next.length)))
        assertEquals(7, dismissed.dismissedStart)
    }
}
