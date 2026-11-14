package dev.dsh.mobile.mesh.ui.screens.main

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.dsh.mobile.mesh.BuildConfig
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.core.session.AssistantMessageNode
import dev.dsh.mobile.mesh.core.session.ChatNode
import dev.dsh.mobile.mesh.core.session.ConversationSnapshot
import dev.dsh.mobile.mesh.data.TranscriptPresentation
import dev.dsh.mobile.mesh.ui.components.DsButton
import dev.dsh.mobile.mesh.ui.components.DsButtonSize
import dev.dsh.mobile.mesh.ui.components.DsButtonVariant
import dev.dsh.mobile.mesh.ui.components.EmptyHero
import dev.dsh.mobile.mesh.ui.components.skeleton
import dev.dsh.mobile.mesh.ui.theme.DsTheme
import dev.dsh.mobile.mesh.ui.theme.DsType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * How close to the top a reader must get before the next page is fetched.
 *
 * Deliberately not zero. Item keys are stable, so the list re-anchors the viewport on the key that
 * was first visible when older items are prepended — which only holds a reader's place if that
 * anchor is a message row that moves down with the rest. The paging row sits at index 0 and stays
 * there, so if it is the anchor the prepended page pushes everything being read below the fold.
 * Firing a couple of rows early keeps a `seq`-keyed message as the anchor.
 */
private const val LOAD_OLDER_THRESHOLD = 2

/** An explicit expansion is reading navigation, not new transcript content to follow to the tail. */
internal val LocalTranscriptContentExpanded = compositionLocalOf<(() -> Unit)?> { null }

/** Placement animation is disabled because disclosure expansion must not animate every sibling. */
internal fun transcriptItemsAnimatePlacement(): Boolean = false

/** The triggering drag may still be finishing; only a *new* drag abandons its pending anchor. */
internal fun shouldDiscardOlderPageAnchor(
    capturedDragGeneration: Int,
    currentDragGeneration: Int,
): Boolean = capturedDragGeneration != currentDragGeneration

/** Ignore incidental taps and tiny movements at the top of the transcript. */
private val TOP_PULL_DISTANCE = 32.dp

private class TranscriptProjectionKey(
    val sessionId: String?,
    val sourceNodes: List<ChatNode>,
) {
    override fun equals(other: Any?): Boolean =
        other is TranscriptProjectionKey && sessionId == other.sessionId && sourceNodes === other.sourceNodes

    override fun hashCode(): Int = 31 * (sessionId?.hashCode() ?: 0) + System.identityHashCode(sourceNodes)
}

private data class TranscriptProjection(
    val key: TranscriptProjectionKey,
    val nodes: List<ChatNode>,
    val parts: List<TranscriptPart>,
    val hasAssistant: Boolean,
)

internal fun transcriptNodeRowKey(node: ChatNode): String = when (node) {
    is AssistantMessageNode -> node.turn?.takeIf { it > 0 }?.let { turn ->
        node.step?.let { step -> "assistant-$turn-$step" }
    } ?: "node-${node.seq}"
    else -> "node-${node.seq}"
}

private fun transcriptNodeAnchorSeq(node: ChatNode): Long {
    if (node !is AssistantMessageNode || !node.streaming) return node.seq
    val turn = node.turn?.takeIf { it > 0 } ?: return node.seq
    val step = node.step ?: return node.seq
    return Long.MIN_VALUE or ((turn.toLong() and 0x7fffffffL) shl 32) or (step.toLong() and 0xffffffffL)
}

private data class OlderPageAnchor(
    val seq: Long,
    val offset: Int,
    val firstRowKey: String?,
    val dragGeneration: Int,
    val oldestEventSeq: Long?,
)

/** A visible item's layout offset is the opposite of scrollToItem's scroll offset. */
internal fun olderPageAnchorScrollOffset(itemOffset: Int): Int = -itemOffset

internal sealed interface TranscriptRow {
    val key: String
    val anchorSeq: Long

    data class Node(val node: dev.dsh.mobile.mesh.core.session.ChatNode) : TranscriptRow {
        override val key: String = transcriptNodeRowKey(node)
        override val anchorSeq: Long = transcriptNodeAnchorSeq(node)
    }

    data class Process(val part: TranscriptPart.Process) : TranscriptRow {
        override val key: String = "process-${part.startSeq}"
        override val anchorSeq: Long = part.startSeq
    }

    data class Command(val activity: ActivityRow.Command) : TranscriptRow {
        override val key: String = activity.commandId?.let { "command-id-$it" } ?: "command-${activity.anchorSeq}"
        override val anchorSeq: Long = activity.anchorSeq
    }

