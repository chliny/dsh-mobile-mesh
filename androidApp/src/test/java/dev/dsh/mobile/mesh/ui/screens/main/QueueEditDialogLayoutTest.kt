package dev.dsh.mobile.mesh.ui.screens.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Editing a long queued turn opened an unbounded field: it grew to the whole message, the plate ran
 * past the bottom of the screen, and the row that commits the edit went with it. The message was
 * neither fully readable nor saveable.
 */
class QueueEditDialogLayoutTest {
    private val docks = File("src/main/java/dev/dsh/mobile/mesh/ui/screens/main/Docks.kt").readText()

    // The queued-turn editor is the last dialog in the file, so it runs to the end of it.
    private val queueDialog = docks.substringAfter("DsDialog(title = stringResource(R.string.chat_queue_edit)")

    @Test
    fun `the queue editor is bounded and scrolls its own text`() {
        assertTrue(queueDialog.contains("heightIn(max = DsDialogBodyMaxHeight())"))
        assertTrue("the field must stop growing at a line cap", queueDialog.contains("maxLines ="))
        assertTrue("a long message must not shrink the field to one line", queueDialog.contains("minLines ="))
    }

    @Test
    fun `the queue editor's actions stay after the bounded field, inside the same plate`() {
        val field = queueDialog.indexOf("heightIn(max = DsDialogBodyMaxHeight())")
        val ok = queueDialog.indexOf("store.updateQueue(id, \"edit\", editText)")
        val dismiss = queueDialog.indexOf("editingId = null", ok)
        assertTrue("the field is missing from the edit plate", field >= 0)
        assertTrue("the OK row must follow the field it commits", field < ok)
        assertTrue("OK must still close the plate", ok < dismiss)
    }

    @Test
    fun `the queue editor never falls back to the truncated preview`() {
        val menu = docks
            .substringAfter("add(MenuItem(stringResource(R.string.chat_queue_edit)) {")
            .substringBefore("stringResource(R.string.chat_queue_remove)")
        assertTrue(menu.contains("queueEditSeedText(item)"))
        assertFalse(
            "seeding the editor from the 200-character preview truncates the saved turn",
            menu.contains("editText = item.previewText"),
        )
    }

    @Test
    fun `the goal editor carries the same bound rather than growing without one`() {
        val goalDialog = docks
            .substringAfter("DsDialog(title = stringResource(R.string.goal_edit)")
            .substringBefore("stringResource(R.string.chat_queue_count")
        assertTrue(goalDialog.contains("heightIn(max = DsDialogBodyMaxHeight())"))
        assertTrue(goalDialog.contains("maxLines ="))
    }

    @Test
    fun `the dock row still summarises on one line`() {
        // The preview belongs to the row, which is a summary; only the editor needs the whole text.
        val row = docks.substringAfter("item.previewText,").substringBefore("Spacer(Modifier.width(8.dp))")
        assertTrue(row.contains("maxLines = 1"))
        assertTrue(row.contains("TextOverflow.Ellipsis"))
    }
}