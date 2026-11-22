package dev.dsh.mobile.mesh.ui.screens.main

import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for passing a stable attachment snapshot through composer submission. */
class ComposerAttachmentSubmissionTest {
    @Test
    fun `prompt path submits and restores the attachment snapshot`() {
        val screen = java.io.File("src/main/java/dev/dsh/mobile/mesh/ui/screens/main/ChatScreen.kt").readText()
        val promptPath = screen.substringAfter("is Submission.Prompt -> {").substringBefore("\n            }\n        }")

        assertTrue(promptPath.contains("images.map { it.encoded() }"))
        assertTrue(promptPath.contains("removeSubmittedAttachments(pending)"))
        assertTrue(promptPath.contains("attachments.addAll(pending)"))
    }
}
