package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.core.wire.dto.PendingUserQuestion
import dev.dsh.mobile.mesh.core.wire.dto.UserQuestionsProjectionView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserQuestionSelectionTest {
    @Test fun `only explicit timed call claims remote wait`() {
        assertFalse(shouldAttachQuestionWait(null, "call"))
        assertFalse(shouldAttachQuestionWait(false, "call"))
        assertFalse(shouldAttachQuestionWait(true, ""))
        assertTrue(shouldAttachQuestionWait(true, "call"))
    }

    @Test fun `continued answer requires host projection`() {
        val pending = PendingQuestions("session", "event", emptyList(), "call", foreground = false)
        assertEquals(QuestionRoute.None, questionRoute(pending, null))
        assertEquals(QuestionRoute.None, questionRoute(pending, UserQuestionsProjectionView(active = listOf(
            PendingUserQuestion("call", emptyList(), "open"),
        ))))
        assertEquals(QuestionRoute.Continued, questionRoute(pending, UserQuestionsProjectionView(active = listOf(
            PendingUserQuestion("call", emptyList(), "continued"),
        ))))
        assertEquals(QuestionRoute.Legacy, questionRoute(pending.copy(callId = null), null))
        assertEquals(QuestionRoute.Foreground, questionRoute(pending.copy(foreground = true), null))
    }

    @Test fun `simultaneous session prompts remain visible when switching sessions`() {
        val first = PendingQuestions("first", "event-first", emptyList())
        val second = PendingQuestions("second", "event-second", emptyList())
        val claims = mapOf(first.sessionId to first, second.sessionId to second)
        assertEquals(first, visibleQuestion(claims, null, "first"))
        assertEquals(second, visibleQuestion(claims, null, "second"))
        assertEquals(first, visibleQuestion(claims - "second", null, "first"))
        assertEquals(null, visibleQuestion(claims, null, "missing"))
    }

    @Test fun `foreground timed claim survives another session prompt`() {
        val first = PendingQuestions("first", "event-first", emptyList(), "call-first", foreground = true)
        val second = PendingQuestions("second", "event-second", emptyList(), "call-second", foreground = true)
        val claims = mapOf(first.sessionId to first, second.sessionId to second)
        assertEquals(first, visibleQuestion(claims, null, "first"))
        assertEquals(second, visibleQuestion(claims, null, "second"))
    }

    @Test fun `projection cannot duplicate foreground call`() {
        val view = UserQuestionsProjectionView(active = listOf(
            PendingUserQuestion("foreground", emptyList(), "continued"),
            PendingUserQuestion("later", emptyList(), "continued"),
        ))
        assertEquals("later", continuedQuestion(view, "foreground")?.callId)
        assertEquals(null, continuedQuestion(null, null))
    }
}
