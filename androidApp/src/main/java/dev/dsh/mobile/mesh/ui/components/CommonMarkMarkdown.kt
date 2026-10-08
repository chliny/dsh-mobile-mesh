package dev.dsh.mobile.mesh.ui.components

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.ui.theme.DsColors
import dev.dsh.mobile.mesh.ui.theme.DsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.LinkedHashMap

private const val MARKDOWN_BASE_URL = "https://markdown.invalid/"
private const val MARKDOWN_LINK_SCHEME = "dsh-markdown"
private const val MARKDOWN_COPY_SCHEME = "dsh-markdown-copy"
private val RENDERED_CODE_BLOCK = Regex("<pre><code(?: class=\"([^\"]*)\")?>(.*?)</code></pre>", RegexOption.DOT_MATCHES_ALL)

private val commonMarkExtensions = listOf(
    TablesExtension.create(),
    TaskListItemsExtension.create(),
    StrikethroughExtension.create(),
)
private val commonMarkParser = Parser.builder().extensions(commonMarkExtensions).build()
private val commonMarkHtmlRenderer = HtmlRenderer.builder().extensions(commonMarkExtensions).build()

private const val MAX_CACHEABLE_MARKDOWN_CHARS = 64 * 1024

internal data class MarkdownRenderCacheKey(
    val markdown: String,
    val colors: DsColors,
    val darkMode: Boolean,
    val copyLabel: String,
    val resolvedImageDestinations: List<String>,
)

private object CommonMarkRenderCache {
    private const val MAX_ENTRIES = 48
    private const val MAX_CACHEABLE_HTML_CHARS = 128 * 1024
    private const val MAX_TOTAL_CHARS = 1_500_000
    private val entries = LinkedHashMap<MarkdownRenderCacheKey, RenderedMarkdown>(MAX_ENTRIES, 0.75f, true)
    private var retainedChars = 0

    fun get(key: MarkdownRenderCacheKey): RenderedMarkdown? = synchronized(entries) { entries[key] }

    fun put(key: MarkdownRenderCacheKey, rendered: RenderedMarkdown) {
        if (key.markdown.length > MAX_CACHEABLE_MARKDOWN_CHARS || rendered.html.length > MAX_CACHEABLE_HTML_CHARS) return
        synchronized(entries) {
            entries.remove(key)?.let { retainedChars -= footprint(key, it) }
            entries[key] = rendered
            retainedChars += footprint(key, rendered)
            val iterator = entries.entries.iterator()
            while ((entries.size > MAX_ENTRIES || retainedChars > MAX_TOTAL_CHARS) && iterator.hasNext()) {
                val eldest = iterator.next()
                retainedChars -= footprint(eldest.key, eldest.value)
                iterator.remove()
            }
        }
    }

    private fun footprint(key: MarkdownRenderCacheKey, value: RenderedMarkdown): Int =
        key.markdown.length + key.resolvedImageDestinations.sumOf { it.length } + value.html.length + value.codeBlocks.sumOf { it.length }
}

private object CommonMarkElementDecisionCache {
    private const val MAX_ENTRIES = 384
    private const val MAX_TOTAL_CHARS = 512 * 1024
    private val entries = LinkedHashMap<String, Boolean>(MAX_ENTRIES, 0.75f, true)
    private var retainedChars = 0

    fun get(markdown: String): Boolean? = synchronized(entries) {
        if (entries.containsKey(markdown)) entries[markdown] else null
    }

    fun put(markdown: String, containsElements: Boolean) {
        if (markdown.length > MAX_CACHEABLE_MARKDOWN_CHARS) return
        synchronized(entries) {
            if (entries.containsKey(markdown)) {
                entries[markdown] = containsElements
                return
            }
            entries[markdown] = containsElements
            retainedChars += markdown.length
            val iterator = entries.keys.iterator()
            while ((entries.size > MAX_ENTRIES || retainedChars > MAX_TOTAL_CHARS) && iterator.hasNext()) {
                retainedChars -= iterator.next().length
                iterator.remove()
            }
        }
    }
}

