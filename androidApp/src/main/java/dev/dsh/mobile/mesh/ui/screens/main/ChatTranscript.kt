package dev.dsh.mobile.mesh.ui.screens.main

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.dsh.mobile.mesh.BuildConfig
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.core.session.AssistantMessageNode
import dev.dsh.mobile.mesh.core.session.ConversationSnapshot
import dev.dsh.mobile.mesh.ui.components.DsButton
import dev.dsh.mobile.mesh.ui.components.DsButtonSize
import dev.dsh.mobile.mesh.ui.components.DsButtonVariant
import dev.dsh.mobile.mesh.ui.components.EmptyHero
import dev.dsh.mobile.mesh.ui.components.skeleton
import dev.dsh.mobile.mesh.ui.theme.DsTheme
import dev.dsh.mobile.mesh.ui.theme.DsType
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

/** Placement animation is disabled because disclosure expansion must not animate every sibling. */
internal fun transcriptItemsAnimatePlacement(): Boolean = false

/**
 * How many pages the transcript may fetch on its own before it needs to be asked.
 *
 * Paging used to be automatic without limit while the transcript was shorter than the screen. That
 * reads as reasonable and is not: a page is counted in *events*, and most events — chunk deltas,
 * tool traffic, turn boundaries — render nothing at all. A session whose log is mostly machinery
 * therefore never fills the screen however much is loaded, so the fill loop pulled the entire
 * history in, four thousand events at a time, re-folding everything already held on each pass until
 * the heap gave out.
 *
 * One extra page is the whole of what the fill is for. A page carries up to sixty messages, which
 * is several screens' worth already; if it still does not reach the bottom of the viewport then the
 * session's log is mostly machinery, and pulling more of it is buying thousands more events for a
 * row or two. Past that the reader asks, via the row at the head of the list.
 */
private const val MAX_AUTO_PAGES = 1

private data class OlderPageAnchor(val seq: Long, val offset: Int)

internal sealed interface TranscriptRow {
    val key: String
    val anchorSeq: Long

    data class Node(val node: dev.dsh.mobile.mesh.core.session.ChatNode) : TranscriptRow {
        override val key: String = "node-${node.seq}"
        override val anchorSeq: Long = node.seq
    }

    data class Process(val part: TranscriptPart.Process) : TranscriptRow {
        override val key: String = "process-${part.startSeq}"
        override val anchorSeq: Long = part.startSeq
    }

    data class Command(val activity: ActivityRow.Command) : TranscriptRow {
        override val key: String = "command-${activity.anchorSeq}"
        override val anchorSeq: Long = activity.anchorSeq
    }

    data class Workflow(val activity: ActivityRow.Workflow) : TranscriptRow {
        override val key: String = "workflow-${activity.anchorSeq}"
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
    isProcessExpanded: (Long) -> Boolean,
): List<TranscriptRow> {
    val result = mutableListOf<TranscriptRow>()
    val pending = mutableListOf<dev.dsh.mobile.mesh.core.session.ChatNode>()
    fun flush() {
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
                    pending.addAll(part.nodes)
                    flush()
                }
            }
        }
    }
    flush()
    return result
}

internal fun shouldFollowTranscriptLayoutShift(
    wasNearBottom: Boolean,
    userDragging: Boolean,
    scrollInProgress: Boolean,
    previouslyCanScrollForward: Boolean?,
    canScrollForward: Boolean,
    measurementChanged: Boolean,
    sameScrollCoordinate: Boolean,
): Boolean = wasNearBottom && !userDragging && !scrollInProgress && canScrollForward &&
    sameScrollCoordinate && (previouslyCanScrollForward == false || measurementChanged)

