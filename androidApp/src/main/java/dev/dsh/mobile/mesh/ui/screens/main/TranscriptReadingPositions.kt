package dev.dsh.mobile.mesh.ui.screens.main

import androidx.compose.runtime.saveable.listSaver
import dev.dsh.mobile.mesh.core.session.AssistantMessageNode

/** Stable seq for durable rows; an open assistant attempt needs its turn/step across re-folds. */
internal data class TranscriptReadingPosition(
    val seq: Long,
    val offset: Int,
    val assistantTurn: Int? = null,
    val assistantStep: Int? = null,
)

internal fun readingPositionOf(row: TranscriptRow, offset: Int): TranscriptReadingPosition {
    val assistant = (row as? TranscriptRow.Node)?.node as? AssistantMessageNode
    return TranscriptReadingPosition(
        row.anchorSeq,
        offset,
        assistantTurn = assistant?.takeIf { it.streaming }?.turn,
        assistantStep = assistant?.takeIf { it.streaming }?.step,
    )
}

internal class TranscriptReadingPositions {
    private val positions = mutableMapOf<String, TranscriptReadingPosition>()

    fun get(sessionId: String): TranscriptReadingPosition? = positions[sessionId]

    fun put(sessionId: String, position: TranscriptReadingPosition) {
        positions[sessionId] = position
    }

    internal fun entries(): Map<String, TranscriptReadingPosition> = positions
}

internal val transcriptReadingPositionsSaver = listSaver<TranscriptReadingPositions, Any>(
    save = { positions ->
        listOf("v2") + positions.entries().flatMap { (sessionId, position) ->
            listOf(sessionId, position.seq, position.offset,
                position.assistantTurn ?: Int.MIN_VALUE, position.assistantStep ?: Int.MIN_VALUE)
        }
    },
    restore = { saved ->
        TranscriptReadingPositions().apply {
            if (saved.firstOrNull() == "v2") {
                saved.drop(1).chunked(5).filter { it.size == 5 }.forEach { (sessionId, seq, offset, turn, step) ->
                    put(sessionId as String, TranscriptReadingPosition(
                        seq as Long, offset as Int,
                        (turn as Int).takeUnless { it == Int.MIN_VALUE },
                        (step as Int).takeUnless { it == Int.MIN_VALUE },
                    ))
                }
            } else {
                // Existing saved-state bundles contain triples from the previous release.
                saved.chunked(3).filter { it.size == 3 }.forEach { (sessionId, seq, offset) ->
                    put(sessionId as String, TranscriptReadingPosition(seq as Long, offset as Int))
                }
            }
        }
    },
)

/** A replaced window must not erase a historical anchor until the reader actually moves. */
internal fun shouldRecordReadingPosition(
    rows: List<TranscriptRow>,
    saved: TranscriptReadingPosition?,
    userScrolling: Boolean,
): Boolean = userScrolling || saved == null || readingPositionIndex(rows, saved) >= 0

/** Return the visible row containing the saved message, not an unrelated turn start. */
internal fun readingPositionIndex(rows: List<TranscriptRow>, position: TranscriptReadingPosition): Int {
    if (position.assistantTurn != null && position.assistantStep != null) {
        return rows.indexOfLast { row ->
            val assistant = (row as? TranscriptRow.Node)?.node as? AssistantMessageNode
            assistant != null && assistant.turn == position.assistantTurn && assistant.step == position.assistantStep
        }
    }
    return rows.indexOfFirst { row ->
        row.anchorSeq == position.seq || when (row) {
            is TranscriptRow.Process -> row.part.nodes.any { it.seq == position.seq }
            is TranscriptRow.Command -> row.activity.run?.seq == position.seq || row.activity.done?.seq == position.seq
            is TranscriptRow.Workflow -> row.activity.events.any { it.seq == position.seq }
            is TranscriptRow.Node -> false
        }
    }
}
