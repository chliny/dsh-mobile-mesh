package dev.dsh.mobile.mesh.ui.components

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import dev.dsh.mobile.mesh.ui.theme.DshTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A transcript image the phone cannot read must not render as a broken image.
 *
 * Assistant messages routinely embed workspace screenshots as Markdown links. When the read fails
 * (an old host, a deleted file, or a workspace read the client cannot decode), the caption — the
 * alt text — is the only truthful fallback and must stay visible.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MarkdownImageFallbackTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `an unreadable local image keeps its caption`() {
        val caption = "设置页中的子代理完成通知选项"
        compose.setContent {
            DshTheme {
                MarkdownText(
                    "截图：\n\n![$caption](assets/notify-subagent-crop.png)",
                    imageResolver = { null },
                )
            }
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(caption, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(caption, substring = true).assertExists()
    }
}
