package dev.dsh.mobile.mesh.ui.screens.main

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
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
    LaunchedEffect(turnStartedAtMillis) {
        while (true) {
            elapsedSeconds = elapsedTurnSeconds(turnStartedAtMillis, System.currentTimeMillis())
            kotlinx.coroutines.delay(1000L)
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
    onLoadOlder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Only the nodes that draw something: a zero-height item still costs its 4dp gap, and a turn's
    // worth of structural events stacks those gaps into a blank band under the chrome.
    val nodes = remember(conversation?.nodes) {
        conversation?.nodes.orEmpty().filter { it.rendersContent() }
    }
    val hasMore = conversation?.hasMore == true
    val itemCount = nodes.size + if (hasMore) 1 else 0
    val sessionId = conversation?.sessionId
    val turnStartedAtMillis = conversation?.turnStartedAtMillis
    val deepDiving = deepDivingVisible(conversation?.running == true, turnStartedAtMillis)
    val waitingForFirstResponse = waitingForFirstResponse(
        running = conversation?.running == true,
        hasAssistant = nodes.any { it is AssistantMessageNode },
    )

    // Both keyed on the session so a freshly opened one starts from a clean assumption rather than
    // inheriting the previous transcript's position — and so the collector always writes to the
    // state the composition is currently reading.
    var wasNearBottom by remember(sessionId) { mutableStateOf(true) }
    var olderPageAnchor by remember(sessionId) { mutableStateOf<OlderPageAnchor?>(null) }
    LaunchedEffect(listState, sessionId, itemCount) {
        var previousViewportHeight: Int? = null
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = info.totalItemsCount
            val viewportHeight = info.viewportEndOffset - info.viewportStartOffset
            Triple(total, last, viewportHeight)
        }.collect { (total, last, viewportHeight) ->
            val viewportChanged = previousViewportHeight != null && previousViewportHeight != viewportHeight
            // The IME reduces the transcript viewport while the composer moves upward. Preserve the
            // old tail anchor and scroll to the new bottom in the same frame, instead of leaving the
            // user one viewport-height short of the latest message.
            if (viewportChanged && wasNearBottom && total > 0) {
                listState.scrollToItem(total - 1)
            }
            wasNearBottom = total == 0 || last >= total - 2 || (viewportChanged && wasNearBottom)
            previousViewportHeight = viewportHeight
        }
    }

    // Keyed on the *newest* seq, not the item count, so only growth at the tail moves the view.
    // Counting items conflated two opposite events: a turn streaming in at the bottom, which should
    // follow, and a page of history arriving at the top, which must not — asking for older messages
    // and being thrown back to the newest one is the opposite of what the tap meant. The paging row
    // appearing and disappearing changed the count too, which moved the view for no reason at all.
    val newestSeq = nodes.lastOrNull()?.seq
    var lastSession by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(nodes, hasMore, sessionId) {
        val anchor = olderPageAnchor ?: return@LaunchedEffect
        if (nodes.none { it.seq == anchor.seq }) return@LaunchedEffect
        val newIndex = nodes.indexOfFirst { it.seq == anchor.seq }
        if (newIndex >= 0) listState.scrollToItem(newIndex + if (hasMore) 1 else 0, anchor.offset)
        olderPageAnchor = null
    }

    LaunchedEffect(newestSeq, sessionId) {
        if (itemCount == 0) return@LaunchedEffect
        val switched = sessionId != lastSession
        lastSession = sessionId
        // Opening a session should land on its tail, not animate the whole list to get there.
        if (switched) listState.scrollToItem(itemCount - 1)
        else if (wasNearBottom) listState.animateScrollToItem(itemCount - 1)
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
            if (anchor != null) olderPageAnchor = OlderPageAnchor(nodes.getOrNull(anchor.index - 1)?.seq ?: return@collect, anchor.offset)
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
                    detectTapGestures { }
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
            items(nodes, key = { it.seq }) { node ->
                // Disclosure expansion changes only this item's height. Avoid animateItem here:
                // animating every sibling during a height change causes the whole transcript to
                // briefly disappear on Android, especially for the todo dock.
                ChatNodeItem(node = node, context = context)
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
