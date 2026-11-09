package dev.dsh.mobile.mesh.ui.screens.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dev.dsh.mobile.mesh.core.session.AssistantMessageNode
import dev.dsh.mobile.mesh.core.session.ChatBlock
import dev.dsh.mobile.mesh.core.session.ConversationSnapshot
import dev.dsh.mobile.mesh.data.TranscriptPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = HiltTestApplication::class)
class ChatTranscriptStreamingTest {
    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createComposeRule()

    @Test
    fun `a finished assistant message can still be forked from its action menu`() {
        val nodes = listOf(AssistantMessageNode(42, null, 1, 1, listOf(ChatBlock("text", "A finished assistant response"))))
        var forkedFrom: Long? = null
        val disclosures = DisclosureScope(TranscriptDisclosures(), "fork-session")
        compose.setContent {
            Box(Modifier.height(520.dp)) {
                ChatTranscript(
                    conversation = ConversationSnapshot("fork-session", nodes = nodes, blank = false),
                    loading = false, loadingOlder = false, loadOlderFailed = false,
                    context = ChatNodeContext(
                        nodes = nodes, sessionId = "fork-session", disclosures = disclosures,
                        running = false, cwd = null, onOpenSubagent = {}, onBranchFrom = { forkedFrom = it },
                        onFeedback = { _, _ -> },
                    ),
                    presentation = TranscriptPresentation(), listState = LazyListState(),
                    readingPositions = TranscriptReadingPositions(), onLoadOlder = { false }, onRestoreOlder = {},
                )
            }
        }
        compose.onNodeWithText("A finished assistant response").performClick()
        compose.onNodeWithContentDescription("Branch from here").performClick()
        compose.runOnIdle { assertEquals(42L, forkedFrom) }
    }

    @Test
    fun `assistant revisions remain complete literal text while streaming`() {
        val first = "# Streaming answer\n\n" + "A long paragraph with **bold text**. ".repeat(300)
        val second = first + "\n\n## Final section\n\nThe final streamed tail."
        val text = mutableStateOf(first)
        val running = mutableStateOf(true)
        val listState = LazyListState()
        val positions = TranscriptReadingPositions()
        val disclosures = DisclosureScope(TranscriptDisclosures(), "streaming-session")
        compose.setContent {
            val nodes = listOf(AssistantMessageNode(1, null, 1, 1, listOf(ChatBlock("text", text.value))))
            Box(Modifier.height(520.dp)) {
                ChatTranscript(
                    conversation = ConversationSnapshot("streaming-session", nodes = nodes, blank = false),
                    loading = false, loadingOlder = false, loadOlderFailed = false,
                    context = ChatNodeContext(
                        nodes = nodes, sessionId = "streaming-session", disclosures = disclosures,
                        running = running.value, cwd = null, onOpenSubagent = {}, onBranchFrom = {},
                        onFeedback = { _, _ -> },
                    ),
                    presentation = TranscriptPresentation(), listState = listState,
                    readingPositions = positions, onLoadOlder = { false }, onRestoreOlder = {},
                )
            }
        }
        compose.onNodeWithText(first, substring = false).assertExists()
        compose.runOnIdle { text.value = second }
        compose.onNodeWithText(second, substring = false).assertExists()
    }

    @Test
    @Config(shadows = [ReadingMarkdownWebViewShadow::class])
    fun `a finished answer that settles into a taller markdown view keeps the reader at the tail`() {
        ReadingMarkdownWebViewShadow.pending.clear()
        // The settled document is much taller than the streaming text it replaces.
        ReadingMarkdownWebViewShadow.contentHeight = 5200
        val text = "| Item | Value |\n| --- | --- |\n" +
            (1..120).joinToString("\n") { "| Streaming row $it | Value $it |" }
        val nodes = listOf(AssistantMessageNode(71, null, 1, 1, listOf(ChatBlock("text", text))))
        val running = mutableStateOf(true)
        val listState = LazyListState()
        val disclosures = DisclosureScope(TranscriptDisclosures(), "settled-tail")
        compose.setContent {
            Box(Modifier.height(520.dp)) {
                ChatTranscript(
                    conversation = ConversationSnapshot("settled-tail", nodes = nodes, blank = false),
                    loading = false, loadingOlder = false, loadOlderFailed = false,
                    context = ChatNodeContext(nodes = nodes, sessionId = "settled-tail", disclosures = disclosures,
                        running = running.value, cwd = null, onOpenSubagent = {}, onBranchFrom = {},
                        onFeedback = { _, _ -> }),
                    presentation = TranscriptPresentation(), listState = listState,
                    readingPositions = TranscriptReadingPositions(),
                    onLoadOlder = { false }, onRestoreOlder = {},
                )
            }
        }
        compose.waitForIdle()

        // A reader who once scrolled in this session is no longer restoring; the tail must still
        // stay pinned when the finished answer replaces its text with a taller Markdown view.
        compose.onRoot().performTouchInput { swipeDown() }
        compose.waitForIdle()
        repeat(8) {
            compose.onRoot().performTouchInput { swipeUp() }
            compose.waitForIdle()
        }
        compose.runOnIdle { assertTrue("Reader must be at the tail", !listState.canScrollForward) }

        compose.runOnIdle { running.value = false }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.waitForIdle(); ReadingMarkdownWebViewShadow.pending.isNotEmpty()
        }
        compose.runOnIdle { ReadingMarkdownWebViewShadow.completeLayout() }
        compose.waitUntil(timeoutMillis = 5_000) { compose.waitForIdle(); !listState.canScrollForward }
        compose.runOnIdle {
            assertTrue(
                "A finished answer that grows must keep the reader at the tail",
                !listState.canScrollForward,
            )
        }
        ReadingMarkdownWebViewShadow.contentHeight = 1800
    }
}
