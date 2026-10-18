package dev.dsh.mobile.mesh.core.wire.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Host-owned reminder catalog from `schedule/catalog`; all six rule kinds share these fields. */
@Serializable
data class ScheduleCatalogEntry(
    val id: String,
    val kind: String,
    val title: String,
    val prompt: String,
    val scheduledAt: String,
    val sessionId: String,
    val status: String,
    val afterSeconds: Long? = null,
    val everySeconds: Long? = null,
    val time: String? = null,
    val timeZone: String? = null,
    val weekdays: List<Int>? = null,
    val expression: String? = null,
    val lastDelivery: ScheduleDeliveryReceipt? = null,
)

@Serializable
data class ScheduleDeliveryReceipt(
    val scheduledAt: String,
    val deliveredAt: String,
    val messageId: String,
    val prompt: String? = null,
)

@Serializable
data class ScheduleRetention(val days: Int, val records: Int)

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

/** Compare-and-update response: only `record` confirms a saved rule. */
@Serializable
data class ScheduleUpdateResult(
    val id: String? = null,
    val updated: Boolean? = null,
    val record: JsonObject? = null,
    val code: String? = null,
    val message: String? = null,
)

@Serializable
data class ScheduleDeleteResult(val id: String, val deleted: Boolean, val code: String? = null)

/** The complete observed record is sent back as `expected` for optimistic concurrency. */
@Serializable
data class ScheduleUpdateRequest(
    val sessionId: String,
    val id: String,
    val expected: JsonObject,
    val title: String? = null,
    val prompt: String? = null,
    val change: JsonObject? = null,
)

@Serializable
data class ScheduleHistoryRequest(
    val sessionId: String,
    val id: String,
    val limit: Int = 20,
    val before: String? = null,
)

@Serializable
data class ScheduleDeleteRequest(val sessionId: String, val id: String)
