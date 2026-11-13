package dev.dsh.mobile.mesh.ui.screens.main

import android.app.Application
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.dsh.mobile.mesh.core.session.ConversationSnapshot
import dev.dsh.mobile.mesh.core.session.QuestionReplyAnswer
import dev.dsh.mobile.mesh.core.session.QuestionReplyNode
import dev.dsh.mobile.mesh.core.session.QuestionReplyQuestion
import dev.dsh.mobile.mesh.core.session.TurnStartNode
import dev.dsh.mobile.mesh.ui.theme.DshTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A saved answer is user input and must remain visible in the actual trajectory screen. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class TrajectoryTabQuestionReplyTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `trajectory shows the answered question and selected option`() {
        val reply = QuestionReplyNode(
            seq = 2,
            callId = "ask-1",
            questions = listOf(QuestionReplyQuestion("scope", "Which scope?", "Scope")),
            answers = listOf(QuestionReplyAnswer("scope", listOf("Current session"))),
            text = "Which scope? Current session",
        )
        compose.setContent {
            DshTheme {
                TrajectoryTab(
                    conversation = ConversationSnapshot(
                        sessionId = "question-session",
                        nodes = listOf(TurnStartNode(1, 1), reply),
                        blank = false,
                    ),
                    stats = null,
                    usage = null,
                    cwd = null,
                    listState = LazyListState(),
                    disclosureScope = DisclosureScope(TranscriptDisclosures(), "question-session"),
                )
            }
        }

        compose.onNodeWithText("Scope: Current session", substring = true).assertExists()
    }
}
