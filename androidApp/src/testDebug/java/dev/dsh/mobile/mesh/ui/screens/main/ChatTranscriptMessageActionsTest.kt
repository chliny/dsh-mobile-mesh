package dev.dsh.mobile.mesh.ui.screens.main

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dagger.hilt.android.testing.HiltAndroidRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dev.dsh.mobile.mesh.core.session.AssistantMessageNode
import dev.dsh.mobile.mesh.core.session.EventFold
import dev.dsh.mobile.mesh.core.session.SessionEventEnvelope
import dev.dsh.mobile.mesh.core.session.ChatBlock
import dev.dsh.mobile.mesh.core.session.ConversationSnapshot
import dev.dsh.mobile.mesh.data.TranscriptPresentation
import dev.dsh.mobile.mesh.ui.components.MarkdownText
import dev.dsh.mobile.mesh.ui.theme.DshTheme
import dev.dsh.mobile.mesh.ui.theme.ThemePreference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A finished answer is rendered by a WebView, which consumes its taps.
 *
 * The per-message actions (copy, branch, feedback) are revealed by tapping the message, so a tap
 * on rendered prose has to reach that toggle even though Compose never sees the touch itself.
 * The tap is delivered to the WebView directly: that is exactly the delivery a device performs,
 * and Robolectric's Compose test injection skips the interop view.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = HiltTestApplication::class)
class ChatTranscriptMessageActionsTest {
    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<ComponentActivity>()

    @After fun resetWebViewShadow() {
        ReadingMarkdownWebViewShadow.resetTapRouting()
        ReadingMarkdownWebViewShadow.contentHeight = 1800
        ReadingMarkdownWebViewShadow.autoFinishPage = true
        ReadingMarkdownWebViewShadow.pageFinishes.clear()
        ReadingMarkdownWebViewShadow.pending.clear()
    }