internal fun nextTranscriptTailIntent(
    currentIntent: Boolean,
    measuredAtBottom: Boolean,
    userDragging: Boolean,
    previousIndex: Int?,
    previousOffset: Int,
    currentIndex: Int,
    currentOffset: Int,
): Boolean {
    if (measuredAtBottom) return true
    val movedTowardHistory = previousIndex != null &&
        (currentIndex < previousIndex || (currentIndex == previousIndex && currentOffset < previousOffset))
    return if (userDragging && movedTowardHistory) false else currentIntent
}

private data class TranscriptLayoutSample(
    val itemCount: Int,
    val lastVisibleIndex: Int?,
    val lastVisibleEnd: Int,
    val viewportEnd: Int,
    val viewportHeight: Int,
    val canScrollForward: Boolean,
    val scrollInProgress: Boolean,
    val firstVisibleIndex: Int,
    val firstVisibleOffset: Int,
    val userDragging: Boolean,
    val visibleItemMeasurements: List<Pair<Int, Int>>,
)

private data class TranscriptReadingSample(
    val position: TranscriptReadingPosition,
    val firstVisibleIndex: Int,
    val firstVisibleOffset: Int,
    val userDragging: Boolean,
)

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

internal fun transcriptNearBottom(
    itemCount: Int,
    lastVisibleIndex: Int,
    lastVisibleEnd: Int,
    viewportEnd: Int,
    tolerancePx: Int,
): Boolean = itemCount == 0 ||
    (lastVisibleIndex == itemCount - 1 && lastVisibleEnd <= viewportEnd + tolerancePx)

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
 * Decide whether the top sentinel may request another page. A reconnect can replace the visible
 * window while the list is already at index zero; that is not a reader gesture and must not be
 * mistaken for permission to walk the entire history.
 */
