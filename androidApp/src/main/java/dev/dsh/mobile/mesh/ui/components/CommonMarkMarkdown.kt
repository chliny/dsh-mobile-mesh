package dev.dsh.mobile.mesh.ui.components

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import dev.dsh.mobile.mesh.ui.theme.DsColors
import dev.dsh.mobile.mesh.ui.theme.DsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import java.nio.charset.StandardCharsets
import java.util.Base64

private const val MARKDOWN_BASE_URL = "https://markdown.invalid/"
private const val MARKDOWN_LINK_SCHEME = "dsh-markdown"

private val commonMarkExtensions = listOf(
    TablesExtension.create(),
    TaskListItemsExtension.create(),
    StrikethroughExtension.create(),
)
private val commonMarkParser = Parser.builder().extensions(commonMarkExtensions).build()
private val commonMarkHtmlRenderer = HtmlRenderer.builder().extensions(commonMarkExtensions).build()

/**
 * Standards-based Markdown rendering shared by file previews and transcript content. The CommonMark
 * parser/HTML renderer handles delimiter rules, nested blocks, reference links, escaping, entities,
 * code spans, and the rest of the CommonMark grammar; GFM tables, task lists, and strikethrough are
 * kept as opt-in extensions for compatibility with content already supported by the app.
 */
@Composable
internal fun CommonMarkMarkdown(
    text: String,
    modifier: Modifier = Modifier,
    imageResolver: (suspend (String) -> String?)? = null,
    onOpenLink: (String) -> Unit,
) {
    val colors = DsTheme.colors
    val webState = remember { CommonMarkWebState() }
    webState.onOpenLink = onOpenLink

    val html by produceState<String?>(null, text, imageResolver, colors) {
        val document = withContext(Dispatchers.Default) { commonMarkParser.parse(text) }
        rewriteDestinations(document, imageResolver)
        value = withContext(Dispatchers.Default) {
            wrapMarkdownHtml(commonMarkHtmlRenderer.render(document), colors)
        }
    }

    if (html != null) {
        AndroidView(
            factory = { viewContext -> CommonMarkWebView(viewContext, webState, colors.bgBase) },
            update = { webView ->
                webView.onOpenLink = onOpenLink
                webView.setBackgroundColor(colors.bgBase.toArgb())
                webView.render(html!!)
            },
            modifier = modifier.fillMaxWidth(),
        )
    }
}

private suspend fun rewriteDestinations(
    root: Node,
    imageResolver: (suspend (String) -> String?)?,
) {
    suspend fun visit(node: Node) {
        when (node) {
            is Image -> {
                val original = node.destination
                val resolved = imageResolver?.let { resolver -> runCatching { resolver(original) }.getOrNull() }
                if (!resolved.isNullOrBlank()) node.destination = resolved
            }
            is Link -> {
                val destination = node.destination
                if (!destination.startsWith('#')) node.destination = encodeMarkdownLink(destination)
            }
        }
        var child = node.firstChild
        while (child != null) {
            val next = child.next
            visit(child)
            child = next
        }
    }
    visit(root)
}

internal fun encodeMarkdownLink(destination: String): String {
    val encoded = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(destination.toByteArray(StandardCharsets.UTF_8))
    return "$MARKDOWN_LINK_SCHEME://open/$encoded"
}

internal fun decodeMarkdownLink(url: String): String? = runCatching {
    val uri = java.net.URI(url)
    if (uri.scheme != MARKDOWN_LINK_SCHEME || uri.host != "open") return null
    val token = uri.rawPath?.substringAfterLast('/')?.takeIf(String::isNotEmpty) ?: return null
    String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8)
}.getOrNull()

internal fun relativeMarkdownNavigationTarget(url: String): String? = runCatching {
    val uri = java.net.URI(url)
    if (uri.scheme != "https" || uri.host != "markdown.invalid") return null
    if (uri.path == "/" && uri.rawQuery == null && uri.rawFragment != null) return null
    buildString {
        append(uri.path.orEmpty().removePrefix("/"))
        uri.rawQuery?.let { append('?').append(it) }
        uri.rawFragment?.let { append('#').append(it) }
    }.takeIf(String::isNotBlank)
}.getOrNull()

/** Parse and render one fragment without the WebView document wrapper (also used by conformance tests). */
internal fun renderCommonMarkFragment(markdown: String): String =
    commonMarkHtmlRenderer.render(commonMarkParser.parse(markdown))