    data class Workflow(val activity: ActivityRow.Workflow) : TranscriptRow {
        override val key: String = activity.runId?.let { "workflow-run-$it" } ?: "workflow-${activity.anchorSeq}"
        override val anchorSeq: Long = activity.anchorSeq
    }
}

/** Group only adjacent visible node parts; never pull an event across a process disclosure. */
internal fun buildTranscriptRows(
    parts: List<TranscriptPart>,
    expanded: Map<Long, Boolean>,
): List<TranscriptRow> = buildTranscriptRows(parts) { startSeq -> expanded[startSeq] == true }

/**
 * As above, with the folded process rows read through a lookup.
 *
 * The holder-backed lookup is the one the transcript uses: it reads snapshot state, so flipping a
 * process row recomposes the whole builder rather than needing the caller to hand back a map.
 */
internal fun buildTranscriptRows(
    parts: List<TranscriptPart>,
    groupActivities: Boolean = true,
    isProcessExpanded: (Long) -> Boolean,
): List<TranscriptRow> {
    val result = mutableListOf<TranscriptRow>()
    val pending = mutableListOf<dev.dsh.mobile.mesh.core.session.ChatNode>()
    fun flush() {
        if (!groupActivities) {
            pending.forEach { result.add(TranscriptRow.Node(it)) }
            pending.clear()
            return
        }
        groupTranscriptActivity(pending).forEach { row ->
            result.add(when (row) {
                is ActivityRow.Node -> TranscriptRow.Node(row.node)
                is ActivityRow.Command -> TranscriptRow.Command(row)
                is ActivityRow.Workflow -> TranscriptRow.Workflow(row)
            })
        }
        pending.clear()
    }
    parts.forEach { part ->
        when (part) {
            is TranscriptPart.Node -> pending.add(part.node)
            is TranscriptPart.Process -> {
                flush()
                result.add(TranscriptRow.Process(part))
                if (isProcessExpanded(part.startSeq)) {
                    pending.addAll(part.materializeNodes())
                    flush()
                }
            }
        }
    }
    flush()
    return result
}

private suspend fun scrollTranscriptToEnd(listState: LazyListState, lastIndex: Int) {
    // scrollToItem(index) aligns the *start* of a long final message with the viewport. Use its
    // measured height as the offset instead, clamped by LazyColumn to the actual content bottom.
    val previousSize = listState.layoutInfo.visibleItemsInfo
        .firstOrNull { it.index == lastIndex }?.size ?: 0
    transcriptPositionLog { "scroll-end-start target=$lastIndex measured=$previousSize ${transcriptLayoutLog(listState)}" }
    listState.scrollToItem(lastIndex, previousSize)
    val measuredSize = listState.layoutInfo.visibleItemsInfo
        .firstOrNull { it.index == lastIndex }?.size ?: previousSize
    transcriptPositionLog { "scroll-end-first target=$lastIndex measured=$measuredSize ${transcriptLayoutLog(listState)}" }
    if (measuredSize != previousSize) {
        listState.scrollToItem(lastIndex, measuredSize)
        transcriptPositionLog { "scroll-end-second target=$lastIndex measured=$measuredSize ${transcriptLayoutLog(listState)}" }
    }
}

private const val TRANSCRIPT_POSITION_TAG = "TranscriptPosition"

private fun transcriptSessionLogId(sessionId: String?): String =
    sessionId?.hashCode()?.toUInt()?.toString(16) ?: "none"

private fun transcriptLayoutLog(state: LazyListState): String {
    val info = state.layoutInfo
    val first = info.visibleItemsInfo.firstOrNull()
    val last = info.visibleItemsInfo.lastOrNull()
    val tailItems = info.visibleItemsInfo.takeLast(5).joinToString { item ->
        "${item.index}@${item.offset}+${item.size}"
    }
    return "scroll=${state.firstVisibleItemIndex}:${state.firstVisibleItemScrollOffset} " +
        "items=${info.totalItemsCount} visible=${first?.index}@${first?.offset}..${last?.index}@${last?.let { it.offset + it.size }} " +
        "tailItems=[$tailItems] canForward=${state.canScrollForward} scrolling=${state.isScrollInProgress} " +
        "viewport=${info.viewportStartOffset}..${info.viewportEndOffset}"
}

private fun transcriptRowsLog(rows: List<TranscriptRow>): String =
    rows.takeLast(6).joinToString(prefix = "[", postfix = "]") { row -> "${row.key}:${row.anchorSeq}" }

private inline fun transcriptPositionLog(message: () -> String) {
    if (BuildConfig.DEBUG) Log.d(TRANSCRIPT_POSITION_TAG, message())
}

