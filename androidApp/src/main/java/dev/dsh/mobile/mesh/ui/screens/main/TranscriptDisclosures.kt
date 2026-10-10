package dev.dsh.mobile.mesh.ui.screens.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap

/**
 * Which conversation disclosures the reader has opened, owned above the transcript that draws them.
 *
 * Opening a full diff, a file preview, the terminal or the workspace browser takes MainScreen down a
 * branch that returns before ChatScreen is composed, so every `remember`-scoped expanded flag dies
 * with the row that held it. Coming back from a diff found the changed-files row collapsed again —
 * and so did every other row the reader had already opened, which is state they had deliberately
 * set.
 *
 * These are reader intent rather than render state, so the holder lives in AppRoot beside the
 * reading positions and the retained list states: it has to outlive the transcript the same way
 * those two already do. Rows are keyed per session, so one transcript's expansions never leak into
 * another.
 */
internal const val TRANSCRIPT_DISCLOSURE_MAX_RETAINED_SESSIONS = 24

internal class TranscriptDisclosures {
    private val sessions = mutableStateMapOf<String, SnapshotStateMap<String, Boolean>>()
    private val touched = LinkedHashSet<String>()

    /** Reading never creates a session's map: a row that has never been opened stays free. */
    fun isOpen(sessionId: String?, key: String): Boolean = sessions[sessionId.orEmpty()]?.get(key) == true

    /**
     * Whether the reader has ever set this row.
     *
     * A card that starts open and can be closed needs the difference between "still at its default"
     * and "the reader closed it"; storing only `true` entries cannot express that.
     */
    fun isSet(sessionId: String?, key: String): Boolean = sessions[sessionId.orEmpty()]?.containsKey(key) == true

    fun setOpen(sessionId: String?, key: String, value: Boolean) {
        sessions.getOrPut(sessionId.orEmpty()) { mutableStateMapOf() }[key] = value
        retain(sessionId.orEmpty())
    }

    /** Flips one row and answers its new state, which is what a disclosure toggle is handed. */
    fun toggle(sessionId: String?, key: String): Boolean {
        val next = !isOpen(sessionId, key)
        setOpen(sessionId, key, next)
        return next
    }

    internal fun sessionCount(): Int = sessions.size

    /**
     * Bounded like [TranscriptListStates]. A long session with every row expanded, times every
     * session the reader has ever opened, is a map no interface needs to keep alive forever, and a
     * session is dropped whole so its rows never come back half-remembered.
     */
    private fun retain(sessionId: String) {
        touched.remove(sessionId)
        touched.add(sessionId)
        while (touched.size > TRANSCRIPT_DISCLOSURE_MAX_RETAINED_SESSIONS) {
            val evicted = touched.first()
            touched.remove(evicted)
            sessions.remove(evicted)
        }
    }
}

/**
 * One surface's disclosure rows: the holder, plus which session's rows these are.
 *
 * Rows ask this for "my" flag instead of threading a session id and a holder through every
 * signature, which is the same reason [ChatNodeContext] carries one.
 *
 * A data class on purpose: ChatScreen builds one per composition, and structural equality is what
 * keeps [rememberDisclosure] from rebuilding every binding on every recomposition.
 */
internal data class DisclosureScope(
    private val disclosures: TranscriptDisclosures,
    val sessionId: String?,
) {
    fun isOpen(key: String): Boolean = disclosures.isOpen(sessionId, key)
    fun isSet(key: String): Boolean = disclosures.isSet(sessionId, key)
    fun setOpen(key: String, open: Boolean) = disclosures.setOpen(sessionId, key, open)
    fun toggle(key: String): Boolean = disclosures.toggle(sessionId, key)
    fun binding(key: String): DisclosureBinding = DisclosureBinding(this, key)
}

/**
 * A single row's open/closed flag and the toggle that flips it.
 *
 * [expanded] reads the holder's snapshot state rather than a local one, so a row recomposes no
 * matter where the flip happened — including on the composition that is being built again after the
 * reader came back from a full diff.
 */
internal class DisclosureBinding internal constructor(
    private val scope: DisclosureScope,
    private val key: String,
) {
    val expanded: Boolean get() = scope.isOpen(key)

    fun toggle() {
        scope.toggle(key)
    }

    val onToggle: () -> Unit = ::toggle
}

/** [DisclosureScope.binding] for a surface that has no [ChatNodeContext] to ask. */
@Composable
internal fun rememberDisclosure(scope: DisclosureScope, key: String): DisclosureBinding =
    remember(scope, key) { scope.binding(key) }

/**
 * Stable identities for every disclosure the conversation surface draws.
 *
 * A row's key must survive a rebuild of the transcript, so none of these is derived from a value
 * recomputed on every composition: they are the node's own seq or call id, plus which part of that
 * node is showing. Two rows on one node — a tool call's card and its todo's unchanged list — are
 * distinct keys rather than one shared flag.
 */
internal object DisclosureKeys {
    fun changes(seq: Long) = "changes:$seq"
    fun toolCall(callId: String) = "tool-call:$callId"
    fun todo(callId: String) = "todo:$callId"
    fun todoUnchanged(callId: String) = "todo-unchanged:$callId"
    fun reasoning(seq: Long, blockIndex: Int) = "reasoning:$seq:$blockIndex"
    fun reasoning(rowKey: String, blockIndex: Int) = "reasoning:$rowKey:$blockIndex"
    fun presentedFiles(seq: Long) = "presented-files:$seq"
    /** The to-do list as an event inside the transcript, as opposed to the dock above the composer. */
    fun transcriptTodo(seq: Long) = "transcript-todo:$seq"
    fun retry(seq: Long) = "retry:$seq"
    fun contextInjection(seq: Long) = "context-injection:$seq"
    fun compaction(seq: Long) = "compaction:$seq"
    fun command(seq: Long) = "command:$seq"
    fun workflow(seq: Long) = "workflow:$seq"
    fun commandActivity(seq: Long) = "command-activity:$seq"
    fun commandActivity(commandId: String?, fallbackSeq: Long) =
        commandId?.let { "command-activity:id:$it" } ?: commandActivity(fallbackSeq)
    fun workflowActivity(anchorSeq: Long) = "workflow-activity:$anchorSeq"
    fun workflowActivity(runId: String?, fallbackSeq: Long) =
        runId?.let { "workflow-activity:run:$it" } ?: workflowActivity(fallbackSeq)
    fun process(startSeq: Long) = "process:$startSeq"
    fun trajectoryCall(callId: String) = "trajectory-call:$callId"
    fun dock(name: String) = "dock:$name"
    fun detailsCard(id: String) = "details-card:$id"
    fun detailsJob(jobId: String) = "details-job:$jobId"
}