private fun wrapMarkdownHtml(fragment: String, colors: DsColors): String {
    fun css(color: Color): String = "#%06X".format(color.toArgb() and 0xFFFFFF)
    val background = css(colors.bgBase)
    val text = css(colors.labelPrimary)
    val secondary = css(colors.labelSecondary)
    val border = css(colors.borderL1)
    val accent = css(colors.accent)
    val codeBackground = css(colors.inlineCode)
    return """
        <!doctype html>
        <html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=5">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data: https: http:; style-src 'unsafe-inline'; font-src data: https:;">
        <style>
          * { box-sizing: border-box; }
          html, body { margin: 0; padding: 0; background: $background; color: $text; }
          body { font-family: sans-serif; font-size: 16px; line-height: 1.5; overflow-wrap: anywhere; }
          p { margin: 0 0 1em; }
          p:last-child, ul:last-child, ol:last-child, blockquote:last-child, pre:last-child, table:last-child { margin-bottom: 0; }
          h1, h2, h3, h4, h5, h6 { color: $text; line-height: 1.25; margin: 1em 0 .45em; }
          h1 { font-size: 1.8em; } h2 { font-size: 1.55em; } h3 { font-size: 1.3em; }
          h4 { font-size: 1.15em; } h5 { font-size: 1.05em; } h6 { font-size: 1em; }
          ul, ol { padding-left: 1.5em; margin: .35em 0 1em; }
          li { margin: .2em 0; }
          blockquote { color: $secondary; border-left: 3px solid $border; margin: .5em 0; padding: .1em 0 .1em .9em; }
          a { color: $accent; text-decoration: underline; }
          code { font-family: monospace; background: $codeBackground; padding: .08em .25em; border-radius: 3px; }
          pre { white-space: pre; overflow-x: auto; background: $background; border: 1px solid $border; border-radius: 8px; padding: 12px; margin: .5em 0 1em; }
          pre code { background: transparent; padding: 0; border-radius: 0; }
          table { border-collapse: collapse; display: block; max-width: 100%; overflow-x: auto; margin: .5em 0 1em; }
          th, td { border: 1px solid $border; padding: 6px 9px; text-align: left; vertical-align: top; }
          th { font-weight: 600; }
          img { max-width: 100%; height: auto; }
          hr { border: 0; border-top: 1px solid $border; margin: 1em 0; }
          ul.task-list { list-style: none; padding-left: 0; }
          li.task-list-item { list-style: none; }
          input[type=checkbox] { margin: 0 .45em 0 0; vertical-align: middle; pointer-events: none; }
        </style>
        </head><body>$fragment</body></html>
    """.trimIndent()
}

private class CommonMarkWebState {
    var onOpenLink: (String) -> Unit = {}
}

private class CommonMarkWebView(
    context: android.content.Context,
    private val state: CommonMarkWebState,
    background: Color,
) : WebView(context) {
    var onOpenLink: (String) -> Unit
        get() = state.onOpenLink
        set(value) { state.onOpenLink = value }

    private var loadedHtml: String? = null

    init {
        setBackgroundColor(background.toArgb())
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        settings.apply {
            javaScriptEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                handleMarkdownNavigation(request.url.toString())

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                handleMarkdownNavigation(url)

            override fun onPageFinished(view: WebView, url: String) {
                view.post { view.requestLayout() }
            }
        }
    }

    fun render(html: String) {
        if (loadedHtml == html) return
        loadedHtml = html
        loadDataWithBaseURL(MARKDOWN_BASE_URL, html, "text/html", "UTF-8", null)
    }

    private fun handleMarkdownNavigation(url: String): Boolean {
        decodeMarkdownLink(url)?.let { target ->
            runCatching { onOpenLink(target) }
            return true
        }
        val uri = Uri.parse(url)
        if (uri.host == "markdown.invalid" && uri.fragment != null && uri.encodedPath.orEmpty() == "/") return false
        val target = relativeMarkdownNavigationTarget(url) ?: url
        runCatching { onOpenLink(target) }
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val height = (contentHeight * scale).toInt().coerceAtLeast(1)
        setMeasuredDimension(resolveSize(measuredWidth, widthMeasureSpec), resolveSize(height, heightMeasureSpec))
    }
}

/** Clean a Markdown image path before resolving it relative to a workspace document. */
internal fun markdownImagePath(source: String): String? =
    source.trim().trim('<', '>').substringBefore('#').substringBefore('?').takeIf { it.isNotBlank() }