    private fun showMarkdownMessage(
        onBranchFrom: (Long) -> Unit,
        markdown: String = "# Download\n\n| Asset | Size |\n| --- | --- |\n| app.apk | 12 MB |\n\nRun **the installer** to upgrade.",
        onOpenUri: (String) -> Unit = {},
        listState: LazyListState = LazyListState(),
    ): WebView {
        val nodes = listOf(
            AssistantMessageNode(42, null, 1, 1, listOf(ChatBlock("text", markdown))),
        )
        val disclosures = DisclosureScope(TranscriptDisclosures(), "actions-session")
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                override fun openUri(uri: String) = onOpenUri(uri)
            }) {
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
                            onOpenSubagent = {},
                            onBranchFrom = onBranchFrom,
                            onFeedback = { _, _ -> },
                        ),
                        presentation = TranscriptPresentation(),
                        listState = listState,
                        readingPositions = TranscriptReadingPositions(),
                        onLoadOlder = { false },
                        onRestoreOlder = {},
                    )
                }
            }
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.waitForIdle()
            findWebView(compose.activity.window.decorView) != null
        }
        // Chromium reports the settled document height through its visual-state callback; without
        // it the row still measures one pixel and a tap would not land on prose.
        ReadingMarkdownWebViewShadow.completeLayout()
        compose.waitForIdle()
        return requireNotNull(findWebView(compose.activity.window.decorView)) { "no rendered Markdown WebView" }
    }

    private fun findWebView(root: View): WebView? {
        if (root is WebView) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findWebView(root.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    /** Deliver the gesture an Android WebView receives for a tap, without Compose's help. */
    private fun tapWebView(webView: WebView) {
        val x = webView.width / 2f
        val y = webView.height / 2f
        val downAt = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downAt, downAt, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(downAt, downAt + 40L, MotionEvent.ACTION_UP, x, y, 0)
        webView.dispatchTouchEvent(down)
        webView.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
        compose.waitForIdle()
    }

    @Test
    @Config(shadows = [ReadingMarkdownWebViewShadow::class])
    fun `a tap on markdown prose reveals the message actions`() {
        ReadingMarkdownWebViewShadow.contentHeight = 250
        var forkedFrom: Long? = null
        val webView = showMarkdownMessage(onBranchFrom = { forkedFrom = it })

        tapWebView(webView)

        compose.onNodeWithContentDescription("Branch from here").performClick()
        compose.runOnIdle { assertEquals(42L, forkedFrom) }
    }

    @Test
    @Config(shadows = [ReadingMarkdownWebViewShadow::class])
    fun `a prose tap used to stop active transcript scrolling does not open message actions`() {
        ReadingMarkdownWebViewShadow.contentHeight = 1800
        val listState = LazyListState()
        val webView = showMarkdownMessage(onBranchFrom = {}, listState = listState)
        val enteredScroll = CompletableDeferred<Unit>()
        val releaseScroll = CompletableDeferred<Unit>()
        compose.runOnIdle {
            CoroutineScope(Dispatchers.Main.immediate).launch {
                listState.scroll {
                    enteredScroll.complete(Unit)
                    releaseScroll.await()
                }
            }
        }
        try {
            compose.waitUntil(5_000) { enteredScroll.isCompleted && listState.isScrollInProgress }
            val downAt = SystemClock.uptimeMillis()
            val x = webView.width / 2f
            val y = webView.height / 2f
            val down = MotionEvent.obtain(downAt, downAt, MotionEvent.ACTION_DOWN, x, y, 0)
            val up = MotionEvent.obtain(downAt, downAt + 40L, MotionEvent.ACTION_UP, x, y, 0)
            webView.dispatchTouchEvent(down)
            // The user is tapping to stop the active scroll. By ACTION_UP, the scroll has already
            // settled, so only retaining the ACTION_DOWN-time state prevents the menu from opening.
            releaseScroll.complete(Unit)
            compose.waitForIdle()
            webView.dispatchTouchEvent(up)
            down.recycle()
            up.recycle()
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Branch from here").assertDoesNotExist()

            // Once scrolling has actually settled, an ordinary prose tap still opens the actions.
            tapWebView(webView)
            compose.onNodeWithContentDescription("Branch from here").assertIsDisplayed()
        } finally {
            releaseScroll.complete(Unit)
        }
    }

    @Test
    @Config(shadows = [ReadingMarkdownWebViewShadow::class])
    fun `a tap that lands on a link routes the destination without revealing message actions`() {
        ReadingMarkdownWebViewShadow.contentHeight = 250
        ReadingMarkdownWebViewShadow.hitTestType = WebView.HitTestResult.ANCHOR_TYPE
        val destination = "https://example.test/docs"
        var opened: String? = null
        val webView = showMarkdownMessage(
            onBranchFrom = {},
            // Keep the already-proven heading/table structure so the async parser definitely
            // selects the WebView, with the link embedded in ordinary rendered prose.
            markdown = "# Download\n\n| Asset | Size |\n| --- | --- |\n| app.apk | 12 MB |\n\n[Open documentation]($destination)",
            onOpenUri = { opened = it },
        )

        tapWebView(webView)
        compose.onNodeWithContentDescription("Branch from here").assertDoesNotExist()

        // Robolectric does not synthesize Chromium navigation from MotionEvents. Exercise the real
        // WebViewClient callback that Chromium invokes for the link tap and assert its user-visible
        // URI handler side effect rather than only checking that the action menu stayed closed.
        val handled = webView.webViewClient.shouldOverrideUrlLoading(webView, destination)
        assertTrue("The Markdown link should be routed by the WebView client", handled)
        assertEquals(destination, opened)
    }

    @Test
    fun `diff markdown keeps long changed lines horizontally scrollable and copies the patch`() {
        val longAddedLine = "+" + "a-very-long-command-argument ".repeat(30)
        val patch = """--- a/old.sh
+++ b/new.sh
@@ -1 +1 @@
-rm -rf old
+rm -rf new
$longAddedLine
 unchanged
"""
        val markdown = "```diff\n$patch```"
        val clipboard = RecordingClipboard()

        compose.setContent {
            DshTheme(preference = ThemePreference.LIGHT) {
                CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                    MarkdownText(markdown)
                }
            }
        }

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.waitForIdle()
            compose.onAllNodesWithText("-rm -rf old", substring = false).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("--- a/old.sh", substring = false).assertIsDisplayed()
        compose.onNodeWithText("+++ b/new.sh", substring = false).assertIsDisplayed()
        compose.onNodeWithText("-rm -rf old", substring = false).assertIsDisplayed()
        compose.onNodeWithText("+rm -rf new", substring = false).assertIsDisplayed()
        val horizontalCode = compose.onNode(
            hasScrollAction() and hasAnyDescendant(hasText(longAddedLine, substring = true)),
            useUnmergedTree = true,
        )
        assertTrue(
            "A long unified-diff line must expose horizontal scrolling rather than wrap and misalign",
            horizontalCode.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].maxValue() > 0f,
        )
        horizontalCode.performSemanticsAction(SemanticsActions.ScrollBy) { it(500f, 0f) }
        assertTrue(horizontalCode.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value() > 0f)
        compose.onNodeWithText("Copy").performClick()
        compose.runOnIdle {
            assertEquals(patch, clipboard.copied?.text)
        }
    }

    @Test
    fun `answered question remains visible after a completed process folds`() {
        fun event(seq: Long, type: String, data: String) = SessionEventEnvelope(
            type = type,
            seq = seq,
            time = seq,
            data = Json.parseToJsonElement(data),
        )
        val conversation = EventFold("question-fold-session").fold(
            listOf(
                event(1, "turn/start", """{"turn":1}"""),
                event(2, "tool/call", """{"callId":"ask-1","name":"ask_user_question","arguments":"{\"questions\":[{\"id\":\"scope\",\"question\":\"Which scope?\",\"header\":\"Scope\"}]}","turn":1,"step":0}"""),
                event(3, "tool/result", """{"message":{"source":{"kind":"tool","callId":"ask-1"},"content":[{"type":"text","text":"{\"answers\":[{\"id\":\"scope\",\"selected\":[\"Current session\"]}]}"}]}}"""),
                event(4, "assistant/message", """{"turn":1,"step":1,"message":{"id":"assistant-1","content":[{"type":"text","text":"Done"}]}}"""),
                event(5, "turn/end", """{"turn":1,"reason":{"kind":"completed"}}"""),
            ),
        )
        val mode = mutableStateOf(TranscriptPresentation.Mode.Compact)
        val disclosures = DisclosureScope(TranscriptDisclosures(), "question-fold-session")
        val listState = LazyListState()
        val readingPositions = TranscriptReadingPositions()

        compose.setContent {
            DshTheme {
                ChatTranscript(
                    conversation = conversation,
                    loading = false,
                    loadingOlder = false,
                    loadOlderFailed = false,
                    context = ChatNodeContext(
                        nodes = conversation.nodes,
                        sessionId = "question-fold-session",
                        disclosures = disclosures,
                        running = false,
                        cwd = null,
                        onOpenSubagent = {},
                        onBranchFrom = {},
                        onFeedback = { _, _ -> },
                    ),
                    presentation = TranscriptPresentation(mode.value),
                    listState = listState,
                    readingPositions = readingPositions,
                    onLoadOlder = { false },
                    onRestoreOlder = {},
                )
            }
        }
        for (presentationMode in listOf(TranscriptPresentation.Mode.Compact, TranscriptPresentation.Mode.Standard)) {
            compose.runOnIdle { mode.value = presentationMode }
            compose.waitForIdle()
            compose.onNodeWithText("Which scope?").assertIsDisplayed()
            compose.onNodeWithText("Current session").assertIsDisplayed()
            compose.onNodeWithText("Done").assertIsDisplayed()
        }
    }

    private class RecordingClipboard : ClipboardManager {
        var copied: AnnotatedString? = null
        override fun getText(): AnnotatedString? = copied
        override fun setText(annotatedString: AnnotatedString) {
            copied = annotatedString
        }
    }
}