/**
 * Distance a drag has pulled toward older history *while the transcript is already at the top*.
 *
 * Scrolling that merely brings the transcript back to the top is navigation, so only travel made
 * once the top is reached counts. Recomputing this on every move, instead of snapshotting "was at
 * the top" on pointer-down, keeps a drag that reaches the top and keeps pulling working and does
 * not lose the pull to a layout pass in between.
 */
internal fun nextTopPullDistance(previous: Float, deltaY: Float, atTop: Boolean): Float =
    if (atTop) (previous + deltaY).coerceAtLeast(0f) else 0f

/** One completed, deliberate pull spent at the top of a transcript with history left to fetch. */
internal fun topPullRequestsOlderPage(
    pullDistancePx: Float,
    thresholdPx: Float,
    firstVisible: Int,
    canPage: Boolean,
): Boolean = canPage && firstVisible <= LOAD_OLDER_THRESHOLD && pullDistancePx >= thresholdPx

/**
 * Whether a reader-driven pull may request a page.
 *
 * [OlderPageGate.failed] is deliberately not a blocker: the paging row already reports the failure,
 * and the pull is itself the retry. Requiring a button tap after one failed page made the gesture
 * look broken.
 */
internal data class OlderPageGate(
    val hasMore: Boolean,
    val loading: Boolean,
    val loadingOlder: Boolean,
    val failed: Boolean,
) {
    val allowsPull: Boolean get() = hasMore && !loading && !loadingOlder
}

/**
 * Key material for the pull detector.
 *
 * The transcript rows and the paging flags are deliberately absent: a streamed row or a page
 * settling mid-drag would change the key, cancel the `pointerInput` coroutine, and drop the gesture
 * in flight — the exact bug this detector must not reintroduce. Latest values are read through
 * [rememberUpdatedState] instead.
 */
internal fun transcriptPullDetectorKeys(sessionId: String?, pullThresholdPx: Float): List<Any?> =
    listOf(sessionId, pullThresholdPx)

/**
 * The conversation itself.
 *
 * Auto-scroll only follows the tail when the reader is already there — scrolling back through a
 * long transcript while a turn streams should not keep yanking the view down. Scrolling the other
 * way pages history in without a button.
 */
internal fun waitingForFirstResponse(running: Boolean, hasAssistant: Boolean): Boolean = running && !hasAssistant

internal fun deepDivingVisible(running: Boolean, turnStartedAtMillis: Long?): Boolean =
    running && turnStartedAtMillis != null

internal fun elapsedTurnSeconds(turnStartedAtMillis: Long, nowMillis: Long): Int =
    ((nowMillis - turnStartedAtMillis).coerceAtLeast(0L) / 1000L).toInt()

@Composable
private fun WaitingForModelRow(turnStartedAtMillis: Long) {
    var elapsedSeconds by remember(turnStartedAtMillis) {
        mutableIntStateOf(elapsedTurnSeconds(turnStartedAtMillis, System.currentTimeMillis()))
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(turnStartedAtMillis, lifecycle) {
        tickWhileVisible(lifecycle.startedStates()) {
            elapsedSeconds = elapsedTurnSeconds(turnStartedAtMillis, System.currentTimeMillis())
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        dev.dsh.mobile.mesh.ui.components.StateDot(
            dev.dsh.mobile.mesh.ui.components.StateDotState.Running,
            size = 8.dp,
        )
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.chat_deep_diving), style = DsType.small13, color = DsTheme.colors.labelSecondary)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.chat_waiting_seconds, elapsedSeconds), style = DsType.caption11, color = DsTheme.colors.labelCaption)
    }
}

