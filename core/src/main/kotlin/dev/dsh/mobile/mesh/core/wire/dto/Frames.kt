package dev.dsh.mobile.mesh.core.wire.dto

import dev.dsh.mobile.mesh.core.wire.RpcError
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Shapes carried by the host's live state, ported from `packages/api/session-controller`
 * and `packages/interaction/user-questions` (v0.1.2-alpha.1).
 *
 * This file used to hold the two downlink frame unions as well. Harness 0.1.2 deleted both
 * sockets they belonged to: session queue, jobs and projections now arrive as
 * [SessionControlFrame] items on `session/control`, the registry frames as
 * [WorkspaceFollowFrame] items on `workspace/follow`, and approvals and questions as
 * agent-scoped waterfalls on `$events`. What remains here is the payload vocabulary those
 * frames carry, which did not change.
 */

/** One pending inbox occurrence in the authoritative `session/queue` snapshot. */
@Serializable
data class QueuedInboxItem(
    /** Message identity used by inbox mutations. */
    @SerialName("id") val id: String,
    /** Agent-resolved FIFO placement ('queued' | 'steering' | 'context'). */
    @SerialName("placement") val placement: String,
    /** Prompt-RPC identity copied from the queued message's user source. */
    @SerialName("rpcId") val rpcId: String? = null,
    /** Complete pending message; the wire carries only identity and content. */
    @SerialName("message") val message: QueuedMessage,
)

/** Queue message payload from `session/control`; role and source are not transmitted. */
@Serializable
data class QueuedMessage(
    @SerialName("id") val id: String,
    @SerialName("content") val content: List<ContentBlock> = emptyList(),
)

/** One selectable answer offered to the user. */
@Serializable
data class AskUserQuestionOption(
    @SerialName("label") val label: String,
    /** Optional extra context rendered by capable UIs. */
    @SerialName("description") val description: String? = null,
)

/** A caller-declared presentation intent; tagged so further intents can be added. */
@Serializable
@kotlinx.serialization.json.JsonClassDiscriminator("kind")
sealed class AskUserQuestionIntent {
    /** A plan submitted for review: `detail` is the plan markdown, the decision approves or declines it. */
    @Serializable
    @SerialName("plan-review")
    data class PlanReview(
        /** The option label that approves the plan; every other option declines it. */
        @SerialName("approve") val approve: String,
        /** Logged tool invocation whose arguments contain the reviewed plan. */
        @SerialName("callId") val callId: String? = null,
    ) : AskUserQuestionIntent()
}

/** One question in a user-questions request. */
@Serializable
data class AskUserQuestionItem(
    /** Stable caller-provided question id, echoed in the answer. */
    @SerialName("id") val id: String,
    /** The question to display. */
    @SerialName("question") val question: String,
    /** Optional supporting detail rendered with the question. */
    @SerialName("detail") val detail: String? = null,
    /** Optional short heading/group label. */
    @SerialName("header") val header: String? = null,
    /** Optional choices the UI can render as a menu. */
    @SerialName("options") val options: List<AskUserQuestionOption>? = null,
    /** Whether more than one option may be selected. Defaults to single-select. */
    @SerialName("multiSelect") val multiSelect: Boolean? = null,
    /** Optional presentation intent for capable UIs. */
    @SerialName("intent") val intent: AskUserQuestionIntent? = null,
)

/**
 * Answer to one question. `custom` rides the item, not the batch beside it. The codec suppresses
 * explicit nulls, so an absent `custom` is omitted rather than sent as `null`.
 */
@Serializable
data class AskUserQuestionAnswerItem(
    /** The answered question's id, echoed back. */
    @SerialName("id") val id: String,
    /** Selected option labels, verbatim — including any `(Recommended)` suffix. */
    @SerialName("selected") val selected: List<String> = emptyList(),
    /** Free-text "Other" answer; absent when the user typed none. */
    @SerialName("custom") val custom: String? = null,
)

/**
 * The human's structured answer batch. `userQuestions/answer` requires each continued question
 * id exactly once; order does not matter. An empty selection is still an answer.
 */
@Serializable
data class AskUserQuestionAnswer(
    @SerialName("answers") val answers: List<AskUserQuestionAnswerItem> = emptyList(),
)

/**
 * The error a client sends to dismiss a question request rather than answer it.
 *
 * The proxy accepts an `ok:false` client-response for a question only when the code is exactly
 * `cancelled`; anything else comes back as `bad-response` and the wait stays open. `details` is
 * required by the schema, and [RpcError] defaults it to `{}` — which reaches the wire because the
 * codec encodes defaults.
 */
val QUESTION_CANCELLED: RpcError = RpcError("cancelled", "the user closed this question request")
