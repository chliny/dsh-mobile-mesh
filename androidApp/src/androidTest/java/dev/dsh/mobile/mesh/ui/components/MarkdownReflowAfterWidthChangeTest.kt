package dev.dsh.mobile.mesh.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dsh.mobile.mesh.ui.theme.DshTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A Markdown row must be re-laid out when the window widens.
 *
 * Chromium keeps reporting the content height of the viewport a committed document was loaded in,
 * so a row that was first laid out in a narrow window (split screen, a folded device) used to keep
 * that narrow layout's height after the window widened. The transcript then showed the visible text
 * followed by a blank band the size of the stale excess, which is the failure this asserts against.
 * It needs a real WebView: the sizing is Chromium's, not Compose's.
 */
@RunWith(AndroidJUnit4::class)
class MarkdownReflowAfterWidthChangeTest {
    @get:Rule val compose = createComposeRule()

    private val markdown = """
        已提交。

        **提交**：`55fe683 fix(connect): 编辑连接页不再显示列表页的连接错误`

        包含内容（2 个文件）：
        - ConnectScreen.kt：仅新建连接表单渲染共享的 `failure`，进入已保存连接的编辑页不再带入列表页错误。
        - ConnectScreenEditorFailureTest.kt：新增回归测试（编辑页不显示 / 新建页仍显示），已通过变异验证。

        提交前复查结论：暂存内容无 bug；测试代码符合 AGENTS.md 的测试规范（真实流程、对外可观测行为、未为测试拆分业务代码）。

        两点说明：
        - 未推送远程（只按你的要求提交本地 commit）。
        - 工作树仍有 30 个未暂存/未跟踪改动（cookie 重构、ZeroTier 等属于其他并行工作），未纳入本次提交。
        - 一个范围外的小观察：编辑页同样会带入列表页的 `authorizationPending`（授权等待提示）。它不是错误提示、影响较小，本次未改动；如需要可以一并处理。
    """.trimIndent()

    @Test
    fun wideningTheWindowRelaysMarkdownRowsOutAtTheNewWidth() {
        val widthDp = mutableStateOf(200)
        val heightPx = mutableIntStateOf(0)
        compose.setContent {
            DshTheme {
                // An unbounded parent measures the row the way a LazyColumn item is measured.
                Column(Modifier.width(widthDp.value.dp).verticalScroll(rememberScrollState())) {
                    MarkdownText(markdown, modifier = Modifier.onSizeChanged { heightPx.intValue = it.height })
                }
            }
        }
        // The document is parsed and laid out asynchronously; wait for the real height, not the
        // one-line loading preview.
        compose.waitUntil(timeoutMillis = 20_000) { heightPx.intValue > (600 * compose.density.density).toInt() }
        val narrowHeight = heightPx.intValue

        compose.runOnIdle { widthDp.value = 368 }
        // The row briefly collapses while the document loads again, so wait for the settled height
        // of the wide layout. Without the re-layout it keeps `narrowHeight` forever.
        compose.waitUntil(timeoutMillis = 20_000) {
            heightPx.intValue in (narrowHeight / 3)..(narrowHeight * 9 / 10)
        }
        val wideHeight = heightPx.intValue

        assertTrue(
            "The widened row must be measured from the new layout, not the narrow one ($narrowHeight -> $wideHeight)",
            wideHeight < narrowHeight * 9 / 10,
        )
        assertTrue(
            "The row must not collapse while being re-laid out ($narrowHeight -> $wideHeight)",
            wideHeight > narrowHeight / 3,
        )
    }
}