/**
 * Avoid scheduling a parser for ordinary prose. Ambiguous text still gets classified by the real
 * CommonMark AST on a worker thread; this only short-circuits strings with no syntax characters.
 */
private fun mightContainCommonMark(markdown: String): Boolean =
    markdown.any { it in "`*_~[]!<>\\&#|=" } || markdown.lineSequence().any { rawLine ->
        val line = rawLine.trimStart()
        val dotSpace = line.indexOf(". ")
        val orderedList = dotSpace > 0 && line.substring(0, dotSpace).all(Char::isDigit)
        line.startsWith("- ") || line.startsWith("+ ") || line.startsWith("* ") || line.startsWith(">") ||
            orderedList || line.trim().let { candidate ->
                candidate.length >= 3 && candidate.all { it == '-' || it == '=' || it.isWhitespace() }
            }
    }

internal fun cachedCommonMarkDecision(markdown: String): Boolean? = CommonMarkElementDecisionCache.get(markdown)

internal fun mightContainCommonMarkElements(markdown: String): Boolean = mightContainCommonMark(markdown)

@Composable
internal fun rememberCommonMarkDecision(markdown: String): State<Boolean> {
    val cached = remember(markdown) {
        CommonMarkElementDecisionCache.get(markdown) ?: if (!mightContainCommonMark(markdown)) {
            false.also { CommonMarkElementDecisionCache.put(markdown, it) }
        } else null
    }
    return produceState(initialValue = cached ?: false, markdown, cached) {
        if (cached == null) value = withContext(Dispatchers.Default) { containsCommonMarkElements(markdown) }
    }
}

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
    val clipboard = LocalClipboardManager.current
    val assets = LocalContext.current.applicationContext.assets
    val darkMode = isSystemInDarkTheme()
    val copyLabel = stringResource(R.string.common_copy)
    val webState = remember { CommonMarkWebState() }
    webState.onOpenLink = onOpenLink
    // A no-resolver document has image destinations determined by its source text; other renderings
    // include the resolver's actual output in the key after those images have been resolved.
    val sourceOnlyCacheKey = if (imageResolver == null && text.length <= MAX_CACHEABLE_MARKDOWN_CHARS) {
        MarkdownRenderCacheKey(text, colors, darkMode, copyLabel, resolvedImageDestinations = emptyList())
    } else null
    val cached = remember(sourceOnlyCacheKey) { sourceOnlyCacheKey?.let(CommonMarkRenderCache::get) }

    val rendered by produceState<RenderedMarkdown?>(cached, text, imageResolver, colors, darkMode, copyLabel, sourceOnlyCacheKey) {
        if (cached != null) {
            value = cached
        } else {
            val document = withContext(Dispatchers.Default) { commonMarkParser.parse(text) }
            val imageDestinations = rewriteDestinations(document, imageResolver)
            val resolvedImages = if (imageResolver == null) emptyList() else imageDestinations
            val cacheKey = if (text.length <= MAX_CACHEABLE_MARKDOWN_CHARS) {
                MarkdownRenderCacheKey(text, colors, darkMode, copyLabel, resolvedImages)
            } else null
            val cachedAfterImageResolution = cacheKey?.let(CommonMarkRenderCache::get)
            if (cachedAfterImageResolution != null) {
                value = cachedAfterImageResolution
            } else {
                val codeBlocks = collectMarkdownCodeBlocks(document)
                val html = withContext(Dispatchers.Default) {
                    val fragment = commonMarkHtmlRenderer.render(document)
                    val decorated = decorateMarkdownCodeBlocks(fragment, codeBlocks, copyLabel, darkMode, assets::open)
                    wrapMarkdownHtml(decorated, colors)
                }
                val result = RenderedMarkdown(html, codeBlocks.map { it.code })
                cacheKey?.let { CommonMarkRenderCache.put(it, result) }
                value = result
            }
        }
    }
    webState.onCopyCode = { index ->
        rendered?.codeBlocks?.getOrNull(index)?.let { clipboard.setText(AnnotatedString(it)) }
    }

    rendered?.let { content ->
        AndroidView(
            factory = { viewContext -> CommonMarkWebView(viewContext, webState, colors.bgBase) },
            update = { webView ->
                webView.onOpenLink = onOpenLink
                webView.onCopyCode = webState.onCopyCode
                webView.setBackgroundColor(colors.bgBase.toArgb())
                webView.render(content.html)
            },
            modifier = modifier.fillMaxWidth(),
        )
    }
}