@Composable
internal fun ChatTranscript(
    conversation: ConversationSnapshot?,
    loading: Boolean,
    loadingOlder: Boolean,
    loadOlderFailed: Boolean,
    context: ChatNodeContext,
    presentation: TranscriptPresentation,
    listState: LazyListState,
    readingPositions: TranscriptReadingPositions,
    onLoadOlder: suspend (String) -> Boolean,
    onRestoreOlder: suspend (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sessionId = conversation?.sessionId
    val sourceNodes = conversation?.nodes.orEmpty()
    val projectionKey = TranscriptProjectionKey(sessionId, sourceNodes)
    // Build one presentation atomically from the immutable message window. A smaller initial
    // follow window bounds this synchronous pass and avoids mixing stale/quick/pending projections.
    val projection = remember(projectionKey, presentation) {
        val visibleNodes = sourceNodes.filter { it.rendersContent() }
        TranscriptProjection(
            key = projectionKey,
            nodes = visibleNodes,
            parts = partitionTranscript(sourceNodes, presentation),
            hasAssistant = visibleNodes.any { it is AssistantMessageNode },
        )
    }
    val nodes = projection.nodes
    val parts = projection.parts
    val disclosureScope = context.disclosures
    // A history page can complete a turn whose initial-window fragment was previously flat.
    // Preserve those visible rows before LazyColumn measures the prepend, rather than collapsing
    // their anchor in the first frame and reopening it only after the layout has jumped.
    val previousProjection = remember(sessionId) { arrayOfNulls<TranscriptProjection>(1) }
    val newlyFolded = previousProjection[0]?.let { previous ->
        newlyFoldedPrependProcesses(previous.key.sourceNodes, previous.parts, sourceNodes, parts)
    }.orEmpty().filterTo(HashSet()) { !disclosureScope.isSet(DisclosureKeys.process(it)) }
    SideEffect {
        newlyFolded.forEach { disclosureScope.setOpen(DisclosureKeys.process(it), true) }
        previousProjection[0] = projection
    }
    val expandedProcesses by remember(parts, disclosureScope, newlyFolded) {
        derivedStateOf {
            parts.asSequence()
                .filterIsInstance<TranscriptPart.Process>()
                .filter { it.startSeq in newlyFolded || processExpanded(it, disclosureScope) }
                .map { it.startSeq }
                .toSet()
        }
    }
    val rows = remember(parts, expandedProcesses, presentation) {
        buildTranscriptRows(parts, groupActivities = presentation.mode != TranscriptPresentation.Mode.Verbose) {
            startSeq -> startSeq in expandedProcesses
        }
    }
    val hasMore = conversation?.hasMore == true
    val oldestEventSeq = conversation?.oldestEventSeq
    val turnStartedAtMillis = conversation?.turnStartedAtMillis
    val deepDiving = deepDivingVisible(conversation?.running == true, turnStartedAtMillis)
    val showEmpty = nodes.isEmpty() && !deepDiving
    val itemCount = (if (hasMore) 1 else 0) + if (showEmpty) 1 else {
        rows.size + (if (deepDiving && turnStartedAtMillis != null) 1 else 0)
    }
    val hasAssistant = projection.hasAssistant
    val waitingForFirstResponse = waitingForFirstResponse(
        running = conversation?.running == true,
        hasAssistant = hasAssistant,
    )

    // Only explicit reader gestures change follow intent. Layout remeasurement (large changed-file
    // lists, loading images, keyboard resizing) must never initiate a programmatic scroll.
    var followTail by remember(sessionId) {
        mutableStateOf(sessionId?.let(readingPositions::get)?.atBottom ?: true)
    }
    var gestureCanResumeTail by remember(sessionId) { mutableStateOf(false) }
    // Only a session restored at the tail needs to wait for asynchronously measured content.
    // An explicit reader action immediately takes ownership of the viewport again.
    var restoringTail by remember(sessionId) { mutableStateOf(false) }
    val onContentExpanded: () -> Unit = {
        followTail = false
        gestureCanResumeTail = false
        restoringTail = false
    }
    var restoredSession by remember { mutableStateOf<String?>(null) }
    var restoreTarget by remember(sessionId) { mutableStateOf<TranscriptReadingPosition?>(null) }
    var restoreCanceled by remember(sessionId) { mutableStateOf(false) }
    var olderPageAnchor by remember(sessionId) { mutableStateOf<OlderPageAnchor?>(null) }
    var olderPageRequest by remember(sessionId) { mutableIntStateOf(0) }
    var olderPagePending by remember(sessionId) { mutableStateOf(false) }
    val currentLoadOlder by rememberUpdatedState(onLoadOlder)
    LaunchedEffect(sessionId, olderPageRequest) {
        if (olderPageRequest == 0 || sessionId == null) return@LaunchedEffect
        val request = olderPageRequest
        val anchor = olderPageAnchor
        try {
            val installed = try {
                currentLoadOlder(sessionId)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TRANSCRIPT_POSITION_TAG, "Failed to load older messages", error)
                false
            }
            // A successful request has already published its page into the snapshot the reader
            // sees, so the gate above can be released. Only a request that installed nothing
            // discards its anchor immediately.
            if (!installed && olderPageRequest == request && olderPageAnchor === anchor) {
                olderPageAnchor = null
            }
        } finally {
            if (olderPageRequest == request) olderPagePending = false
        }
    }
    val userDragging by listState.interactionSource.collectIsDraggedAsState()
    var dragGeneration by remember(sessionId) { mutableIntStateOf(0) }
    LaunchedEffect(listState, sessionId) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    dragGeneration++
                    gestureCanResumeTail = false
                    followTail = false
                    restoringTail = false
                    if (restoredSession != sessionId || restoreTarget != null) restoreCanceled = true
                    restoreTarget = null
                }
                is DragInteraction.Stop -> {
                    gestureCanResumeTail = true
                    if (!listState.canScrollForward) followTail = true
                }
                else -> Unit
            }
        }
    }
    LaunchedEffect(listState, sessionId, restoredSession) {
        if (restoredSession != sessionId) return@LaunchedEffect
        // A fling can arrive at the bottom after Drag.Stop; only an active scroll may re-enable
        // following. A passive remeasure or disclosure expansion never does.
        snapshotFlow { gestureCanResumeTail && listState.isScrollInProgress && !listState.canScrollForward }.collect { reachedBottom ->
            if (reachedBottom) followTail = true
        }
    }
    LaunchedEffect(listState, sessionId, restoredSession) {
        if (restoredSession != sessionId) return@LaunchedEffect
        // Rows can finish measuring after the transcript settled: a Markdown document that is not in
        // the render cache starts at a placeholder height, and growing a row pushes the last row
        // below the viewport without any reader gesture or data change. That is exactly what a
        // finished answer does — a Markdown image finishes loading and reports its real height a
        // frame later. A restored tail re-checks every layout pass; a reader who is only following
        // the tail is corrected only while the same first row is still on screen, so a paging
        // re-anchor or any other programmatic scroll that moves the viewport is never fought.
        var tailFirstKey: Any? = null
        snapshotFlow { listState.layoutInfo }.collect { info ->
            val first = info.visibleItemsInfo.firstOrNull()
            val firstKey = first?.key
            val atTail = !listState.canScrollForward
            if (atTail) tailFirstKey = firstKey
            // Re-measuring the last row changes its offset (a placeholder height snap is anchored at
            // its start), so the key is the stable signal: same first row, no longer at the end.
            val rowGrewUnderViewport = !atTail && firstKey != null && firstKey == tailFirstKey
            if (!restoringTail && !(followTail && rowGrewUnderViewport)) return@collect
            if (userDragging || listState.isScrollInProgress || restoreTarget != null) return@collect
            // This emission can arrive while the list is already measuring: a Markdown document
            // reports its final height during the same frame that remeasures its row. Ask for the
            // next frame so the correction never remeasures a list that is measuring right now.
            withFrameNanos { }
            if (userDragging || listState.isScrollInProgress || !listState.canScrollForward) return@collect
            if (!restoringTail && !followTail) return@collect
            scrollTranscriptToEnd(listState, listState.layoutInfo.totalItemsCount - 1)
        }
    }
    val exitRows by rememberUpdatedState(rows)
    val exitHasMore by rememberUpdatedState(hasMore)
    val exitReady by rememberUpdatedState(restoredSession == sessionId && restoreTarget == null)
    val exitFollowTail by rememberUpdatedState(followTail)
    DisposableEffect(sessionId, listState, readingPositions) {
        onDispose {
            val saved = sessionId?.let(readingPositions::get)
            // A fast fling followed immediately by the session-list button can remove this page
            // before snapshotFlow records its final viewport. Capture the actual laid-out row at
            // the navigation boundary, not the tail that a later follow window may display.
            if (sessionId != null && exitReady) {
                val info = listState.layoutInfo
                val position = info.visibleItemsInfo.firstNotNullOfOrNull { item ->
                    readingPositionForVisibleRow(exitRows, exitHasMore, item.index, item.key,
                        listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset,
                        exitFollowTail)
                }
                if (position != null && (saved == null || readingPositionIndex(exitRows, saved) >= 0)) {
                    readingPositions.put(sessionId, position)
                }
            }
            transcriptPositionLog {
                "leave session=${transcriptSessionLogId(sessionId)} saved=${sessionId?.let(readingPositions::get)?.let { "seq=${it.seq},offset=${it.offset},bottom=${it.atBottom}" } ?: "none"} ${transcriptLayoutLog(listState)}"
            }
        }
    }
    // Keyed on the *newest* seq, not the item count, so only growth at the tail moves the view.
    // Counting items conflated two opposite events: a turn streaming in at the bottom, which should
    // follow, and a page of history arriving at the top, which must not — asking for older messages
    // and being thrown back to the newest one is the opposite of what the tap meant. The paging row
    // appearing and disappearing changed the count too, which moved the view for no reason at all.
    val newestRowKey = nodes.lastOrNull()?.let(::transcriptNodeRowKey)
    var lastSession by remember { mutableStateOf<String?>(null) }
    // Save the actual first visible keyed row; do not infer navigation from changes in
    // pixel offsets, item heights, or viewport dimensions.
    LaunchedEffect(listState, sessionId, rows, hasMore, restoredSession, restoreTarget) {
        if (sessionId == null || restoredSession != sessionId || restoreTarget != null) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            info.visibleItemsInfo.firstNotNullOfOrNull { item ->
                readingPositionForVisibleRow(rows, hasMore, item.index, item.key,
                    listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset,
                    followTail && !listState.canScrollForward)
            }
        }.collect { position ->
            if (position != null && restoreTarget == null && shouldRecordReadingPosition(
                    rows, readingPositions.get(sessionId), userDragging, position.atBottom,
                )) readingPositions.put(sessionId, position)
        }
    }
    SideEffect {
        val anchor = olderPageAnchor
        if (anchor != null) {
            if (shouldDiscardOlderPageAnchor(anchor.dragGeneration, dragGeneration)) {
                olderPageAnchor = null
            } else if (rows.firstOrNull()?.key != anchor.firstRowKey) {
                val newIndex = rows.indexOfFirst { it.anchorSeq == anchor.seq }
                if (newIndex >= 0) {
                    // Re-anchor in the prepend's remeasure, never with a second scroll after it.
                    listState.requestScrollToItem(newIndex + if (hasMore) 1 else 0, anchor.offset)
                    olderPageAnchor = null
                } else if (!hasMore) olderPageAnchor = null
            }
            // An empty page ends paging; a page of non-rendered records can advance the
            // authoritative cursor without changing the first visible row.
            if (!hasMore || oldestEventSeq != anchor.oldestEventSeq) olderPageAnchor = null
        }
    }

    LaunchedEffect(newestRowKey, nodes.lastOrNull(), sessionId) {
        // A history snapshot can initially contain only structural events and the paging row.
        // Treating that sentinel as the restored session consumes the one-time restore before
        // the first readable message arrives on the next server page.
        if (!canRestoreReadingPosition(rows)) return@LaunchedEffect
        if (restoreCanceled && restoredSession != sessionId) {
            lastSession = sessionId
            restoredSession = sessionId
            return@LaunchedEffect
        }
        val switched = sessionId != lastSession
        // An existing session returns to the message the reader was viewing, not the newest
        // turn. New sessions (or anchors outside the available snapshot) still open at the tail.
        if (switched) {
            val saved = sessionId?.let(readingPositions::get)
            val index = saved?.let { readingPositionIndex(rows, it) } ?: -1
            transcriptPositionLog {
                "restore-start session=${transcriptSessionLogId(sessionId)} rows=${rows.size} hasMore=$hasMore loading=$loading " +
                    "saved=${saved?.let { "seq=${it.seq},offset=${it.offset},bottom=${it.atBottom}" } ?: "none"} " +
                    "anchorIndex=$index rowTail=${transcriptRowsLog(rows)} ${transcriptLayoutLog(listState)}"
            }
            if (shouldRestoreTranscriptToBottom(saved)) {
                // End position is a semantic anchor: the first visible row can be an earlier
                // node in the current turn, so restoring that row would move a tail reader up.
                scrollTranscriptToEnd(listState, itemCount - 1)
                followTail = true
                restoringTail = true
            } else if (index >= 0) {
                listState.scrollToItem(index + if (hasMore) 1 else 0, saved!!.offset)
                followTail = false
            } else if (shouldWaitForReadingAnchor(saved, rows, hasMore)) {
                // Opening a file unmounts this page. A newer follow window may not contain the
                // historical row yet: keep the saved coordinate until authoritative pages arrive.
                // Jumping to the tail here would also overwrite that anchor in the observer.
                restoreTarget = saved
                followTail = false
            } else {
                scrollTranscriptToEnd(listState, itemCount - 1)
                followTail = true
            }
            // scrollToItem is suspending; a new follow snapshot can cancel this effect before
            // it completes. Commit the switch only after positioning succeeds so it can retry.
            lastSession = sessionId
            restoredSession = sessionId
            transcriptPositionLog {
                "restore-done session=${transcriptSessionLogId(sessionId)} path=${if (shouldRestoreTranscriptToBottom(saved)) "tail" else if (index >= 0) "anchor" else if (restoreTarget != null) "await-anchor" else "fallback-tail"} " +
                    "rowTail=${transcriptRowsLog(rows)} ${transcriptLayoutLog(listState)}"
            }
        }
    }

    // A last update can arrive while a reader's fling is still settling. Retry when the gesture
    // settles or re-enables following rather than waiting for another transcript update.
    LaunchedEffect(newestRowKey, nodes.lastOrNull(), sessionId, restoredSession, followTail,
        restoreTarget, loadingOlder) {
        if (restoredSession == sessionId && followTail && restoreTarget == null &&
            !loadingOlder && canRestoreReadingPosition(rows)) {
            // Do not key this effect on scroll state: our own scrollToItem changes that state
            // and would cancel the very correction it is performing.
            snapshotFlow { listState.isScrollInProgress || userDragging }.first { scrolling -> !scrolling }
            scrollTranscriptToEnd(listState, itemCount - 1)
        }
    }

    // The opening follow snapshot can replace a cached, deeper window with only its newest page.
    // A returning reader can be farther back than the opening follow window. Fetch only the
    // authoritative pages needed to reach that row, never substituting a tail position while it
    // is missing. The oldest row must advance before another page request is permitted.
    val restoreScope = rememberCoroutineScope()
    var restorePageOldestSeq by remember(sessionId) { mutableStateOf<Long?>(null) }
    LaunchedEffect(sessionId, rows, hasMore, oldestEventSeq, loadingOlder, restoredSession, restorePageOldestSeq, restoreTarget, restoreCanceled, userDragging) {
        if (sessionId == null || restoreCanceled) return@LaunchedEffect
        val saved = readingPositions.get(sessionId) ?: return@LaunchedEffect
        // An opening page can contain only non-rendered journal events. Fetch history without
        // declaring the session restored until a readable message has actually arrived.
        if (restoredSession != sessionId && rows.isNotEmpty()) return@LaunchedEffect
        val target = restoreTarget
        if (target != null) {
            val index = readingPositionIndex(rows, target)
            if (index >= 0) {
                // A user scroll while the request was in flight supersedes the old anchor.
                if (!userDragging && readingPositions.get(sessionId) == target) {
                    listState.scrollToItem(index + if (hasMore) 1 else 0, target.offset)
                    followTail = false
                }
                restoreTarget = null
            } else if (saved != target) {
                restoreTarget = null
            } else if (shouldFetchReadingAnchorAfterPage(rows, target, hasMore, loadingOlder, restorePageOldestSeq, oldestEventSeq)) {
                restorePageOldestSeq = oldestEventSeq
                restoreScope.launch { onRestoreOlder(sessionId) }
            } else if (!hasMore && !loadingOlder) {
                // The saved message no longer exists on the server.
                restoreTarget = null
                scrollTranscriptToEnd(listState, itemCount - 1)
                followTail = true
            }
        } else if (shouldFetchReadingAnchorAfterPage(rows, saved, hasMore, loadingOlder, restorePageOldestSeq, oldestEventSeq)) {
            restoreTarget = saved
            restorePageOldestSeq = oldestEventSeq
            restoreScope.launch { onRestoreOlder(sessionId) }
        }
    }

    // A single, deliberate pull at the top requests older history. Do not infer a gesture from
    // the list index: layout shifts and flings can also move it to the top.
    val canPage = OlderPageGate(
        hasMore = hasMore,
        loading = loading,
        loadingOlder = loadingOlder,
        failed = loadOlderFailed,
    ).allowsPull
    val pullThresholdPx = with(LocalDensity.current) { TOP_PULL_DISTANCE.toPx() }
    val requestOlderPage: () -> Unit = {
        if (!olderPagePending && sessionId != null) {
            olderPagePending = true
            val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index > 0 }
            olderPageAnchor = visible?.let { item ->
                rows.getOrNull(item.index - 1)?.let { row ->
                    OlderPageAnchor(row.anchorSeq, olderPageAnchorScrollOffset(item.offset),
                        rows.firstOrNull()?.key, dragGeneration, oldestEventSeq)
                }
            }
            olderPageRequest++
        }
    }
    // Read through updated state so the gesture detector below can keep its single instance while
    // the transcript streams and the paging flags change underneath it.
    val canPageState = rememberUpdatedState(canPage)
    val requestOlderPageState = rememberUpdatedState(requestOlderPage)

    if (loading) {
        TranscriptSkeleton(modifier)
        return
    }

    SelectionContainer {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Only the session and the pull threshold are keys. Keying on the row list or on
                // `canPage` restarted this detector whenever a live turn streamed another row or a
                // page finished: the drag in flight was cancelled, its distance lost, and the pull
                // did nothing until the reader tapped the paging button. Latest values are read
                // from snapshot state instead, so recomposition cannot kill a pull.
                .pointerInput(*transcriptPullDetectorKeys(sessionId, pullThresholdPx).toTypedArray()) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var pullDistance = 0f
                        var released = false
                        while (true) {
                            val change = awaitPointerEvent(PointerEventPass.Initial).changes
                                .firstOrNull { it.id == down.id } ?: break
                            pullDistance = nextTopPullDistance(
                                previous = pullDistance,
                                deltaY = change.positionChange().y,
                                atTop = !listState.canScrollBackward,
                            )
                            if (!change.pressed) {
                                released = true
                                break
                            }
                        }
                        // A cancelled pointer never lifted: it must not page.
                        if (!released) return@awaitEachGesture
                        val canPageNow = canPageState.value
                        val shouldPage = topPullRequestsOlderPage(
                            pullDistancePx = pullDistance,
                            thresholdPx = pullThresholdPx,
                            firstVisible = listState.firstVisibleItemIndex,
                            canPage = canPageNow,
                        )
                        transcriptPositionLog {
                            "pull session=${transcriptSessionLogId(sessionId)} distance=$pullDistance " +
                                "threshold=$pullThresholdPx first=${listState.firstVisibleItemIndex} " +
                                "canPage=$canPageNow request=$shouldPage"
                        }
                        if (shouldPage) requestOlderPageState.value()
                    }
                },
        ) {
            LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        // Bottom-anchored: a transcript shorter than the viewport belongs above the composer, not
        // pinned under the tab strip with the empty half below it.
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom),
    ) {
        if (hasMore) {
            // Present whenever there is more to fetch, so index 0 stays stable across pages.
            item(key = "load-older") {
                LoadOlderRow(
                    loading = loadingOlder,
                    failed = loadOlderFailed,
                    offerManual = true,
                    onRetry = requestOlderPage,
                )
            }
        }
        if (showEmpty) {
            item(key = "empty") {
                EmptyHero(
                    headline = stringResource(R.string.chat_empty_title),
                    subtitle = stringResource(R.string.chat_empty_hint),
                )
            }
        } else {
            items(rows, key = { it.key }) { row ->
                when (row) {
                    is TranscriptRow.Node -> CompositionLocalProvider(LocalTranscriptContentExpanded provides onContentExpanded) {
                        ChatNodeItem(node = row.node, context = context)
                    }
                    is TranscriptRow.Command -> CommandActivityRow(row.activity, context)
                    is TranscriptRow.Workflow -> WorkflowActivityRow(row.activity, context)
                    is TranscriptRow.Process -> {
                        val processKey = DisclosureKeys.process(row.part.startSeq)
                        val open = row.part.startSeq in expandedProcesses
                        val title = if (open) stringResource(R.string.chat_process_hide, row.part.nodeCount)
                            else stringResource(R.string.chat_process_summary, row.part.nodeCount)
                        val detail = liveProcessDetail(row.part, presentation, context.cwd)
                        Text(
                            text = if (detail.isNullOrBlank()) title else "$title · $detail",
                            modifier = Modifier.fillMaxWidth().clickable {
                                disclosureScope.setOpen(processKey, !open)
                            }.padding(horizontal = 8.dp, vertical = 8.dp),
                            style = DsType.small13,
                            color = DsTheme.colors.labelSecondary,
                        )
                    }
                }
            }
            if (deepDiving && turnStartedAtMillis != null) {
                item(key = "waiting-for-model") {
                    WaitingForModelRow(turnStartedAtMillis)
                }
            }
        }
        }
    }
    }
}

