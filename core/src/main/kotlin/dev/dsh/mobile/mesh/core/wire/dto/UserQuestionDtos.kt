package dev.dsh.mobile.mesh.core.wire.dto

import dev.dsh.mobile.mesh.core.wire.decodeFromJsonElement
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Host-computed remaining duration from one `userQuestions/attachWait` stream item. */
@Serializable
data class UserQuestionWaitRemaining(
    @SerialName("remainingMs") val remainingMs: Long,
)

/** One timed call still answerable according to the host's `userQuestions` projection. */
@Serializable
data class PendingUserQuestion(
    @SerialName("callId") val callId: String,
    @SerialName("questions") val questions: List<AskUserQuestionItem>,
    /** `open` while the tool call can return an answer; `continued` after its foreground window. */
    @SerialName("state") val state: String,
)

/** One timed call settled with its recorded answer batch, in settlement order. */
@Serializable
data class SettledUserQuestion(
    @SerialName("callId") val callId: String,
    @SerialName("answers") val answers: List<AskUserQuestionAnswerItem>,
)

/** Read-only host projection; legacy blocking calls appear in neither list. */
@Serializable
data class UserQuestionsProjectionView(
    @SerialName("active") val active: List<PendingUserQuestion> = emptyList(),
    @SerialName("settled") val settled: List<SettledUserQuestion> = emptyList(),
)

/** Decode this registered projection from a baseline; absence means the host did not publish it. */
fun SessionProjectionsBlock.userQuestionsView(): UserQuestionsProjectionView? =
    values["userQuestions"]?.let { decodeFromJsonElement(UserQuestionsProjectionView.serializer(), it) }

/** Decode the same registered projection from the non-activating `session/projections` read. */
fun SessionProjectionsValue.userQuestionsView(): UserQuestionsProjectionView? =
    values["userQuestions"]?.let { decodeFromJsonElement(UserQuestionsProjectionView.serializer(), it) }
