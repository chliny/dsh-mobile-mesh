package dev.dsh.mobile.mesh.core.wire.dto

import dev.dsh.mobile.mesh.core.wire.RpcError
import dev.dsh.mobile.mesh.core.wire.decodeFromJsonElement
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.JsonElement

/**
 * Shapes carried by the host's live state, ported from `packages/api/session-controller`
 * and `packages/interaction/user-questions` (protocol baseline: v0.2.2-rc.1).
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

/**
 * A caller-declared presentation intent; tagged so further intents can be added.
 *
 * An intent changes presentation only, never the answer encoding, so an intent this client does
 * not know must not take the whole question request down with it: [AskUserQuestionIntentOrNullSerializer]
 * decodes an unrecognized tag as no intent, and the UI falls back to the generic option flow.
 */
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

    /**
     * A blocking permission decision with fixed actions and no free-text answer (harness
     * 0.2.2). The options still arrive with the question, so the generic option flow can answer
     * it; the extra metadata is for a dedicated approval card this client does not render yet.
     */
    @Serializable
    @SerialName("approval")
    data class Approval(
        /** Option label granting the scope described by the caller. */
        @SerialName("approve") val approve: String,
        /** Option label refusing the requested access. */
        @SerialName("reject") val reject: String,
        /** Optional persistent grant label, offered only when the caller supports it. */
        @SerialName("always") val always: String? = null,
        /** Human-recognition metadata for the requesting subject; never an identity. */
        @SerialName("subject") val subject: ApprovalSubject? = null,
        /** Localized question and limited-grant labels, keyed by lowercase language id. */
        @SerialName("display") val display: ApprovalDisplay? = null,
    ) : AskUserQuestionIntent()

    /** Who is asking, for display only. */
    @Serializable
    data class ApprovalSubject(
        @SerialName("name") val name: String,
        @SerialName("icon") val icon: String? = null,
    )

    /** Localized approval copy; each member is keyed by language id and carries a required English entry. */
    @Serializable
    data class ApprovalDisplay(
        @SerialName("question") val question: Map<String, String> = emptyMap(),
        @SerialName("detail") val detail: Map<String, String>? = null,
        @SerialName("approve") val approve: Map<String, String>? = null,
    )
}

/**
 * Decode an intent without failing its question request when a future Host adds a tag this
 * client does not know; unknown tags become an absent intent and render the generic flow.
 */
object AskUserQuestionIntentOrNullSerializer : kotlinx.serialization.KSerializer<AskUserQuestionIntent?> {
    private val delegate = AskUserQuestionIntent.serializer().nullable

    override val descriptor: kotlinx.serialization.descriptors.SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: AskUserQuestionIntent?) {
        delegate.serialize(encoder, value)
    }

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): AskUserQuestionIntent? {
        val jsonDecoder = decoder as? kotlinx.serialization.json.JsonDecoder ?: return delegate.deserialize(decoder)
        return runCatching {
            decodeFromJsonElement(AskUserQuestionIntent.serializer(), jsonDecoder.decodeJsonElement())
        }.getOrNull()
    }
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
    /** Optional presentation intent for capable UIs; an unknown future tag decodes as no intent. */
    @Serializable(with = AskUserQuestionIntentOrNullSerializer::class)
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
