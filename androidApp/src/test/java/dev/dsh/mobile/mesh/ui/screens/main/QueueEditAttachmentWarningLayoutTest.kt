package dev.dsh.mobile.mesh.ui.screens.main

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class QueueEditAttachmentWarningLayoutTest {
    @Test
    fun `non-text queue item needs explicit confirmation before editor opens`() {
        val source = File("src/main/java/dev/dsh/mobile/mesh/ui/screens/main/Docks.kt").readText()
        val menu = source.substringAfter("add(MenuItem(stringResource(R.string.chat_queue_edit)) {")
            .substringBefore("stringResource(R.string.chat_queue_remove)")
        assertTrue(menu.contains("if (queueEditLosesNonText(item))"))
        assertTrue(menu.indexOf("editWarningItem = item") < menu.indexOf("editingId = item.id"))
        val warning = source.substringAfter("editWarningItem?.let { item ->")
            .substringBefore("editingId?.let { id ->")
        assertTrue(warning.contains("R.string.chat_queue_edit_warning_body"))
        assertTrue(warning.contains("R.string.common_cancel"))
        assertTrue(warning.contains("R.string.chat_queue_edit_continue"))
        assertTrue(warning.indexOf("R.string.chat_queue_edit_continue") < warning.indexOf("editingId = item.id"))
    }
}
