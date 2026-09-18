package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.core.session.QueueItem
import dev.dsh.mobile.mesh.core.session.SessionEventEnvelope
import dev.dsh.mobile.mesh.core.wire.decodeFromJsonElement
import dev.dsh.mobile.mesh.core.wire.encodeToJsonElement
import dev.dsh.mobile.mesh.core.wire.dto.ContentBlock
import dev.dsh.mobile.mesh.core.wire.dto.MessageData
import dev.dsh.mobile.mesh.core.wire.dto.QueuedInboxItem
import dev.dsh.mobile.mesh.core.wire.dto.SessionWireEvent
import dev.dsh.mobile.mesh.core.wire.dto.SessionEvent
import dev.dsh.mobile.mesh.core.wire.dto.SessionEventSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Pure, unit-testable decode helpers shared by [SessionStore] and the notification observer.
 *
 * Every function is lenient: malformed payloads yield `null` (or an empty field) instead of
 * throwing, because stream frames are merge-extensible and may drift.
 */

/**
 * Convert one wire event from a journal record into the raw-envelope shape the fold consumes.
 *
 * The journal's event form is already flat — type, seq, time, raw `data` — so unlike
 * [sessionEventToEnvelope] this needs no round-trip through a typed DTO. `surfaceOp` is
 * stringified for the envelope exactly as the typed path does it.
 */
fun wireEventToEnvelope(event: SessionWireEvent): SessionEventEnvelope = SessionEventEnvelope(
    type = event.type,
    seq = event.seq.toLong(),
    time = event.time,
    data = event.data,
    surfaceOp = event.surfaceOp?.let { raw ->
        (raw as? JsonPrimitive)?.contentOrNull ?: raw.toString()
    },
)

/**
 * Convert a typed [SessionEvent] into the raw-envelope shape the fold consumes. `data` is the
 * raw JSON of the event's payload (re-derived from the serializer so it stays a [JsonElement]
 * exactly as the fold expects); `surfaceOp` is stringified for the envelope.
 */
fun sessionEventToEnvelope(event: SessionEvent): SessionEventEnvelope {
    val json = encodeToJsonElement(SessionEventSerializer, event).jsonObject
    val data = json["data"] ?: JsonObject(emptyMap())
    val surfaceOp = json["surfaceOp"]?.let { raw ->
        (raw as? JsonPrimitive)?.contentOrNull ?: raw.toString()
    }
    return SessionEventEnvelope(
        type = event.type,
        seq = event.seq.toLong(),
        time = event.time,
        data = data,
        surfaceOp = surfaceOp,
    )
}

/** Decode a raw `session/event` event object into a [SessionEventEnvelope], or null on drift. */
fun parseSessionEventEnvelope(eventJson: JsonElement): SessionEventEnvelope? =
    runCatching { sessionEventToEnvelope(decodeFromJsonElement(SessionEventSerializer, eventJson)) }
        .getOrNull()

/** Convert one authoritative queue snapshot item into the renderer-facing [QueueItem]. */
fun queuedInboxItemToQueueItem(item: QueuedInboxItem): QueueItem {
    val messageText = item.message.content
        .filterIsInstance<ContentBlock.Text>()
        .joinToString("\n") { it.text }
    val preview = messageText.take(120)
    val content = encodeToJsonElement(MessageData.serializer(), item.message)
    return QueueItem(
        id = item.id,
        placement = item.placement,
        previewText = preview,
        messageText = messageText,
        content = content,
        rpcId = item.rpcId ?: item.message.source.rpcId,
    )
}
