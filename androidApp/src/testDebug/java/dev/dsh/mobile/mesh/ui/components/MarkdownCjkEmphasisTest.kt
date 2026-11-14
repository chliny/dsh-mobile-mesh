package dev.dsh.mobile.mesh.ui.components

import android.app.Application
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.dsh.mobile.mesh.ui.theme.DshTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** User-visible regression: a CJK punctuation mark adjacent to a bold delimiter must still render bold. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MarkdownCjkEmphasisTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun cjkBoldLabelWithoutFollowingSpaceRendersEmphasis() {
        compose.setContent {
            DshTheme { MarkdownText("**结论：**本次较长恢复主要是覆盖网络路径反复不可达。") }
        }
        compose.waitForIdle()

        val rendered = compose.onNodeWithText("结论：", substring = true, useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsProperties.Text].single()
        assertFalse("Markdown delimiters must not leak into rendered text", rendered.text.contains("**"))
        assertTrue("The following CJK text must remain present", rendered.text.contains("本次较长恢复"))
        assertTrue(
            "The conclusion label must have a bold span",
            rendered.spanStyles.any { it.item.fontWeight == FontWeight.Bold },
        )
    }
}