internal fun shouldPageAtTop(
    firstVisible: Int,
    fillsViewport: Boolean,
    autoPages: Int,
    maxAutoPages: Int,
    userScrolling: Boolean,
): Boolean {
    if (firstVisible > LOAD_OLDER_THRESHOLD) return false
    // A filled viewport at index zero is also the normal post-reconnect layout. Only an active user
    // scroll may page it; a programmatic re-anchor must not walk the whole history.
    if (fillsViewport && !userScrolling) return false
    return if (userScrolling) true else autoPages < maxAutoPages
}

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
    listState: LazyListState,
    readingPositions: TranscriptReadingPositions,
    onLoadOlder: () -> Unit,
    onRestoreOlder: suspend (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Only the nodes that draw something: a zero-height item still costs its 4dp gap, and a turn's
    // worth of structural events stacks those gaps into a blank band under the chrome.
    val nodes = remember(conversation?.nodes) {
        conversation?.nodes.orEmpty().filter { it.rendersContent() }
    }
    val sessionId = conversation?.sessionId
    DisposableEffect(sessionId, listState, readingPositions) {
        onDispose {
            val saved = sessionId?.let(readingPositions::get)
            transcriptPositionLog {
                "leave session=${transcriptSessionLogId(sessionId)} saved=${saved?.let { "seq=${it.seq},offset=${it.offset},bottom=${it.atBottom}" } ?: "none"} ${transcriptLayoutLog(listState)}"
            }
        }
    }
    val disclosureScope = context.disclosures
    val isProcessExpanded: (Long) -> Boolean = { startSeq -> disclosureScope.isOpen(DisclosureKeys.process(startSeq)) }
    val parts = remember(conversation?.nodes) { partitionTranscript(conversation?.nodes.orEmpty()) }
    val rows = buildTranscriptRows(parts, isProcessExpanded)
    val hasMore = conversation?.hasMore == true
    val itemCount = rows.size + if (hasMore) 1 else 0
    val turnStartedAtMillis = conversation?.turnStartedAtMillis
    val deepDiving = deepDivingVisible(conversation?.running == true, turnStartedAtMillis)
    val waitingForFirstResponse = waitingForFirstResponse(
        running = conversation?.running == true,
        hasAssistant = nodes.any { it is AssistantMessageNode },
    )

    // Both keyed on the session so a freshly opened one starts from a clean assumption rather than
    // inheriting the previous transcript's position — and so the collector always writes to the
    // state the composition is currently reading.
    var tailIntent by remember(sessionId) {
        mutableStateOf(sessionId?.let(readingPositions::get)?.atBottom ?: true)
    }
    var wasNearBottom by remember(sessionId) { mutableStateOf(tailIntent) }
    var restoredSession by remember { mutableStateOf<String?>(null) }
    var olderPageAnchor by remember(sessionId) { mutableStateOf<OlderPageAnchor?>(null) }
    val userDragging by listState.interactionSource.collectIsDraggedAsState()
    val bottomTolerancePx = with(LocalDensity.current) { 48.dp.roundToPx() }
    LaunchedEffect(listState, sessionId, itemCount, restoredSession, bottomTolerancePx) {
        // Do not let an empty/stale layout from before the initial anchor restore reset its
        // tail-follow state. Only a visible row in the restored transcript is meaningful.
        if (restoredSession != sessionId) return@LaunchedEffect
        var previousViewportHeight: Int? = null
        var previousCanScrollForward: Boolean? = null
        var previousScrollCoordinate: Pair<Int, Int>? = null
        var previousItemMeasurements: List<Pair<Int, Int>>? = null
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            TranscriptLayoutSample(
                itemCount = info.totalItemsCount,
                lastVisibleIndex = last?.index,
                lastVisibleEnd = (last?.offset ?: 0) + (last?.size ?: 0),
                viewportEnd = info.viewportEndOffset,
                viewportHeight = info.viewportEndOffset - info.viewportStartOffset,
                canScrollForward = listState.canScrollForward,
                scrollInProgress = listState.isScrollInProgress,
                firstVisibleIndex = listState.firstVisibleItemIndex,
                firstVisibleOffset = listState.firstVisibleItemScrollOffset,
                userDragging = userDragging,
                visibleItemMeasurements = info.visibleItemsInfo.map { it.index to it.size },
            )
        }.collect { sample ->
            val lastIndex = sample.lastVisibleIndex ?: return@collect
            val viewportChanged = previousViewportHeight != null && previousViewportHeight != sample.viewportHeight
            val currentCoordinate = sample.firstVisibleIndex to sample.firstVisibleOffset
            val layoutShiftedPastTail = shouldFollowTranscriptLayoutShift(
                wasNearBottom = wasNearBottom,
                userDragging = sample.userDragging,
                scrollInProgress = sample.scrollInProgress,
                previouslyCanScrollForward = previousCanScrollForward,
                canScrollForward = sample.canScrollForward,
                measurementChanged = previousItemMeasurements != null && previousItemMeasurements != sample.visibleItemMeasurements,
                sameScrollCoordinate = previousScrollCoordinate == currentCoordinate,
            )
            if ((layoutShiftedPastTail || viewportChanged) && wasNearBottom && sample.itemCount > 0) {
                transcriptPositionLog {
                    "follow-layout-shift session=${transcriptSessionLogId(sessionId)} " +
                        "shift=$layoutShiftedPastTail measurementsChanged=${previousItemMeasurements != sample.visibleItemMeasurements} " +
                        "viewportChanged=$viewportChanged ${transcriptLayoutLog(listState)}"
                }
                scrollTranscriptToEnd(listState, sample.itemCount - 1)
                wasNearBottom = true
            }
            val measuredNearBottom = transcriptNearBottom(
                sample.itemCount,
                lastIndex,
                sample.lastVisibleEnd,
                sample.viewportEnd,
                bottomTolerancePx,
            )
            // A streamed row may grow between measurement and auto-follow. Preserve tail intent
            // across a layout-only shift if the scroll coordinate itself did not change.
            wasNearBottom = measuredNearBottom || tailIntent || (wasNearBottom && !sample.userDragging) ||
                (viewportChanged && wasNearBottom) || (layoutShiftedPastTail && wasNearBottom)
            previousViewportHeight = sample.viewportHeight
            previousCanScrollForward = sample.canScrollForward
            previousScrollCoordinate = currentCoordinate
            previousItemMeasurements = sample.visibleItemMeasurements
        }
    }

    // Keyed on the *newest* seq, not the item count, so only growth at the tail moves the view.
    // Counting items conflated two opposite events: a turn streaming in at the bottom, which should
    // follow, and a page of history arriving at the top, which must not — asking for older messages
    // and being thrown back to the newest one is the opposite of what the tap meant. The paging row
    // appearing and disappearing changed the count too, which moved the view for no reason at all.
    val newestSeq = nodes.lastOrNull()?.seq
    var lastSession by remember { mutableStateOf<String?>(null) }
    var restoreTarget by remember(sessionId) { mutableStateOf<TranscriptReadingPosition?>(null) }
    // Observe only a laid-out row belonging to this session, and only after the initial restore.
    // In particular, an empty/loading snapshot must not replace a saved reading anchor.
    LaunchedEffect(listState, sessionId, rows, hasMore, restoredSession, restoreTarget) {
        if (sessionId == null || restoredSession != sessionId) return@LaunchedEffect
        // A follow snapshot can replace the cached older window with only the newest page.
        // Its automatic LazyColumn re-anchor is not a reader action: do not overwrite the
        // last known coordinate with whatever unrelated row happens to occupy that viewport.
        val saved = readingPositions.get(sessionId)
        var previousLoggedBottom: Boolean? = null
        var previousScrollCoordinate: Pair<Int, Int>? = null
        snapshotFlow {
            val info = listState.layoutInfo
            val first = info.visibleItemsInfo.firstOrNull { item ->
                rows.getOrNull(item.index - if (hasMore) 1 else 0)?.key == item.key
            }
            first?.let { item ->
                val row = rows.getOrNull(item.index - if (hasMore) 1 else 0) ?: return@let null
                val last = info.visibleItemsInfo.lastOrNull()
                val atBottom = last != null && transcriptNearBottom(
                    info.totalItemsCount,
                    last.index,
                    last.offset + last.size,
                    info.viewportEndOffset,
                    bottomTolerancePx,
                )
                TranscriptReadingSample(
                    position = readingPositionOf(
                        row,
                        if (item.index == listState.firstVisibleItemIndex) listState.firstVisibleItemScrollOffset else 0,
                        atBottom,
                    ),
                    firstVisibleIndex = listState.firstVisibleItemIndex,
                    firstVisibleOffset = listState.firstVisibleItemScrollOffset,
                    userDragging = userDragging,
                )
            }
        }.collect { sample ->
            if (sample == null) return@collect
            val position = sample.position
            if (restoreTarget != null && sample.userDragging) restoreTarget = null
            val currentCoordinate = sample.firstVisibleIndex to sample.firstVisibleOffset
            tailIntent = nextTranscriptTailIntent(
                currentIntent = tailIntent,
                measuredAtBottom = position.atBottom,
                userDragging = sample.userDragging,
                previousIndex = previousScrollCoordinate?.first,
                previousOffset = previousScrollCoordinate?.second ?: 0,
                currentIndex = sample.firstVisibleIndex,
                currentOffset = sample.firstVisibleOffset,
            )
            wasNearBottom = tailIntent || position.atBottom
            val positionToSave = position.copy(atBottom = tailIntent || position.atBottom)
            if (previousLoggedBottom != position.atBottom) {
                val shouldRecord = restoreTarget == null && shouldRecordReadingPosition(
                    rows, saved, sample.userDragging, positionToSave.atBottom,
                )
                transcriptPositionLog {
                    "observe session=${transcriptSessionLogId(sessionId)} seq=${position.seq} offset=${position.offset} " +
                        "bottom=${position.atBottom} tailIntent=${tailIntent} drag=${sample.userDragging} record=$shouldRecord " +
                        "savedBottom=${saved?.atBottom} coordinate=$currentCoordinate previous=$previousScrollCoordinate " +
                        transcriptLayoutLog(listState)
                }
                previousLoggedBottom = position.atBottom
            }
            if (restoreTarget == null && shouldRecordReadingPosition(rows, saved, sample.userDragging, positionToSave.atBottom)) {
                readingPositions.put(sessionId, positionToSave)
            }
            previousScrollCoordinate = currentCoordinate
        }
    }
    LaunchedEffect(rows, hasMore, sessionId) {
        val anchor = olderPageAnchor ?: return@LaunchedEffect
        val newIndex = rows.indexOfFirst { it.anchorSeq == anchor.seq }
        if (newIndex >= 0) listState.scrollToItem(newIndex + if (hasMore) 1 else 0, anchor.offset)
        olderPageAnchor = null
    }

    LaunchedEffect(newestSeq, sessionId) {
        // A history snapshot can initially contain only structural events and the paging row.
        // Treating that sentinel as the restored session consumes the one-time restore before
        // the first readable message arrives on the next server page.
        if (!canRestoreReadingPosition(rows)) return@LaunchedEffect
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
                tailIntent = true
                wasNearBottom = true
            } else if (index >= 0) {
                listState.scrollToItem(index + if (hasMore) 1 else 0, saved!!.offset)
                tailIntent = false
                wasNearBottom = false
            } else {
                scrollTranscriptToEnd(listState, itemCount - 1)
                tailIntent = true
                wasNearBottom = true
            }
            // scrollToItem is suspending; a new follow snapshot can cancel this effect before
            // it completes. Commit the switch only after positioning succeeds so it can retry.
            lastSession = sessionId
            restoredSession = sessionId
            transcriptPositionLog {
                "restore-done session=${transcriptSessionLogId(sessionId)} path=${if (shouldRestoreTranscriptToBottom(saved)) "tail" else if (index >= 0) "anchor" else "fallback-tail"} " +
                    "rowTail=${transcriptRowsLog(rows)} ${transcriptLayoutLog(listState)}"
            }
        } else if (wasNearBottom) scrollTranscriptToEnd(listState, itemCount - 1)
    }

    // The opening follow snapshot can replace a cached, deeper window with only its newest page.
    // Ask the authoritative session/page endpoint for one older page, then return to the original
    // anchor if that page includes it. The bound prevents mostly-invisible logs from being folded
    // without limit; a reader may still use the ordinary paging row to travel further back.
    val restoreScope = rememberCoroutineScope()
    var restorePageAttempted by remember(sessionId) { mutableStateOf(false) }
    LaunchedEffect(sessionId, rows, hasMore, loadingOlder, restoredSession, restorePageAttempted, restoreTarget, userDragging) {
        if (sessionId == null || restoredSession != sessionId) return@LaunchedEffect
        val saved = readingPositions.get(sessionId) ?: return@LaunchedEffect
        val target = restoreTarget
        if (target != null) {
            val index = readingPositionIndex(rows, target)
            if (index >= 0) {
                // A user scroll while the request was in flight supersedes the old anchor.
                if (!userDragging && readingPositions.get(sessionId) == target) {
                    listState.scrollToItem(index + if (hasMore) 1 else 0, target.offset)
                    wasNearBottom = false
                }
                restoreTarget = null
            } else if (saved != target) restoreTarget = null
        } else if (shouldFetchReadingAnchorPage(rows, saved, hasMore, loadingOlder, restorePageAttempted)) {
            restorePageAttempted = true
            restoreTarget = saved
            restoreScope.launch { onRestoreOlder(sessionId) }
        }
    }

    // Reaching the top pulls the next page. The guard matters: this effect sits above the `loading`
    // early return, so without it the trigger would fire against an empty list and race the initial
    // history fetch. It also re-arms once a page lands, which is what fills the first screen when a
    // session opens on fewer messages than the viewport holds.
    //
    // Two different things want a page, and only one of them is safe to repeat without limit.
    // Scrolling to the top is the reader asking, and can page as far back as they care to go.
    // Filling a screen that the transcript does not yet cover is the app asking, and is bounded by
    // MAX_AUTO_PAGES — a page that adds thousands of events and no visible rows would otherwise
    // keep the app asking forever.
    var autoPages by rememberSaveable(sessionId) { mutableIntStateOf(0) }
    var userScrolling by remember(sessionId) { mutableStateOf(false) }
    var pullArmed by remember(sessionId) { mutableStateOf(false) }
    val canPage = hasMore && !loading && !loadingOlder && !loadOlderFailed
    val autoPagingExhausted = hasMore && !loading && autoPages >= MAX_AUTO_PAGES
    LaunchedEffect(listState, sessionId) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { userScrolling = it }
    }
    LaunchedEffect(listState, sessionId, canPage, userScrolling) {
        if (!canPage) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            val covered = info.visibleItemsInfo.sumOf { it.size }
            val viewport = info.viewportEndOffset - info.viewportStartOffset
            listState.firstVisibleItemIndex to (viewport > 0 && covered >= viewport)
        }.collect { (firstVisible, fillsViewport) ->
            if (!shouldPageAtTop(firstVisible, fillsViewport, autoPages, MAX_AUTO_PAGES, userScrolling)) return@collect
            if (!fillsViewport) autoPages++
            val anchor = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index > 0 }
            if (anchor != null) olderPageAnchor = OlderPageAnchor(rows.getOrNull(anchor.index - 1)?.anchorSeq ?: return@collect, anchor.offset)
            onLoadOlder()
        }
    }

    if (loading) {
        TranscriptSkeleton(modifier)
        return
    }

    SelectionContainer {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
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
                    offerManual = autoPagingExhausted,
                    onRetry = onLoadOlder,
                )
            }
        }
        if (nodes.isEmpty() && !deepDiving) {
            item(key = "empty") {
                EmptyHero(
                    headline = stringResource(R.string.chat_empty_title),
                    subtitle = stringResource(R.string.chat_empty_hint),
                )
            }
        } else {
            items(rows, key = { it.key }) { row ->
                when (row) {
                    is TranscriptRow.Node -> ChatNodeItem(node = row.node, context = context)
                    is TranscriptRow.Command -> CommandActivityRow(row.activity, context)
                    is TranscriptRow.Workflow -> WorkflowActivityRow(row.activity, context)
                    is TranscriptRow.Process -> {
                        val processKey = DisclosureKeys.process(row.part.startSeq)
                        val open = disclosureScope.isOpen(processKey)
                        Text(
                            text = if (open) stringResource(R.string.chat_process_hide, row.part.nodes.size)
                                else stringResource(R.string.chat_process_summary, row.part.nodes.size),
                            modifier = Modifier.fillMaxWidth().clickable {
                                disclosureScope.toggle(processKey)
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
 * The row is silent during normal automatic paging. Once the reader explicitly reaches the top
 * again, the same pull gesture requests another page without requiring a button tap. It speaks up
 * while fetching, and offers a retry when a page failed.
 *
 * [offerManual] is the third case: automatic paging has spent its budget on a session whose events
 * are mostly not messages, so the list may still be too short to scroll. Without a button there
 * would be nothing left to trigger a page, and the rest of the history would be unreachable.
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
        failed -> DsButton(
            text = stringResource(R.string.chat_load_older_retry),
            onClick = onRetry,
            variant = DsButtonVariant.Ghost,
            size = DsButtonSize.Small,
            modifier = Modifier.fillMaxWidth(),
        )

        offerManual && !loading -> DsButton(
            text = stringResource(R.string.chat_load_older),
            onClick = onRetry,
            variant = DsButtonVariant.Ghost,
            size = DsButtonSize.Small,
            modifier = Modifier.fillMaxWidth(),
        )

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
