package dev.dsh.mobile.mesh.core.wire.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Host-owned automation-task wire types, ported from `packages/schedule/schedule/src/types.ts`
 * (protocol baseline: v0.2.2-rc.1).
 *
 * Harness 0.2.2 redesigned the Schedule surface. A catalog row no longer embeds the receiving
 * session id: it carries a delivery [ScheduleTarget], the sessions involved in the task, a
 * revision (`updatedAt`) used for optimistic concurrency, and widened status values. The
 * per-rule fields (kind/title/prompt/scheduledAt and the rule selector) are unchanged, so one
 * tolerant DTO decodes both generations; [sessionId] and [target] are each absent on one side.
 *
 * Mutation also moved: `schedule/update` and `schedule/delete` were replaced by
 * `schedule/edit`, whose request carries one closed update-or-delete operation and whose
 * failures use the standard error envelope. The legacy request/response values stay here for
 * hosts released before that change.
 */

/** The Host's delivery destination for one task. */
@Serializable
data class ScheduleTarget(
    /** 'session' (one existing Session), 'new_once' (one Session created at first delivery) or 'new' (a fresh Session per delivery). */
    @SerialName("kind") val kind: String,
    /** Target Session identity; present for 'session' and, after reservation, 'new_once'. */
    @SerialName("sessionId") val sessionId: String? = null,
    /** Values used when the target creates a Session. */
    @SerialName("create") val create: ScheduleSessionCreateSpec? = null,
)

/** Creation values for a target-created Session; omission uses Host defaults. */
@Serializable
data class ScheduleSessionCreateSpec(
    @SerialName("cwd") val cwd: String? = null,
    @SerialName("agentPreset") val agentPreset: String? = null,
)

/** One catalog row: the stored rule plus Host-owned delivery state. */
@Serializable
data class ScheduleCatalogEntry(
    val id: String,
    val kind: String,
    val title: String,
    val prompt: String,
    val scheduledAt: String,
    /** Receiving Session id on hosts before 0.2.2; those hosts always send it. */
    val sessionId: String? = null,
    /** 'active' | 'inactive' before 0.2.2; 'active' | 'paused' | 'ended' from 0.2.2 on. */
    val status: String,
    val afterSeconds: Long? = null,
    val everySeconds: Long? = null,
    val time: String? = null,
    val timeZone: String? = null,
    val weekdays: List<Int>? = null,
    val expression: String? = null,
    /** Normalized RFC 5545 timing for the 0.2.2 `rrule` kind. */
    val rrule: String? = null,
    /** Delivery destination on 0.2.2 and later hosts; absent before. */
    val target: ScheduleTarget? = null,
    /** Sessions told about task changes while their Agent is live (0.2.2). */
    val involvedSessionIds: List<String> = emptyList(),
    /** Canonical UTC creation time (0.2.2, optional). */
    val createdAt: String? = null,
    /** Revision to send back as `expectedUpdatedAt` for conflict detection (0.2.2, optional). */
    val updatedAt: String? = null,
    /** The latest delivery is queued or running in its reused target Session (0.2.2). */
    val executionActive: Boolean? = null,
    /** End of the minimum delivery interval while it remains in the future (0.2.2). */
    val deliveryFloorEndsAt: String? = null,
    val lastDelivery: ScheduleDeliveryReceipt? = null,
) {
    /** The Session this task delivers to, when the row names one on either generation. */
    val deliverySessionId: String?
        get() = sessionId ?: target?.sessionId

    /** Host-visible rule label: 'active' means enabled, every other value is a stopped state. */
    val enabled: Boolean
        get() = status == "active"
}

/** One durably acknowledged delivery. */
@Serializable
data class ScheduleDeliveryReceipt(
    val scheduledAt: String,
    val deliveredAt: String,
    val messageId: String,
    val prompt: String? = null,
    /** Session that received this delivery on 0.2.2 and later hosts. */
    val targetSessionId: String? = null,
)

@Serializable
data class ScheduleRetention(val days: Int, val records: Int)