/**
 * Head of the transcript while more history exists.
 *
 * The row is quiet during normal paging, but a deliberate pull at the top requests the next page
 * without requiring a button tap. It speaks up while fetching and offers a retry when a page
 * failed.
 *
 * [offerManual] keeps the button available: a session whose events are mostly not messages can stay
 * too short to scroll, and without a button there would be nothing left to trigger a page.
 */
@Composable
private fun LoadOlderRow(
    loading: Boolean,
    failed: Boolean,
    offerManual: Boolean,
    onRetry: () -> Unit,
) {
    val colors = DsTheme.colors
    when {
        // A pull can retry a failed page, so activity wins over the retry button while the next
        // page is in flight instead of leaving the failure on screen until it settles.
        loading -> Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = colors.labelTertiary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.chat_loading_older),
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
        }

        failed -> DsButton(
            text = stringResource(R.string.chat_load_older_retry),
            onClick = onRetry,
            variant = DsButtonVariant.Ghost,
            size = DsButtonSize.Small,
            modifier = Modifier.fillMaxWidth(),
        )

        offerManual -> DsButton(
            text = stringResource(R.string.chat_load_older),
            onClick = onRetry,
            variant = DsButtonVariant.Ghost,
            size = DsButtonSize.Small,
            modifier = Modifier.fillMaxWidth(),
        )

        else -> Spacer(Modifier.height(1.dp))
    }
}

/** Placeholder bubbles while a session's history loads, instead of an empty white screen. */
@Composable
private fun TranscriptSkeleton(modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        listOf(0.55f, 0.9f, 0.75f, 0.4f).forEach { fraction ->
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(14.dp)
                    .skeleton(colors.bgLayer2, colors.hover),
            )
        }
    }
}
