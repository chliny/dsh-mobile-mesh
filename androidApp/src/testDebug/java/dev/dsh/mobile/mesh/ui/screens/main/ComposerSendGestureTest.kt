package dev.dsh.mobile.mesh.ui.screens.main

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import dev.dsh.mobile.mesh.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "en-rUS")
class ComposerSendGestureTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun longPressChoosesDeliveryForOneMessageWithoutAlsoSubmittingOnRelease() {
        val draft = mutableStateOf("Change direction")
        val image = PendingAttachment.Image("image/png", "", null, 0, 1, 1, "reference.png")
        val attachments = mutableStateListOf<PendingAttachment>(image)
        val submissions = mutableListOf<Pair<String, String?>>()
        val submittedAttachments = mutableListOf<List<PendingAttachment>>()
        compose.setContent {
            DshTheme {
                Composer(
                    draft = draft.value,
                    onDraftChange = { draft.value = it },
                    attachments = attachments,
                    onRemoveAttachment = {},
                    onRetryAttachment = {},
                    permissions = null,
                    pendingPermission = null,
                    onPermissionPick = {},
                    contextBreakdown = null,
                    contextPressure = null,
                    models = null,
                    onOpenModels = {},
                    running = true,
                    enabled = true,
                    onOpenSheet = {},
                    onSend = { text, pending, mode ->
                        submissions.add(text to mode)
                        submittedAttachments.add(pending)
                        attachments.clear()
                    },
                    onStop = { error("Sending a message must not stop the turn") },
                )
            }
        }

        val send = compose.onNodeWithContentDescription("Send message")
        send.performTouchInput { longClick() }
        compose.runOnIdle {
            assertTrue(submissions.isEmpty())
            assertEquals("Change direction", draft.value)
        }
        compose.onNodeWithText("Steer").performTouchInput { click() }
        compose.runOnIdle { draft.value = "Later follow-up" }

        send.performTouchInput { longClick() }
        compose.onNodeWithText("Queue").performTouchInput { click() }
        compose.runOnIdle { draft.value = "Use the usual mode" }

        send.performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(
                listOf("Change direction" to "steer", "Later follow-up" to "queue", "Use the usual mode" to null),
                submissions,
            )
            assertEquals(listOf(listOf(image), emptyList(), emptyList()), submittedAttachments)
        }
    }
}