/** Saved-delivery history; the request is accepted by every host generation. */
@Serializable
data class ScheduleHistoryResult(
    val id: String,
    val records: List<ScheduleDeliveryReceipt> = emptyList(),
    val earlierRecordsUnavailable: Boolean = false,
    val earlierRecordsPruned: Boolean = false,
    val retention: ScheduleRetention? = null,
    val nextBefore: String? = null,
    val code: String? = null,
)

/**
 * `schedule/edit` request (0.2.2+): one closed update-or-delete operation.
 *
 * [ScheduleEditPatch] omits every member it does not change, and [expectedUpdatedAt] is the
 * `updatedAt` value the editor read — the Host answers a mismatch with a `schedule_conflict`
 * result rather than writing.
 */
@Serializable
data class ScheduleEditRequest(
    val id: String,
    /** 'update' or 'delete'. */
    val action: String,
    val patch: ScheduleEditPatch? = null,
    val expectedUpdatedAt: String? = null,
)

/** Fields one edit changes together; omitted members keep their stored values. */
@Serializable
data class ScheduleEditPatch(
    val title: String? = null,
    val prompt: String? = null,
    /** Replacement timing in the Host's `ScheduleTiming` vocabulary. */
    val timing: JsonObject? = null,
    val target: ScheduleTarget? = null,
    val active: Boolean? = null,
)

@Serializable
data class ScheduleCreateRequest(
    val title: String,
    val prompt: String,
    val timing: JsonObject,
    val target: ScheduleTarget? = null,
)

@Serializable
data class ScheduleIdRequest(val id: String)

/** A row or a non-mutating miss/refusal from view or run. */
@Serializable
data class ScheduleActionResult(val id: String, val kind: String? = null, val code: String? = null)

@Serializable
data class ScheduleDeleteInactiveResult(val deleted: Int)

@Serializable
data class ScheduleDeliveryLimits(val newSessionIntervalSeconds: Long)

/**
 * `schedule/edit` result (0.2.2+): the committed row, a delete receipt, or a non-mutating
 * lookup/conflict miss. A committed update and the miss shapes all carry `id`; the row is
 * identified by its `kind`, and only a missing `code` confirms a write.
 */
@Serializable
data class ScheduleEditResult(
    val id: String? = null,
    val deleted: Boolean? = null,
    val code: String? = null,
    val message: String? = null,
    /** Present when the result is the committed catalog row. */
    val kind: String? = null,
) {
    /** Whether the Host committed the operation. */
    val accepted: Boolean
        get() = code == null && (deleted == true || kind != null)

    /** The non-mutating miss/conflict the Host reported, if any. */
    val missCode: String?
        get() = code
}

/** Legacy compare-and-update response (hosts before 0.2.2): only `record` confirms a saved rule. */
@Serializable
data class ScheduleUpdateResult(
    val id: String? = null,
    val updated: Boolean? = null,
    val record: JsonObject? = null,
    val code: String? = null,
    val message: String? = null,
)

@Serializable
data class ScheduleDeleteResult(
    val id: String,
    val deleted: Boolean = false,
    val code: String? = null,
)

/** Legacy compare-and-update request; the complete observed record is sent as `expected`. */
@Serializable
data class ScheduleUpdateRequest(
    val sessionId: String,
    val id: String,
    val expected: JsonObject,
    val title: String? = null,
    val prompt: String? = null,
    val change: JsonObject? = null,
)

/**
 * Saved-delivery history request.
 *
 * [sessionId] exists only on hosts before 0.2.2; those hosts require it, while later hosts
 * ignore it because a task is Host-wide. [ScheduleCatalogEntry.deliverySessionId] supplies the
 * value when the row names one, and an empty string is discarded by the newer Hosts.
 */
@Serializable
data class ScheduleHistoryRequest(
    val sessionId: String = "",
    val id: String,
    val limit: Int = 20,
    val before: String? = null,
)

/** Legacy delete request (hosts before 0.2.2). */
@Serializable
data class ScheduleDeleteRequest(val sessionId: String, val id: String)
