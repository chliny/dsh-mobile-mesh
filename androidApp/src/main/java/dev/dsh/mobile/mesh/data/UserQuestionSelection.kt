package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.core.wire.dto.PendingUserQuestion
import dev.dsh.mobile.mesh.core.wire.dto.UserQuestionsProjectionView

/** Only the host's continued state authorizes the separate answer endpoint. */
internal fun continuedQuestion(view: UserQuestionsProjectionView?, foregroundCallId: String?): PendingUserQuestion? =
    view?.active?.firstOrNull { it.state == "continued" && it.callId != foregroundCallId }

/** A timed request needs its own claim; legacy waterfalls remain on the old event route. */
internal fun shouldAttachQuestionWait(timed: Boolean?, callId: String?): Boolean =
    timed == true && !callId.isNullOrBlank()

internal fun questionRoute(question: PendingQuestions?, view: UserQuestionsProjectionView?): QuestionRoute = when {
    question == null -> QuestionRoute.None
    question.callId == null -> QuestionRoute.Legacy
    question.foreground -> QuestionRoute.Foreground
    view?.active?.any { it.callId == question.callId && it.state == "continued" } == true -> QuestionRoute.Continued
    else -> QuestionRoute.None
}

internal enum class QuestionRoute { None, Legacy, Foreground, Continued }
