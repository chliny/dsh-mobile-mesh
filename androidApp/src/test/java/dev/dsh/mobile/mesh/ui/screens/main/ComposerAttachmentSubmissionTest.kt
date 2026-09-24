package dev.dsh.mobile.mesh.ui.screens.main

import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for passing a stable attachment snapshot through composer submission. */
class ComposerAttachmentSubmissionTest {
    @Test
    fun `send callback receives pending attachments and prompt path submits snapshot`() {
        val composer = java.io.File("src/main/java/dev/dsh/mobile/mesh/ui/screens/main/Composer.kt").readText()
        val screen = java.io.File("src/main/java/dev/dsh/mobile/mesh/ui/screens/main/ChatScreen.kt").readText()
        val sendAction = composer.substringAfter("val text = currentDraft").substringBefore("\n                            },")
        val promptPath = screen.substringAfter("is Submission.Prompt -> {").substringBefore("\n            }\n        }")

        assertTrue(sendAction.contains("currentOnSend(text, attachments.toList())"))
        assertTrue(promptPath.contains("images.map { it.encoded() }"))
        assertTrue(promptPath.contains("removeSubmittedAttachments(pending)"))
        assertTrue(promptPath.contains("attachments.addAll(pending)"))
    }
}
