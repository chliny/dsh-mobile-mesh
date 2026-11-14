package dev.dsh.mobile.mesh.ui.screens.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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

/**
 * The per-message actions (copy, branch, feedback) are revealed by tapping a message.
 *
 * Answers are rendered as Compose Markdown text now, so the message's own tap handling must see a
 * tap on prose while a tap on a link must stay a link click and must not toggle the actions.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = HiltTestApplication::class)
class ChatTranscriptMessageActionsTest {
    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createComposeRule()

    private fun showAnswer(
        markdown: String,
        renderedText: String,
        onBranchFrom: (Long) -> Unit,
        onOpenFile: ((String, String) -> Unit)? = null,
    ) {
        val nodes = listOf(
            AssistantMessageNode(42, null, 1, 1, listOf(ChatBlock("text", markdown))),
        )
        val disclosures = DisclosureScope(TranscriptDisclosures(), "actions-session")
        compose.setContent {
            Box(Modifier.height(520.dp)) {
                ChatTranscript(
                    conversation = ConversationSnapshot("actions-session", nodes = nodes, blank = false),
                    loading = false,
                    loadingOlder = false,
                    loadOlderFailed = false,
                    context = ChatNodeContext(
                        nodes = nodes,
                        sessionId = "actions-session",
                        disclosures = disclosures,
                        running = false,
                        cwd = null,
                        onOpenFile = onOpenFile,
                        onOpenSubagent = {},
                        onBranchFrom = onBranchFrom,
                        onFeedback = { _, _ -> },
                    ),
                    presentation = TranscriptPresentation(),
                    listState = LazyListState(),
                    readingPositions = TranscriptReadingPositions(),
                    onLoadOlder = { false },
                    onRestoreOlder = {},
                )
            }
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(renderedText, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `a tap on rendered prose reveals the message actions`() {
        var forkedFrom: Long? = null
        showAnswer(
            "A finished **assistant** answer with a table.\n\n| A | B |\n| --- | --- |\n| 1 | 2 |",
            renderedText = "assistant answer",
            onBranchFrom = { forkedFrom = it },
        )

        compose.onAllNodes(hasClickAction()).onFirst().performTouchInput { click() }
        compose.onNodeWithContentDescription("Branch from here").performClick()
        compose.runOnIdle { assertEquals(42L, forkedFrom) }
    }

    @Test
    fun `a tap on a workspace link opens the file without revealing the message actions`() {
        var opened: String? = null
        showAnswer(
            "[Open the notes](notes.md)",
            renderedText = "Open the notes",
            onBranchFrom = {},
            onOpenFile = { path, _ -> opened = path },
        )

        compose.onNodeWithText("Open the notes", useUnmergedTree = true).performTouchInput { click() }
        compose.waitForIdle()

        assertEquals("notes.md", opened)
        compose.onNodeWithContentDescription("Branch from here").assertDoesNotExist()
    }
}