private suspend fun rewriteDestinations(
    root: Node,
    imageResolver: (suspend (String) -> String?)?,
): List<String> {
    val imageDestinations = mutableListOf<String>()
    suspend fun visit(node: Node) {
        when (node) {
            is Image -> {
                val original = node.destination
                val resolved = imageResolver?.let { resolver -> runCatching { resolver(original) }.getOrNull() }
                if (!resolved.isNullOrBlank()) node.destination = resolved
                imageDestinations += node.destination
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
    return imageDestinations
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

internal fun decodeMarkdownCopyIndex(url: String): Int? = runCatching {
    val uri = java.net.URI(url)
    if (uri.scheme != MARKDOWN_COPY_SCHEME || uri.host != "copy") return null
    uri.rawPath?.substringAfterLast('/')?.toIntOrNull()
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

internal data class MarkdownCodeBlock(val code: String, val language: String?)
private data class RenderedMarkdown(val html: String, val codeBlocks: List<String>)

private fun collectMarkdownCodeBlocks(root: Node): List<MarkdownCodeBlock> {
    val blocks = mutableListOf<MarkdownCodeBlock>()
    var node: Node? = root.firstChild
    while (node != null) {
        when (node) {
            is FencedCodeBlock -> {
                val info = node.info?.trim().orEmpty()
                blocks += MarkdownCodeBlock(node.literal.orEmpty(), info.substringBefore(' ').takeIf(String::isNotBlank))
            }
            is IndentedCodeBlock -> blocks += MarkdownCodeBlock(node.literal.orEmpty(), null)
        }
        if (node.firstChild != null) {
            node = node.firstChild
        } else {
            while (node != null && node.next == null) node = node.parent
            node = node?.next
        }
    }
    return blocks
}

internal fun decorateMarkdownCodeBlocks(
    fragment: String,
    codeBlocks: List<MarkdownCodeBlock>,
    copyLabel: String,
    darkMode: Boolean,
    openAsset: (String) -> java.io.InputStream,
): String {
    var codeIndex = 0
    val themeAsset = if (darkMode) "textmate-dark.json" else "textmate-light.json"
    return RENDERED_CODE_BLOCK.replace(fragment) { match ->
        val block = codeBlocks.getOrNull(codeIndex) ?: return@replace match.value
        if (match.groupValues[2] != escapeMarkdownCodeText(block.code)) return@replace match.value
        val index = codeIndex++
        val highlighted = highlightFencedCode(
            block.code,
            textMateGrammarAssetForLanguage(block.language),
            themeAsset,
            openAsset,
        )
        renderMarkdownCodeBlockHtml(
            index = index,
            language = block.language,
            codeClass = match.groups[1]?.value,
            highlightedCodeHtml = annotatedCodeToHtml(highlighted),
            copyLabel = copyLabel,
        )
    }
}

internal fun renderMarkdownCodeBlockHtml(
    index: Int,
    language: String?,
    codeClass: String?,
    highlightedCodeHtml: String,
    copyLabel: String,
): String {
    val classAttribute = codeClass?.takeIf(String::isNotBlank)?.let { " class=\"${escapeMarkdownHtml(it)}\"" }.orEmpty()
    val title = escapeMarkdownHtml(language?.takeIf(String::isNotBlank) ?: "code")
    return "<div class=\"markdown-code-block\"><div class=\"markdown-code-header\"><span>$title</span>" +
        "<a class=\"markdown-copy-code\" href=\"$MARKDOWN_COPY_SCHEME://copy/$index\">${escapeMarkdownHtml(copyLabel)}</a></div>" +
        "<pre><code$classAttribute>$highlightedCodeHtml</code></pre></div>"
}

internal fun annotatedCodeToHtml(code: AnnotatedString): String {
    val styledRanges = code.spanStyles
        .filter { it.start < it.end && it.item.color != Color.Unspecified }
        .sortedBy { it.start }
    val html = StringBuilder()
    var cursor = 0
    styledRanges.forEach { range ->
        if (range.start < cursor) return@forEach
        html.append(escapeMarkdownCodeText(code.text.substring(cursor, range.start)))
        val argb = range.item.color.toArgb()
        val color = "#%06X".format(argb and 0xFFFFFF)
        html.append("<span style=\"color:$color\">")
        html.append(escapeMarkdownCodeText(code.text.substring(range.start, range.end)))
        html.append("</span>")
        cursor = range.end
    }
    html.append(escapeMarkdownCodeText(code.text.substring(cursor)))
    return html.toString()
}

private fun escapeMarkdownCodeText(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

private fun escapeMarkdownHtml(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")

private fun containsMarkdownEscapesOrEntities(markdown: String): Boolean {
    fun isAsciiPunctuation(char: Char): Boolean =
        char.code in 33..47 || char.code in 58..64 || char.code in 91..96 || char.code in 123..126

    for (index in markdown.indices) {
        if (markdown[index] == '\\' && index + 1 < markdown.length && isAsciiPunctuation(markdown[index + 1])) {
            return true
        }
        if (markdown[index] == '&') {
            val end = markdown.indexOf(';', startIndex = index + 1)
            if (end > index + 1 && end - index <= 34) {
                val entity = markdown.substring(index + 1, end)
                val validNamed = entity.first().isLetter() && entity.all { it.isLetterOrDigit() }
                val validDecimal = entity.startsWith('#') && entity.drop(1).isNotEmpty() && entity.drop(1).all(Char::isDigit)
                val validHex = entity.startsWith("#x", ignoreCase = true) && entity.drop(2).isNotEmpty() &&
                    entity.drop(2).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
                if (validNamed || validDecimal || validHex) return true
            }
        }
    }
    return false
}

/** True only when parsing finds Markdown structure or inline formatting beyond plain paragraphs. */
internal fun containsCommonMarkElements(markdown: String): Boolean {
    CommonMarkElementDecisionCache.get(markdown)?.let { return it }
    val result = if (containsMarkdownEscapesOrEntities(markdown)) {
        true
    } else {
        val document = commonMarkParser.parse(markdown)
        var paragraphCount = 0
        var containsElements = false
        var node: Node? = document.firstChild
        while (node != null && !containsElements) {
            when (node) {
                is Paragraph -> if (++paragraphCount > 1) containsElements = true
                is Text, is SoftLineBreak -> Unit
                else -> containsElements = true
            }
            if (node.firstChild != null) {
                node = node.firstChild
            } else {
                while (node != null && node.next == null) node = node.parent
                node = node?.next
            }
        }
        containsElements
    }
    CommonMarkElementDecisionCache.put(markdown, result)
    return result
}

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
          .markdown-code-block { margin: .5em 0 1em; border: 1px solid $border; border-radius: 8px; overflow: hidden; background: $background; }
          .markdown-code-header { display: flex; align-items: center; justify-content: space-between; padding: 5px 10px; border-bottom: 1px solid $border; color: $secondary; font-size: 12px; }
          .markdown-copy-code { color: $accent; text-decoration: none; font-weight: 600; }
          .markdown-code-block pre { margin: 0; border: 0; border-radius: 0; }
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
    var onCopyCode: (Int) -> Unit = {}
}

private class CommonMarkWebView(
    context: android.content.Context,
    private val state: CommonMarkWebState,
    background: Color,
) : WebView(context) {
    var onOpenLink: (String) -> Unit
        get() = state.onOpenLink
        set(value) { state.onOpenLink = value }

    var onCopyCode: (Int) -> Unit
        get() = state.onCopyCode
        set(value) { state.onCopyCode = value }

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
        decodeMarkdownCopyIndex(url)?.let { index ->
            runCatching { onCopyCode(index) }
            return true
        }
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
