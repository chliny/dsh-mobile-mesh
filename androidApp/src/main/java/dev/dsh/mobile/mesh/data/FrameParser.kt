package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.core.session.QueueItem
import dev.dsh.mobile.mesh.core.session.SessionEventEnvelope
import dev.dsh.mobile.mesh.core.wire.decodeFromJsonElement
import dev.dsh.mobile.mesh.core.wire.encodeToJsonElement
import dev.dsh.mobile.mesh.core.wire.dto.ContentBlock
import dev.dsh.mobile.mesh.core.wire.dto.InboxMessageView
import dev.dsh.mobile.mesh.core.wire.dto.QueuedInboxItem
import dev.dsh.mobile.mesh.core.wire.dto.QueuedMessage
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

/** Convert one durable inbox-projection message into the renderer-facing [QueueItem]. */
fun inboxMessageToQueueItem(item: InboxMessageView, placement: String): QueueItem {
    val messageText = item.content
        .joinToString("\n") { queueBlockPreview(it) }
        .trim()
    val preview = messageText.take(200)
    val content = encodeToJsonElement(InboxMessageView.serializer(), item)
    return QueueItem(
        id = item.id,
        placement = placement,
        previewText = preview,
        messageText = messageText,
        content = content,
        rpcId = item.source?.takeIf { it.kind == "user" }?.rpcId,
    )
}

/** Convert one legacy control-queue item into the renderer-facing [QueueItem]. */
fun queuedInboxItemToQueueItem(item: QueuedInboxItem): QueueItem {
    val messageText = item.message.content
        .joinToString("\n") { queueBlockPreview(it) }
        .trim()
    return QueueItem(
        id = item.id,
        placement = item.placement,
        previewText = messageText.take(200),
        messageText = messageText,
        content = encodeToJsonElement(QueuedMessage.serializer(), item.message),
        rpcId = item.rpcId,
    )
}

private fun queueBlockPreview(block: ContentBlock): String = when (block) {
    is ContentBlock.Text -> block.text
    is ContentBlock.Reasoning -> block.text
    is ContentBlock.Image -> "[image]"
    is ContentBlock.File -> "[file]"
    is ContentBlock.ToolCall -> "[tool call]"
    is ContentBlock.ToolResult -> "[tool result]"
    is dev.dsh.mobile.mesh.core.wire.dto.UnknownContentBlock -> "[${block.type}]"
}
