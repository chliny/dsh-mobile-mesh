package dev.dsh.mobile.mesh.ui.components

import android.app.Application
import android.webkit.WebView
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowWebView

/**
 * A transcript image the phone cannot read must not render as a broken-image glyph.
 *
 * Assistant messages routinely embed workspace screenshots as Markdown links. When the read fails
 * (an old host, a deleted file, or a workspace read the client cannot decode), a relative `<img>`
 * source makes the document request its own base URL and the reader sees a broken image where the
 * caption should be; the caption itself — the alt text — is the only truthful fallback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MarkdownImageFallbackTest {
    @get:Rule val compose = createComposeRule()

    @Test
    @Config(shadows = [RecordingMarkdownWebViewShadow::class])
    fun `an unreadable local image keeps its caption instead of a broken image`() {
        RecordingMarkdownWebViewShadow.loadedHtml = null
        compose.setContent {
            CompositionLocalProvider {
                MarkdownText(
                    "截图：\n\n![设置页中的子代理完成通知选项](assets/screens/notify-subagent-crop.png)",
                    imageResolver = { null },
                )
            }
        }
        compose.waitUntil(5_000) { RecordingMarkdownWebViewShadow.loadedHtml != null }

        val html = RecordingMarkdownWebViewShadow.loadedHtml!!
        assertTrue("the caption is still visible", html.contains("设置页中的子代理完成通知选项"))
        assertFalse("no image element survives without bytes", html.contains("<img"))
    }
}

/** Records the document the Markdown view loads so the test can inspect the rendered fallback. */
@Implements(WebView::class)
class RecordingMarkdownWebViewShadow : ShadowWebView() {
    @Implementation
    override fun loadDataWithBaseURL(
        baseUrl: String?,
        data: String?,
        mimeType: String?,
        encoding: String?,
        historyUrl: String?,
    ) {
        loadedHtml = data
        super.loadDataWithBaseURL(baseUrl, data, mimeType, encoding, historyUrl)
    }

    companion object {
        @Volatile
        var loadedHtml: String? = null
    }
}
