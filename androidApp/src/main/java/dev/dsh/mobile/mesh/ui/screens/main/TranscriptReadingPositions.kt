package dev.dsh.mobile.mesh.ui.screens.main

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.saveable.listSaver
import dev.dsh.mobile.mesh.core.session.AssistantMessageNode

/** Stable seq for durable rows; an open assistant attempt needs its turn/step across re-folds. */
internal data class TranscriptReadingPosition(
    val seq: Long,
    val offset: Int,
    val assistantTurn: Int? = null,
    val assistantStep: Int? = null,
    val atBottom: Boolean = false,
)

internal fun readingPositionOf(
    row: TranscriptRow,
    offset: Int,
    atBottom: Boolean = false,
): TranscriptReadingPosition {
    val assistant = (row as? TranscriptRow.Node)?.node as? AssistantMessageNode
    return TranscriptReadingPosition(
        // The disclosure key uses turn/start, which can disappear when a fresh history window
        // starts mid-turn. Its first rendered process node survives both folded and plain rows.
        (row as? TranscriptRow.Process)?.part?.firstNodeSeq ?: row.anchorSeq,
        offset,
        assistantTurn = assistant?.takeIf { it.streaming }?.turn,
        assistantStep = assistant?.takeIf { it.streaming }?.step,
        atBottom = atBottom,
    )
}

/** Keeps active Compose list states separate per session during in-process session switches. */
internal class TranscriptListStates {
    private val states = LinkedHashMap<String, LazyListState>(16, 0.75f, true)
    private val emptySessionState = LazyListState()

    fun forSession(sessionId: String?): LazyListState {
        if (sessionId == null) return emptySessionState
        states[sessionId]?.let { return it }
        val state = LazyListState()
        states[sessionId] = state
        if (states.size > MAX_RETAINED_SESSIONS) states.remove(states.keys.first())
        return state
    }

    private companion object {
        const val MAX_RETAINED_SESSIONS = 24
    }
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
        listOf("v3") + positions.entries().flatMap { (sessionId, position) ->
            listOf(sessionId, position.seq, position.offset,
                position.assistantTurn ?: Int.MIN_VALUE, position.assistantStep ?: Int.MIN_VALUE, position.atBottom)
        }
    },
    restore = { saved ->
        TranscriptReadingPositions().apply {
            when (saved.firstOrNull()) {
                "v3" -> saved.drop(1).chunked(6).filter { it.size == 6 }
                    .forEach { values ->
                        val (sessionId, seq, offset, turn, step) = values
                        val atBottom = values[5]
                        put(sessionId as String, TranscriptReadingPosition(
                            seq as Long, offset as Int,
                            (turn as Int).takeUnless { it == Int.MIN_VALUE },
                            (step as Int).takeUnless { it == Int.MIN_VALUE },
                            atBottom as Boolean,
                        ))
                    }
                "v2" -> saved.drop(1).chunked(5).filter { it.size == 5 }
                    .forEach { (sessionId, seq, offset, turn, step) ->
                        put(sessionId as String, TranscriptReadingPosition(
                            seq as Long, offset as Int,
                            (turn as Int).takeUnless { it == Int.MIN_VALUE },
                            (step as Int).takeUnless { it == Int.MIN_VALUE },
                        ))
                    }
                else -> {
                    // Existing saved-state bundles contain triples from the first release.
                    saved.chunked(3).filter { it.size == 3 }.forEach { (sessionId, seq, offset) ->
                        put(sessionId as String, TranscriptReadingPosition(seq as Long, offset as Int))
                    }
                }
            }
        }
    },
)

/** Whether a returning session should restore its semantic tail rather than its first visible row. */
internal fun shouldRestoreTranscriptToBottom(position: TranscriptReadingPosition?): Boolean = position?.atBottom == true

/** A paging sentinel or waiting indicator is not a readable anchor for session restoration. */
internal fun canRestoreReadingPosition(rows: List<TranscriptRow>): Boolean = rows.isNotEmpty()

/** One bounded server page on reentry; never start an unbounded history-fill loop. */
internal fun shouldFetchReadingAnchorPage(
    rows: List<TranscriptRow>,
    saved: TranscriptReadingPosition?,
    hasMore: Boolean,
    loadingOlder: Boolean,
    attempted: Boolean,
): Boolean = saved != null && !saved.atBottom && hasMore && !loadingOlder && !attempted && readingPositionIndex(rows, saved) < 0

/** A replaced window must not erase a historical anchor until the reader actually moves. */
internal fun shouldRecordReadingPosition(
    rows: List<TranscriptRow>,
    saved: TranscriptReadingPosition?,
    userScrolling: Boolean,
    currentAtBottom: Boolean,
): Boolean {
    // Streaming can extend the last row before auto-follow measures its new bottom. That
    // temporary gap is not a reader moving away; keep the semantic tail until a real drag.
    if (saved?.atBottom == true && !userScrolling && !currentAtBottom) return false
    return userScrolling || saved == null || currentAtBottom || readingPositionIndex(rows, saved) >= 0
}

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
            is TranscriptRow.Process -> row.part.containsSeq(position.seq)
            is TranscriptRow.Command -> row.activity.run?.seq == position.seq || row.activity.done?.seq == position.seq
            is TranscriptRow.Workflow -> row.activity.events.any { it.seq == position.seq }
            is TranscriptRow.Node -> false
        }
    }
}
