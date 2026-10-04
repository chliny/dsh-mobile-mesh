package dev.dsh.mobile.mesh.data

import org.junit.Assert.assertTrue
import org.junit.Test

/** A settled question must not keep the session drawer's pending badge. */
class QuestionDismissalPendingStateTest {
    @Test
    fun `accepted question dismissal clears matching pending request`() {
        val source = java.io.File("src/main/java/dev/dsh/mobile/mesh/data/SessionStore.kt").readText()
        val dismissal = source.substringAfter("suspend fun dismissQuestions(")
            .substringBefore("private fun pendingQuestionEvent(")

        assertTrue(dismissal.contains("if (outcome is QuestionOutcome.Accepted) clearQuestions(sessionId, eventId)"))
        assertTrue(dismissal.contains("return outcome"))
    }
}
