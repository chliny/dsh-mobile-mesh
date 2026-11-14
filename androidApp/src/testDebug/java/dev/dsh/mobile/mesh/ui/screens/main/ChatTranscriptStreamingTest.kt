package dev.dsh.mobile.mesh.ui.screens.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
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

    private fun markdownVisible(fragment: String): Boolean =
        compose.onAllNodesWithText(fragment, substring = true).fetchSemanticsNodes().isNotEmpty()

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
        compose.waitUntil(timeoutMillis = 5_000) { markdownVisible("A finished assistant response") }
        compose.onNodeWithText("A finished assistant response").performClick()
        compose.onNodeWithContentDescription("Branch from here").performClick()
        compose.runOnIdle { assertEquals(42L, forkedFrom) }
    }

    @Test
    fun `assistant revisions keep the whole streamed answer readable`() {
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
        compose.waitUntil(timeoutMillis = 10_000) { markdownVisible("Streaming answer") }
        assertTrue("the streamed paragraph stays complete", markdownVisible("A long paragraph with bold text."))
        compose.runOnIdle { text.value = second }
        compose.waitUntil(timeoutMillis = 10_000) { markdownVisible("The final streamed tail.") }
    }

    /** The finished answer grows from a short streaming placeholder into a tall table. */
    @Test
    fun `a finished answer that grows keeps the reader at the tail`() {
        val text = mutableStateOf("Working")
        val running = mutableStateOf(true)
        val listState = LazyListState()
        val disclosures = DisclosureScope(TranscriptDisclosures(), "settled-tail")
        compose.setContent {
            val nodes = listOf(AssistantMessageNode(71, null, 1, 1, listOf(ChatBlock("text", text.value))))
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
        compose.waitUntil(timeoutMillis = 10_000) { markdownVisible("Working") }
        repeat(2) {
            compose.onRoot().performTouchInput { swipeUp() }
            compose.waitForIdle()
        }
        compose.runOnIdle { assertTrue("Reader must be at the tail", !listState.canScrollForward) }

        val table = "| Item | Value |\n| --- | --- |\n" +
            (1..120).joinToString("\n") { "| Streaming row $it | Value $it |" }
        compose.runOnIdle {
            text.value = table
            running.value = false
        }
        compose.waitUntil(timeoutMillis = 10_000) { markdownVisible("Streaming row 120") }
        compose.waitUntil(timeoutMillis = 10_000) { !listState.canScrollForward }
        compose.runOnIdle {
            assertTrue(
                "A finished answer that grows must keep the reader at the tail",
                !listState.canScrollForward,
            )
        }
    }
}
