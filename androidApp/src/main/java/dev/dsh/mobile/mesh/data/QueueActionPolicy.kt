package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.core.session.QueueItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Both authoritative inbox projections and legacy control snapshots store their blocks in the
 * `content` array of [QueueItem.content]. Editing currently sends a *single text block* via
 * `session/updateQueue`; images, file references and other structured blocks cannot round-trip.
 * Check block types, not preview placeholders: user text may literally contain "[image]".
 */
internal fun queueEditLosesNonText(item: QueueItem): Boolean =
    ((item.content as? JsonObject)?.get("content") as? JsonArray)
        ?.any { block ->
            val type = ((block as? JsonObject)?.get("type"))?.jsonPrimitive?.contentOrNull
            type != "text"
        } == true


internal fun canMutateQueueItem(item: QueueItem): Boolean =
    !item.id.startsWith("local:")

internal fun canSteerQueueItem(item: QueueItem, running: Boolean): Boolean =
    canMutateQueueItem(item) && running

/**
 * The text an edit dialog must open on.
 *
 * `session/updateQueue` replaces the item's whole content with whatever text the client sends, so
 * the editor is seeded with the item's complete message. `previewText` is the dock row's one-line
 * summary and stops at 200 characters; seeding from it made a longer queued turn open short in the
 * editor and then quietly drop everything past that mark the moment OK was pressed.
 */
internal fun queueEditSeedText(item: QueueItem): String =
    item.messageText.ifEmpty { item.previewText }
