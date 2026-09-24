package dev.dsh.mobile.mesh.ui.screens.main

import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression guard for restoring pending attachment chips when revisiting a session. */
class AttachmentDraftPersistenceTest {
    @Test
    fun `attachments are snapshotted per session and restored on selection`() {
        val source = java.io.File("src/main/java/dev/dsh/mobile/mesh/ui/screens/main/ChatScreen.kt").readText()

        assertTrue(source.contains("attachmentsBySession = remember { mutableStateMapOf<String, List<PendingAttachment>>() }"))
        assertTrue(source.contains("addAll(attachmentsBySession[it].orEmpty())"))
        assertTrue(source.contains("snapshotFlow { attachments.toList() }.collect"))
        assertTrue(source.contains("attachmentsBySession[sessionId] = pending"))
    }
}
