package dev.dsh.mobile.mesh.ui.screens.main

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import dev.dsh.mobile.mesh.ui.theme.DshTheme
import dev.dsh.mobile.mesh.core.session.AssistantMessageNode
import dev.dsh.mobile.mesh.core.session.EventFold
import dev.dsh.mobile.mesh.core.session.SessionEventEnvelope
import dev.dsh.mobile.mesh.core.session.ChatBlock
import dev.dsh.mobile.mesh.core.session.ContextMessageNode
import dev.dsh.mobile.mesh.core.session.ConversationSnapshot
import dev.dsh.mobile.mesh.core.session.UserMessageNode
import dev.dsh.mobile.mesh.data.TranscriptPresentation
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ChatTranscriptScrollTest {
    @get:Rule val compose = createComposeRule()

    private fun markdownVisible(fragment: String): Boolean =
        compose.onAllNodesWithText(fragment, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `reading history stays on the same message when the newest message and viewport change`() {
        val messages = (1L..80L).map { seq ->
            UserMessageNode(seq, null, listOf(ChatBlock("text", "Message $seq with enough content to scroll")), null)
        }
        val snapshot = mutableStateOf(ConversationSnapshot("scroll-session", nodes = messages, blank = false))
        val height = mutableStateOf(520)
        val listState = LazyListState()
        val positions = TranscriptReadingPositions()
        val disclosures = DisclosureScope(TranscriptDisclosures(), "scroll-session")
        compose.setContent {
            Box(Modifier.height(height.value.dp)) {
                ChatTranscript(
                    conversation = snapshot.value,
                    loading = false,
                    loadingOlder = false,
                    loadOlderFailed = false,
                    context = ChatNodeContext(
                        nodes = snapshot.value.nodes,
                        sessionId = "scroll-session",
                        disclosures = disclosures,
                        running = false,
                        cwd = null,
                        onOpenSubagent = {},
                        onBranchFrom = {},
                        onFeedback = { _, _ -> },
                    ),
                    presentation = TranscriptPresentation(),
                    listState = listState,
                    readingPositions = positions,
                    onLoadOlder = { false },
                    onRestoreOlder = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { swipeDown() }
        compose.waitForIdle()
        val anchor = compose.runOnIdle {
            assertTrue("A reader gesture must move away from the latest row", listState.canScrollForward)
            listState.layoutInfo.visibleItemsInfo.first().key to listState.firstVisibleItemScrollOffset
        }

        snapshot.value = snapshot.value.copy(nodes = messages.dropLast(1) +
            messages.last().copy(blocks = listOf(ChatBlock("text", "Message 80 updated"))))
        height.value = 460
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(anchor.first, listState.layoutInfo.visibleItemsInfo.first().key)
            assertEquals(anchor.second, listState.firstVisibleItemScrollOffset)
            assertTrue(listState.canScrollForward)
        }

        compose.runOnIdle {
            CoroutineScope(Dispatchers.Main.immediate).launch { listState.scrollToItem(75) }
        }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue("The gesture should reach the transcript tail", !listState.canScrollForward) }
        snapshot.value = snapshot.value.copy(nodes = snapshot.value.nodes +
            UserMessageNode(81, null, listOf(ChatBlock("text", "Message 81")), null))
        compose.waitForIdle()
        compose.runOnIdle { assertTrue("New messages follow after scrolling back to the tail", !listState.canScrollForward) }
    }

    @Test
    fun `a final message arriving during a scroll is followed when scrolling settles`() {
        val messages = (1L..40L).map { seq ->
            UserMessageNode(seq, null, listOf(ChatBlock("text", "Message $seq")), null)
        }
        val snapshot = mutableStateOf(ConversationSnapshot("scroll-update", nodes = messages, blank = false))
        val listState = LazyListState()
        val positions = TranscriptReadingPositions()
        val disclosures = DisclosureScope(TranscriptDisclosures(), "scroll-update")
        val scrolling = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        compose.setContent {
            Box(Modifier.height(520.dp)) {
                ChatTranscript(conversation = snapshot.value, loading = false, loadingOlder = false,
                    loadOlderFailed = false,
                    context = ChatNodeContext(nodes = snapshot.value.nodes, sessionId = "scroll-update",
                        disclosures = disclosures, running = false, cwd = null, onOpenSubagent = {},
                        onBranchFrom = {}, onFeedback = { _, _ -> }),
                    presentation = TranscriptPresentation(), listState = listState,
                    readingPositions = positions, onLoadOlder = { false }, onRestoreOlder = {})
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("Reader starts at the tail", !listState.canScrollForward)
            CoroutineScope(Dispatchers.Main.immediate).launch {
                listState.scroll {
                    scrolling.complete(Unit)
                    release.await()
                }
            }
        }
        try {
            compose.waitUntil(5_000) { scrolling.isCompleted }
            compose.runOnIdle {
                snapshot.value = snapshot.value.copy(nodes = messages +
                    UserMessageNode(41, null, listOf(ChatBlock("text", "Final message")), null))
            }
            compose.waitForIdle()
        } finally {
            release.complete(Unit)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("The last update must be visible when scrolling ends", !listState.canScrollForward)
            assertTrue(listState.layoutInfo.visibleItemsInfo.any { it.key == "node-41" })
        }
    }

    @Test
    fun `interrupting a pending history restore still allows following new messages after returning to tail`() {
        val messages = (50L..80L).map { seq ->
            UserMessageNode(seq, null, listOf(ChatBlock("text", "Message $seq")), null)
        }
        val snapshot = mutableStateOf(ConversationSnapshot("interrupted-restore", nodes = messages,
            blank = false, hasMore = true, oldestEventSeq = 50))
        val listState = LazyListState()
        val positions = TranscriptReadingPositions().apply {
            put("interrupted-restore", TranscriptReadingPosition(seq = 1, offset = 0))
        }
        val disclosures = DisclosureScope(TranscriptDisclosures(), "interrupted-restore")
        compose.setContent {
            Box(Modifier.height(520.dp)) {
                ChatTranscript(conversation = snapshot.value, loading = false, loadingOlder = false,
                    loadOlderFailed = false,
                    context = ChatNodeContext(nodes = snapshot.value.nodes, sessionId = "interrupted-restore",
                        disclosures = disclosures, running = false, cwd = null, onOpenSubagent = {},
                        onBranchFrom = {}, onFeedback = { _, _ -> }),
                    presentation = TranscriptPresentation(), listState = listState,
                    readingPositions = positions, onLoadOlder = { false }, onRestoreOlder = {})
            }
        }
        compose.waitForIdle()
        var gestures = 0
        while (compose.runOnIdle { listState.canScrollForward } && gestures++ < 20) {
            compose.onRoot().performTouchInput { swipeUp() }
            compose.waitForIdle()
        }
        compose.runOnIdle { assertTrue("Reader must reach the tail", !listState.canScrollForward) }
        snapshot.value = snapshot.value.copy(nodes = messages +
            UserMessageNode(81, null, listOf(ChatBlock("text", "New message")), null))
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("A new message must remain visible after the reader resumes following", !listState.canScrollForward)
            assertTrue(listState.layoutInfo.visibleItemsInfo.any { it.key == "node-81" })
        }
    }

    @Test
    fun `scrolling backward enters the end of a long user message and continues without jumping to its start`() {
        // Blank lines keep the input as separate paragraphs: Robolectric measures each text block
        // as one line, and 650 paragraphs still reproduce a message taller than the viewport.
        val longInput = (1..650).joinToString("\n\n") { "User input line $it followed by the original detailed instructions." }
        val messages = listOf(UserMessageNode(1, null, listOf(ChatBlock("text", longInput)), null)) +
            (2L..30L).map { UserMessageNode(it, null, listOf(ChatBlock("text", "Later message $it")), null) }
        val listState = LazyListState()
        val positions = TranscriptReadingPositions()
        val disclosures = DisclosureScope(TranscriptDisclosures(), "long-user-session")
        compose.setContent {
            Box(Modifier.height(520.dp)) {
                ChatTranscript(
                    conversation = ConversationSnapshot("long-user-session", nodes = messages, blank = false),
                    loading = false, loadingOlder = false, loadOlderFailed = false,
                    context = ChatNodeContext(
                        nodes = messages, sessionId = "long-user-session", disclosures = disclosures,
                        running = false, cwd = null, onOpenSubagent = {}, onBranchFrom = {},
                        onFeedback = { _, _ -> },
                    ),
                    presentation = TranscriptPresentation(), listState = listState,
                    readingPositions = positions, onLoadOlder = { false }, onRestoreOlder = {},
                )
            }
        }
        compose.waitForIdle()
        // Approach from newer messages with real drags, as a reader scrolling back through history.
        var drags = 0
        while (compose.runOnIdle { listState.firstVisibleItemIndex > 0 } && drags++ < 40) {
            compose.onRoot().performTouchInput {
                swipe(start = center.copy(y = height * 0.35f), end = center.copy(y = height * 0.65f), durationMillis = 500)
            }
            compose.waitForIdle()
        }
        // The long message may enter the viewport at its start; keep scrolling back until the
        // reader is actually inside its tail, which is what approaching from newer messages means.
        var extraDrags = 0
        while (compose.runOnIdle {
                listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset <= listState.layoutInfo.viewportEndOffset
            } && extraDrags++ < 20
        ) {
            compose.onRoot().performTouchInput {
                swipe(start = center.copy(y = height * 0.35f), end = center.copy(y = height * 0.65f), durationMillis = 500)
            }
            compose.waitForIdle()
        }
        val before = compose.runOnIdle {
            assertEquals("node-1", listState.layoutInfo.visibleItemsInfo.first().key)
            assertTrue(
                "Entering from below must show the end, not the beginning of the long user input " +
                    "(offset=${listState.firstVisibleItemScrollOffset}, " +
                    "viewportEnd=${listState.layoutInfo.viewportEndOffset}, " +
                    "itemSize=${listState.layoutInfo.visibleItemsInfo.first().size}, " +
                    "items=${listState.layoutInfo.totalItemsCount})",
                listState.firstVisibleItemScrollOffset > listState.layoutInfo.viewportEndOffset,
            )
            listState.firstVisibleItemScrollOffset to listState.layoutInfo.visibleItemsInfo.first().size
        }
        compose.onRoot().performTouchInput {
            swipe(
                start = center.copy(x = width * 0.8f, y = height * 0.35f),
                end = center.copy(x = width * 0.8f, y = height * 0.65f),
                durationMillis = 1_200,
            )
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val moved = before.first - listState.firstVisibleItemScrollOffset
            assertEquals("node-1", listState.layoutInfo.visibleItemsInfo.first().key)
            assertTrue("A short drag must move naturally within the long message", moved in 1..300)
            assertEquals(before.second, listState.layoutInfo.visibleItemsInfo.first().size)
        }
    }

    @Test
    fun `returning to a long markdown message preserves the offset while it re-renders`() {
        val table = "| Column | Value |\n| --- | --- |\n" + (1..100).joinToString("\n") { "| Row $it | Content |" }
        val messages = listOf(ContextMessageNode(42, null, listOf(ChatBlock("text", table)), null))
        val visible = mutableStateOf(true)
        val listState = LazyListState()
        val positions = TranscriptReadingPositions()
        val disclosures = DisclosureScope(TranscriptDisclosures(), "markdown-session").apply {
            setOpen(DisclosureKeys.contextInjection(42), true)
        }
        compose.setContent {
            if (visible.value) Box(Modifier.height(520.dp)) {
                ChatTranscript(
                    conversation = ConversationSnapshot("markdown-session", nodes = messages, blank = false),
                    loading = false,
                    loadingOlder = false,
                    loadOlderFailed = false,
                    context = ChatNodeContext(
                        nodes = messages, sessionId = "markdown-session", disclosures = disclosures,
                        running = false, cwd = null, onOpenSubagent = {}, onBranchFrom = {},
                        onFeedback = { _, _ -> },
                    ),
                    presentation = TranscriptPresentation(),
                    listState = listState,
                    readingPositions = positions,
                    onLoadOlder = { false }, onRestoreOlder = {},
                )
            }
        }
        compose.waitUntil(timeoutMillis = 10_000) { markdownVisible("Row 100") }
        // A reader leaves the tail deliberately before the exact coordinate is captured.
        compose.onRoot().performTouchInput { swipeDown() }
        compose.waitForIdle()
        compose.runOnIdle { CoroutineScope(Dispatchers.Main.immediate).launch { listState.scrollToItem(0, 600) } }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(600, listState.firstVisibleItemScrollOffset) }
        visible.value = false
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(600, positions.get("markdown-session")?.offset) }
        visible.value = true
        compose.waitUntil(timeoutMillis = 10_000) { markdownVisible("Row 100") }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(600, listState.firstVisibleItemScrollOffset) }
    }

    @Test
    fun `a restored tail stays at the end when the answer finishes parsing`() {
        val text = mutableStateOf("Preparing the answer")
        val shown = mutableStateOf(true)
        val listState = LazyListState()
        val positions = TranscriptReadingPositions()
        val disclosures = DisclosureScope(TranscriptDisclosures(), "tail-session").apply {
            setOpen(DisclosureKeys.contextInjection(62), true)
        }
        compose.setContent {
            val nodes = listOf(ContextMessageNode(62, null, listOf(ChatBlock("text", text.value)), null))
            if (shown.value) Box(Modifier.height(520.dp)) {
                ChatTranscript(
                    conversation = ConversationSnapshot("tail-session", nodes = nodes, blank = false),
                    loading = false, loadingOlder = false, loadOlderFailed = false,
                    context = ChatNodeContext(nodes = nodes, sessionId = "tail-session", disclosures = disclosures,
                        running = false, cwd = null, onOpenSubagent = {}, onBranchFrom = {}, onFeedback = { _, _ -> }),
                    presentation = TranscriptPresentation(), listState = listState, readingPositions = positions,
                    onLoadOlder = { false }, onRestoreOlder = {},
                )
            }
        }
        compose.waitUntil(timeoutMillis = 10_000) { markdownVisible("Preparing the answer") }
        repeat(2) {
            compose.onRoot().performTouchInput { swipeUp() }
            compose.waitForIdle()
        }
        compose.runOnIdle { assertTrue("Must leave from the actual tail", !listState.canScrollForward) }
        shown.value = false
        compose.waitForIdle()
        compose.runOnIdle { assertTrue("Tail must be saved as tail", positions.get("tail-session")?.atBottom == true) }

        shown.value = true
        compose.waitUntil(timeoutMillis = 10_000) { markdownVisible("Preparing the answer") }
        compose.waitForIdle()
        assertTrue("The restored transcript starts at the end", !listState.canScrollForward)

        // The answer grows from a short placeholder into a tall table after the viewport was
        // restored at the tail; no reader gesture may be needed to keep the end visible.
        val table = "| Item | Content |\n| --- | --- |\n" +
            (1..100).joinToString("\n") { "| Tail row $it | Text |" }
        compose.runOnIdle { text.value = table }
        compose.waitUntil(timeoutMillis = 10_000) { markdownVisible("Tail row 100") }
        compose.waitUntil(timeoutMillis = 10_000) { !listState.canScrollForward }
        compose.runOnIdle {
            assertTrue(
                "The restored tail must settle at the end once the answer finishes parsing",
                !listState.canScrollForward,
            )
        }
    }

    @Test
    fun `returning from the session list restores the message being read`() {
        val messages = (1L..80L).map { seq ->
            UserMessageNode(seq, null, listOf(ChatBlock("text", "Message $seq")), null)
        }
        val visible = mutableStateOf(true)
        val opening = mutableStateOf(false)
        val snapshot = mutableStateOf(ConversationSnapshot("return-session", nodes = messages, blank = false))
        val listState = LazyListState()
        val positions = TranscriptReadingPositions()
        val disclosures = DisclosureScope(TranscriptDisclosures(), "return-session")
        compose.setContent {
            if (visible.value) Box(Modifier.height(520.dp)) {
                ChatTranscript(
                    conversation = snapshot.value,
                    loading = opening.value,
                    loadingOlder = false,
                    loadOlderFailed = false,
                    context = ChatNodeContext(
                        nodes = snapshot.value.nodes,
                        sessionId = "return-session",
                        disclosures = disclosures,
                        running = false,
                        cwd = null,
                        onOpenSubagent = {},
                        onBranchFrom = {},
                        onFeedback = { _, _ -> },
                    ),
                    presentation = TranscriptPresentation(),
                    listState = listState,
                    readingPositions = positions,
                    onLoadOlder = { false },
                    onRestoreOlder = {
                        snapshot.value = snapshot.value.copy(
                            nodes = messages, hasMore = false, oldestEventSeq = 1,
                        )
                    },
                )
            }
        }
        compose.waitForIdle()
        repeat(4) {
            compose.onRoot().performTouchInput { swipeDown() }
            compose.waitForIdle()
        }
        val anchor = compose.runOnIdle {
            assertTrue(listState.canScrollForward)
            listState.layoutInfo.visibleItemsInfo.first().key to listState.firstVisibleItemScrollOffset
        }
        assertTrue("The reading row must be outside the opening follow page", anchor.first.toString().substringAfterLast('-').toLong() < 61)
        visible.value = false
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(positions.get("return-session")?.atBottom == false) }
        opening.value = true
        visible.value = true
        compose.waitForIdle()
        opening.value = false
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(anchor.first, listState.layoutInfo.visibleItemsInfo.first().key)
            assertEquals(anchor.second, listState.firstVisibleItemScrollOffset)
        }
        // Re-entry first renders the cached deep page; the follow opening then replaces it
        // with its short newest window. The old reading anchor must survive that replacement.
        snapshot.value = snapshot.value.copy(
            nodes = messages.takeLast(20), hasMore = true, oldestEventSeq = 61,
        )
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(anchor.first, listState.layoutInfo.visibleItemsInfo.first().key)
            assertEquals(anchor.second, listState.firstVisibleItemScrollOffset)
        }
    }

    @Test
    fun `loading an older page preserves the visible message and its offset`() {
        val current = (40L..80L).map { seq ->
            UserMessageNode(seq, null, listOf(ChatBlock("text", "Message $seq")), null)
        }
        val older = (1L..39L).map { seq ->
            UserMessageNode(seq, null, listOf(ChatBlock("text", "Message $seq")), null)
        }
        val snapshot = mutableStateOf(ConversationSnapshot(
            "paging-session", nodes = current, blank = false, hasMore = true, oldestEventSeq = 40,
        ))
        val listState = LazyListState()
        val disclosures = DisclosureScope(TranscriptDisclosures(), "paging-session")
        compose.setContent {
            Box(Modifier.height(520.dp)) {
                ChatTranscript(
                    conversation = snapshot.value,
                    loading = false,
                    loadingOlder = false,
                    loadOlderFailed = false,
                    context = ChatNodeContext(
                        nodes = snapshot.value.nodes,
                        sessionId = "paging-session",
                        disclosures = disclosures,
                        running = false,
                        cwd = null,
                        onOpenSubagent = {},
                        onBranchFrom = {},
                        onFeedback = { _, _ -> },
                    ),
                    presentation = TranscriptPresentation(),
                    listState = listState,
                    readingPositions = TranscriptReadingPositions(),
                    onLoadOlder = {
                        snapshot.value = snapshot.value.copy(
                            nodes = older + current, hasMore = false, oldestEventSeq = 1,
                        )
                        true
                    },
                    onRestoreOlder = {},
                )
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { CoroutineScope(Dispatchers.Main.immediate).launch { listState.scrollToItem(0) } }
        compose.waitForIdle()
        val before = compose.runOnIdle {
            listState.layoutInfo.visibleItemsInfo.first { it.key == "node-40" }.offset
        }
        compose.onNodeWithText("Load older messages").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            val after = listState.layoutInfo.visibleItemsInfo.first { it.key == "node-40" }
            assertEquals(before, after.offset)
        }
    }

    @Test
    fun `PTC question answers replace handled rows while failures and other tools remain visible`() {
        fun event(seq: Long, type: String, data: String) = SessionEventEnvelope(
            type = type,
            seq = seq,
            time = seq,
            data = Json.parseToJsonElement(data),
        )
        fun conversation(
            name: String = "ask_user_question",
            isError: Boolean = false,
            includeStart: Boolean = true,
        ): ConversationSnapshot {
            val arguments = if (name == "ask_user_question") {
                """{"questions":[{"id":"scope","question":"Which scope?","header":"Scope"}]}"""
            } else {
                """{"description":"Run script"}"""
            }
            val startPayload = """{"subCallId":"ask-1","name":"$name","arguments":$arguments}"""
            val dispatch = if (isError) {
                """{"subCallId":"ask-1","name":"$name","arguments":$arguments,"isError":true,"content":[{"type":"text","text":"failed"}]}"""
            } else if (name == "ask_user_question") {
                """{"subCallId":"ask-1","name":"$name","arguments":$arguments,"isError":false,"content":[{"type":"text","text":"{\"answers\":[{\"id\":\"scope\",\"selected\":[\"Current session\"]}]}"}]}"""
            } else {
                """{"subCallId":"ask-1","name":"$name","arguments":$arguments,"isError":false,"content":[{"type":"text","text":"done"}]}"""
            }
            val events = buildList {
                if (includeStart) add(event(1, "tool/ptc-dispatch-start", startPayload))
                add(event(if (includeStart) 2 else 1, "tool/ptc-dispatch", dispatch))
            }
            return EventFold("ptc-session").fold(events)
        }

        val snapshot = mutableStateOf(conversation(isError = false, includeStart = false))
        val mode = mutableStateOf(TranscriptPresentation.Mode.Compact)
        val disclosures = DisclosureScope(TranscriptDisclosures(), "ptc-session")
        val listState = LazyListState()
        val positions = TranscriptReadingPositions()
        compose.setContent {
            DshTheme {
                Box(Modifier.height(520.dp)) {
                    ChatTranscript(
                        conversation = snapshot.value,
                        loading = false,
                        loadingOlder = false,
                        loadOlderFailed = false,
                        context = ChatNodeContext(
                            nodes = snapshot.value.nodes,
                            sessionId = "ptc-session",
                            disclosures = disclosures,
                            running = false,
                            cwd = null,
                            onOpenSubagent = {},
                            onBranchFrom = {},
                            onFeedback = { _, _ -> },
                        ),
                        presentation = TranscriptPresentation(mode.value),
                        listState = listState,
                        readingPositions = positions,
                        onLoadOlder = { false },
                        onRestoreOlder = {},
                    )
                }
            }
        }
        for (presentationMode in listOf(TranscriptPresentation.Mode.Compact, TranscriptPresentation.Mode.Standard)) {
            compose.runOnIdle { mode.value = presentationMode }
            compose.waitForIdle()
            compose.onNodeWithText("Which scope?").assertIsDisplayed()
            compose.onNodeWithText("Current session").assertIsDisplayed()
            compose.onNodeWithText("tool/ptc-dispatch").assertDoesNotExist()
        }

        compose.runOnIdle { snapshot.value = conversation(isError = true, includeStart = false) }
        compose.waitForIdle()
        compose.onNodeWithText("Which scope?").assertDoesNotExist()
        compose.onNodeWithText("Current session").assertDoesNotExist()
        compose.onNodeWithText("tool/ptc-dispatch").assertIsDisplayed()

        compose.runOnIdle { snapshot.value = conversation(name = "bash") }
        compose.waitForIdle()
        compose.onNodeWithText("tool/ptc-dispatch").assertIsDisplayed()
    }
}
