package dev.dsh.mobile.mesh.ui.screens.main

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.mesh.core.session.ChatBlock
import dev.dsh.mobile.mesh.core.session.ConversationSnapshot
import dev.dsh.mobile.mesh.core.session.UserMessageNode
import dev.dsh.mobile.mesh.data.TranscriptPresentation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises older-page loading through the same pull gesture a reader uses in the transcript. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ChatTranscriptPagingTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `repeated pulls do not cancel the page already loading`() {
        val current = (21L..80L).map { UserMessageNode(it, null, listOf(ChatBlock("text", "Message $it")), null) }
        val listState = LazyListState()
        val disclosures = DisclosureScope(TranscriptDisclosures(), "duplicate-page")
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        var completed = false
        compose.setContent {
            Box(Modifier.height(520.dp)) {
                ChatTranscript(conversation = ConversationSnapshot("duplicate-page", nodes = current,
                    blank = false, hasMore = true, oldestEventSeq = 21),
                    loading = false, loadingOlder = false, loadOlderFailed = false,
                    context = ChatNodeContext(nodes = current, sessionId = "duplicate-page", disclosures = disclosures,
                        running = false, cwd = null, onOpenSubagent = {}, onBranchFrom = {}, onFeedback = { _, _ -> }),
                    presentation = TranscriptPresentation(), listState = listState,
                    readingPositions = TranscriptReadingPositions(), onLoadOlder = {
                        calls++
                        started.complete(Unit)
                        release.await()
                        completed = true
                        true
                    }, onRestoreOlder = {})
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { CoroutineScope(Dispatchers.Main.immediate).launch { listState.scrollToItem(0) } }
        compose.waitForIdle()
        try {
            compose.onRoot().performTouchInput { swipeDown() }
            compose.waitUntil(5_000) { started.isCompleted }
            compose.onRoot().performTouchInput { swipeDown() }
            compose.waitForIdle()
            compose.runOnIdle { assertEquals("Repeated pulls must share the pending request", 1, calls) }
            release.complete(Unit)
            compose.waitForIdle()
            compose.runOnIdle { assertEquals("The original request must finish", true, completed) }
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun `retry after a failed page preserves the reading position`() {
        val current = (21L..80L).map { UserMessageNode(it, null, listOf(ChatBlock("text", "Message $it")), null) }
        val older = (1L..20L).map { UserMessageNode(it, null, listOf(ChatBlock("text", "Message $it")), null) }
        val snapshot = mutableStateOf(ConversationSnapshot("retry-page", nodes = current, blank = false,
            hasMore = true, oldestEventSeq = 21))
        val failed = mutableStateOf(false)
        val releaseRetry = CompletableDeferred<Unit>()
        val retryStarted = CompletableDeferred<Unit>()
        val listState = LazyListState()
        val disclosures = DisclosureScope(TranscriptDisclosures(), "retry-page")
        var calls = 0
        compose.setContent {
            Box(Modifier.height(520.dp)) {
                ChatTranscript(conversation = snapshot.value, loading = false, loadingOlder = false,
                    loadOlderFailed = failed.value,
                    context = ChatNodeContext(nodes = snapshot.value.nodes, sessionId = "retry-page",
                        disclosures = disclosures, running = false, cwd = null, onOpenSubagent = {},
                        onBranchFrom = {}, onFeedback = { _, _ -> }),
                    presentation = TranscriptPresentation(), listState = listState,
                    readingPositions = TranscriptReadingPositions(),
                    onLoadOlder = {
                        calls++
                        if (calls == 1) {
                            failed.value = true
                            false
                        } else {
                            retryStarted.complete(Unit)
                            releaseRetry.await()
                            failed.value = false
                            true
                        }
                    }, onRestoreOlder = {})
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { CoroutineScope(Dispatchers.Main.immediate).launch { listState.scrollToItem(0) } }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { swipeDown() }
        compose.waitForIdle()
        compose.onNodeWithText("Couldn't load earlier messages — tap to retry").performClick()
        try {
            compose.waitUntil(5_000) { retryStarted.isCompleted }
            compose.runOnIdle { releaseRetry.complete(Unit) }
            compose.waitForIdle()
            val offset = compose.runOnIdle { listState.layoutInfo.visibleItemsInfo.first { it.key == "node-21" }.offset }
            compose.runOnIdle { snapshot.value = snapshot.value.copy(nodes = older + current, oldestEventSeq = 1) }
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals("Retry must retain the reader's place when older messages arrive", offset,
                    listState.layoutInfo.visibleItemsInfo.first { it.key == "node-21" }.offset)
            }
        } finally {
            releaseRetry.complete(Unit)
        }
    }

    @Test
    fun `a page request returning without a response cannot move a later snapshot`() {
        val current = (21L..80L).map { UserMessageNode(it, null, listOf(ChatBlock("text", "Message $it")), null) }
        val older = (1L..20L).map { UserMessageNode(it, null, listOf(ChatBlock("text", "Message $it")), null) }
        val snapshot = mutableStateOf(ConversationSnapshot("no-page", nodes = current, blank = false,
            hasMore = true, oldestEventSeq = 21))
        val listState = LazyListState()
        val disclosures = DisclosureScope(TranscriptDisclosures(), "no-page")
        var calls = 0
        compose.setContent {
            Box(Modifier.height(520.dp)) {
                ChatTranscript(conversation = snapshot.value, loading = false, loadingOlder = false,
                    loadOlderFailed = false,
                    context = ChatNodeContext(nodes = snapshot.value.nodes, sessionId = "no-page",
                        disclosures = disclosures, running = false, cwd = null, onOpenSubagent = {},
                        onBranchFrom = {}, onFeedback = { _, _ -> }),
                    presentation = TranscriptPresentation(), listState = listState,
                    readingPositions = TranscriptReadingPositions(), onLoadOlder = { calls++; false },
                    onRestoreOlder = {})
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { CoroutineScope(Dispatchers.Main.immediate).launch { listState.scrollToItem(0) } }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertEquals(1, calls)
        compose.runOnIdle { CoroutineScope(Dispatchers.Main.immediate).launch { listState.scrollToItem(15) } }
        compose.waitForIdle()
        val key = compose.runOnIdle { listState.layoutInfo.visibleItemsInfo.first().key }
        compose.runOnIdle { snapshot.value = snapshot.value.copy(nodes = older + current, oldestEventSeq = 1) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("An abandoned page must not override the reader's later position", key,
                listState.layoutInfo.visibleItemsInfo.first().key)
        }
    }

    @Test
    fun `failed older request cannot move the reader during a later snapshot`() =
        completedPageDoesNotLeaveAnAnchor(failed = true)

    @Test
    fun `empty older request cannot move the reader during a later snapshot`() =
        completedPageDoesNotLeaveAnAnchor(failed = false)

    @Test
    fun `synchronously completed empty page cannot move the reader during a later snapshot`() =
        completedPageDoesNotLeaveAnAnchor(failed = false, synchronous = true)

    private fun completedPageDoesNotLeaveAnAnchor(failed: Boolean, synchronous: Boolean = false) {
        val current = (21L..80L).map { seq ->
            UserMessageNode(seq, null, listOf(ChatBlock("text", "Message $seq")), null)
        }
        val older = (1L..20L).map { seq ->
            UserMessageNode(seq, null, listOf(ChatBlock("text", "Message $seq")), null)
        }
        val snapshot = mutableStateOf(ConversationSnapshot("page-completion", nodes = current,
            blank = false, hasMore = true, oldestEventSeq = 21))
        val loadingOlder = mutableStateOf(false)
        val loadFailed = mutableStateOf(false)
        val completion = CompletableDeferred<Boolean>()
        var requests = 0
        val listState = LazyListState()
        val positions = TranscriptReadingPositions()
        val disclosures = DisclosureScope(TranscriptDisclosures(), "page-completion")
        compose.setContent {
            Box(Modifier.height(520.dp)) {
                ChatTranscript(conversation = snapshot.value, loading = false, loadingOlder = loadingOlder.value,
                    loadOlderFailed = loadFailed.value,
                    context = ChatNodeContext(nodes = snapshot.value.nodes, sessionId = "page-completion",
                        disclosures = disclosures, running = false, cwd = null, onOpenSubagent = {},
                        onBranchFrom = {}, onFeedback = { _, _ -> }),
                    presentation = TranscriptPresentation(), listState = listState, readingPositions = positions,
                    onLoadOlder = {
                        requests++
                        loadingOlder.value = true
                        if (synchronous) {
                            snapshot.value = snapshot.value.copy(hasMore = false)
                            loadingOlder.value = false
                            true
                        } else completion.await()
                    }, onRestoreOlder = {})
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { CoroutineScope(Dispatchers.Main.immediate).launch { listState.scrollToItem(0) } }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertEquals(1, requests)
        compose.runOnIdle {
            loadFailed.value = failed
            if (!failed) snapshot.value = snapshot.value.copy(hasMore = false)
            loadingOlder.value = false
            completion.complete(!failed)
        }
        compose.waitForIdle()
        compose.runOnIdle { CoroutineScope(Dispatchers.Main.immediate).launch { listState.scrollToItem(15) } }
        compose.waitForIdle()
        val anchor = compose.runOnIdle { listState.layoutInfo.visibleItemsInfo.first().key }
        compose.runOnIdle { snapshot.value = snapshot.value.copy(nodes = older + current, oldestEventSeq = 1) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("A completed request must not pull the reader back to its old position",
                anchor, listState.layoutInfo.visibleItemsInfo.first().key)
        }
    }

    @Test
    fun `delayed page snapshot keeps the reading anchor after the loading indicator ends`() {
        val current = (21L..80L).map { UserMessageNode(it, null, listOf(ChatBlock("text", "Message $it")), null) }
        val older = (1L..20L).map { UserMessageNode(it, null, listOf(ChatBlock("text", "Message $it")), null) }
        val snapshot = mutableStateOf(ConversationSnapshot("delayed-page", nodes = current,
            blank = false, hasMore = true, oldestEventSeq = 21))
        val loadingOlder = mutableStateOf(false)
        val listState = LazyListState()
        val positions = TranscriptReadingPositions()
        val disclosures = DisclosureScope(TranscriptDisclosures(), "delayed-page")
        compose.setContent {
            Box(Modifier.height(520.dp)) {
                ChatTranscript(conversation = snapshot.value, loading = false, loadingOlder = loadingOlder.value,
                    loadOlderFailed = false,
                    context = ChatNodeContext(nodes = snapshot.value.nodes, sessionId = "delayed-page",
                        disclosures = disclosures, running = false, cwd = null, onOpenSubagent = {},
                        onBranchFrom = {}, onFeedback = { _, _ -> }),
                    presentation = TranscriptPresentation(), listState = listState, readingPositions = positions,
                    onLoadOlder = { loadingOlder.value = true; true }, onRestoreOlder = {})
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { CoroutineScope(Dispatchers.Main.immediate).launch { listState.scrollToItem(0) } }
        compose.waitForIdle()
        val offset = compose.runOnIdle { listState.layoutInfo.visibleItemsInfo.first { it.key == "node-21" }.offset }
        compose.onRoot().performTouchInput { swipeDown() }
        compose.waitForIdle()
        compose.runOnIdle { loadingOlder.value = false }
        compose.waitForIdle()
        compose.runOnIdle { snapshot.value = snapshot.value.copy(nodes = older + current, oldestEventSeq = 1) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("Page publication after loading must preserve the reading position", offset,
                listState.layoutInfo.visibleItemsInfo.first { it.key == "node-21" }.offset)
        }
    }

    @Test
    fun `pulling at history head loads older rows without moving the visible message`() {
        val visiblePage = (21L..80L).map { seq ->
            UserMessageNode(seq, null, listOf(ChatBlock("text", "Message $seq")), null)
        }
        val olderPage = (1L..20L).map { seq ->
            UserMessageNode(seq, null, listOf(ChatBlock("text", "Message $seq")), null)
        }
        val snapshot = mutableStateOf(ConversationSnapshot(
            "paging-session", nodes = visiblePage, blank = false, hasMore = true, oldestEventSeq = 21,
        ))
        var requests = 0
        val listState = LazyListState()
        val disclosures = DisclosureScope(TranscriptDisclosures(), "paging-session")
        compose.setContent {
            val current = snapshot.value
            Box(Modifier.height(520.dp)) {
                ChatTranscript(
                    conversation = current,
                    loading = false,
                    loadingOlder = false,
                    loadOlderFailed = false,
                    context = ChatNodeContext(
                        nodes = current.nodes, sessionId = "paging-session", disclosures = disclosures,
                        running = false, cwd = null, onOpenSubagent = {}, onBranchFrom = {}, onFeedback = { _, _ -> },
                    ),
                    presentation = TranscriptPresentation(),
                    listState = listState,
                    readingPositions = TranscriptReadingPositions(),
                    onLoadOlder = {
                        requests++
                        snapshot.value = snapshot.value.copy(nodes = olderPage + visiblePage, oldestEventSeq = 1)
                        true
                    },
                    onRestoreOlder = {},
                )
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            CoroutineScope(Dispatchers.Main.immediate).launch { listState.scrollToItem(0) }
        }
        compose.waitForIdle()
        val before = compose.runOnIdle {
            val item = listState.layoutInfo.visibleItemsInfo.first { it.key == "node-21" }
            item.key to item.offset
        }

        compose.onRoot().performTouchInput { swipeDown() }
        compose.waitForIdle()

        compose.runOnIdle {
            assertEquals("One deliberate pull should request one older page", 1, requests)
            val after = listState.layoutInfo.visibleItemsInfo.first { it.key == before.first }
            assertEquals("Prepending history must preserve the reader's visible message position", before.second, after.offset)
        }
    }
}
