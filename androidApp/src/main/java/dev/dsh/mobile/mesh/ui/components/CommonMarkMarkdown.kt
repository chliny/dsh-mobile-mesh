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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.ui.theme.DsColors
import dev.dsh.mobile.mesh.ui.theme.DsTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
import kotlin.math.ceil

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
// Soft breaks must be visible in tight list items and table cells too, not only in paragraphs
// (which happen to use pre-wrap in the WebView stylesheet).
private val commonMarkHtmlRenderer = HtmlRenderer.builder()
    .extensions(commonMarkExtensions)
    .softbreak("<br />")
    .build()

private const val MAX_CACHEABLE_MARKDOWN_CHARS = 64 * 1024
private const val MAX_MARKDOWN_IMAGES_PER_DOCUMENT = 8
private const val MAX_MARKDOWN_IMAGE_DOCUMENT_CHARS = 16_000_000

/** Shares in-flight image reads across streaming revisions and cancels them when the message leaves composition. */
private class MarkdownImageResolutionCache(
    private val scope: CoroutineScope,
    private val resolver: suspend (String) -> String?,
) {
    private val mutex = Mutex()
    private val images = LinkedHashMap<String, String>(MAX_MARKDOWN_IMAGES_PER_DOCUMENT, 0.75f, true)
    private val pending = mutableMapOf<String, Deferred<String?>>()
    private var cachedChars = 0

    suspend fun resolve(source: String): String? {
        val request = mutex.withLock {
            images[source]?.let { return it }
            pending.getOrPut(source) {
                scope.async(Dispatchers.IO) {
                    try {
                        val resolved = try { withTimeoutOrNull(5_000) { resolver(source) } }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { null }
                        mutex.withLock {
                            if (!resolved.isNullOrBlank() && resolved.length <= 3_000_000) {
                                images[source] = resolved
                                cachedChars += resolved.length
                                while (images.size > MAX_MARKDOWN_IMAGES_PER_DOCUMENT || cachedChars > MAX_MARKDOWN_IMAGE_DOCUMENT_CHARS) {
                                    val oldest = images.entries.iterator()
                                    cachedChars -= oldest.next().value.length
                                    oldest.remove()
                                }
                            }
                        }
                        resolved
                    } finally {
                        withContext(NonCancellable) { mutex.withLock { pending.remove(source) } }
                    }
                }
            }
        }
        return request.await()
    }
}

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
        if (key.markdown.length > MAX_CACHEABLE_MARKDOWN_CHARS || rendered.parts.sumOf { it.htmlLength } > MAX_CACHEABLE_HTML_CHARS) return
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
        key.markdown.length + key.resolvedImageDestinations.sumOf { it.length } +
            value.parts.sumOf { it.htmlLength } + value.codeBlocks.sumOf { it.length }
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
internal fun rememberCommonMarkDecision(markdown: String): State<Boolean?> {
    val cached = remember(markdown) {
        CommonMarkElementDecisionCache.get(markdown) ?: if (!mightContainCommonMark(markdown)) {
            false.also { CommonMarkElementDecisionCache.put(markdown, it) }
        } else null
    }
    return produceState<Boolean?>(initialValue = cached, markdown, cached) {
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
    val imageScope = rememberCoroutineScope()
    val imageCache = remember(imageResolver, imageScope) {
        imageResolver?.let { MarkdownImageResolutionCache(imageScope, it) }
    }
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
            value = withContext(Dispatchers.Default) {
                val document = commonMarkParser.parse(normalizeStrongDelimiterWhitespace(text))
                val imageDestinations = rewriteDestinations(document, imageCache)
                val resolvedImages = if (imageResolver == null) emptyList() else imageDestinations
                val cacheKey = if (text.length <= MAX_CACHEABLE_MARKDOWN_CHARS) {
                    MarkdownRenderCacheKey(text, colors, darkMode, copyLabel, resolvedImages)
                } else null
                val cachedAfterImageResolution = cacheKey?.let(CommonMarkRenderCache::get)
                if (cachedAfterImageResolution != null) {
                    cachedAfterImageResolution
                } else {
                    val codeBlocks = collectMarkdownCodeBlocks(document)
                    val parts = renderMarkdownParts(document, colors, copyLabel, darkMode, assets::open)
                    val result = RenderedMarkdown(parts, codeBlocks.map { it.code })
                    cacheKey?.let { CommonMarkRenderCache.put(it, result) }
                    result
                }
            }
        }
    }
    webState.onCopyCode = { index ->
        rendered?.codeBlocks?.getOrNull(index)?.let { clipboard.setText(AnnotatedString(it)) }
    }

    val content = rendered
    if (content == null) {
        MarkdownLoadingPreview(text, modifier)
    } else {
        androidx.compose.foundation.layout.Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content.parts.forEach { part ->
                when (part) {
                    is MarkdownPart.Html -> AndroidView(
                        factory = { viewContext -> CommonMarkWebView(viewContext, webState) },
                        update = { webView ->
                            webView.bind(webState)
                            webView.onOpenLink = onOpenLink
                            webView.onCopyCode = webState.onCopyCode
                            webView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            webView.render(part)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        // Reusing a WebView in LazyColumn can measure the next message with the
                        // previous document's height and leave a blank band while it loads.
                        onRelease = { webView -> webView.release() },
                    )
                    is MarkdownPart.Code -> MarkdownCodeBlockCard(
                        code = part.block.code,
                        language = part.block.language,
                        copyLabel = copyLabel,
                        onCopy = { clipboard.setText(AnnotatedString(part.block.code)) },
                        textMate = rememberTextMateHighlighterForLanguage(part.block.language ?: "text"),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

private suspend fun rewriteDestinations(
    root: Node,
    imageCache: MarkdownImageResolutionCache?,
): List<String> {
    val imageDestinations = mutableListOf<String>()
    val distinctLocalImages = mutableSetOf<String>()
    var resolvedChars = 0
    suspend fun visit(node: Node) {
        currentCoroutineContext().ensureActive()
        when (node) {
            is Image -> {
                val original = node.destination
                val local = !original.startsWith("http://", ignoreCase = true) &&
                    !original.startsWith("https://", ignoreCase = true) &&
                    !original.startsWith("data:", ignoreCase = true)
                val allowed = !local || original in distinctLocalImages ||
                    distinctLocalImages.size < MAX_MARKDOWN_IMAGES_PER_DOCUMENT
                if (local && allowed) distinctLocalImages += original
                val resolved = if (imageCache != null && allowed && resolvedChars < MAX_MARKDOWN_IMAGE_DOCUMENT_CHARS) {
                    try { imageCache.resolve(original) } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) { null }
                } else null
                if (!resolved.isNullOrBlank() && resolvedChars + resolved.length <= MAX_MARKDOWN_IMAGE_DOCUMENT_CHARS) {
                    node.destination = resolved
                    resolvedChars += resolved.length
                }
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

private fun isMarkdownDocumentHost(host: String?): Boolean = host == "markdown.invalid" ||
    host?.matches(Regex("markdown-[0-9]+\\.invalid")) == true

internal fun relativeMarkdownNavigationTarget(url: String): String? = runCatching {
    val uri = java.net.URI(url)
    if (uri.scheme != "https" || !isMarkdownDocumentHost(uri.host)) return null
    if (uri.path == "/" && uri.rawQuery == null && uri.rawFragment != null) return null
    buildString {
        append(uri.path.orEmpty().removePrefix("/"))
        uri.rawQuery?.let { append('?').append(it) }
        uri.rawFragment?.let { append('#').append(it) }
    }.takeIf(String::isNotBlank)
}.getOrNull()

/** Parse and render one fragment without the WebView document wrapper (also used by conformance tests). */
internal fun renderCommonMarkFragment(markdown: String): String =
    commonMarkHtmlRenderer.render(commonMarkParser.parse(normalizeStrongDelimiterWhitespace(markdown)))

/** Be forgiving of a space before a closing strong delimiter, common in generated CJK prose. */
private fun normalizeStrongDelimiterWhitespace(markdown: String): String =
    markdown.replace(Regex("(?<!\\\\)\\*\\*([^*\\n]+?)[ \\t]+\\*\\*"), "<strong>$1</strong>")

internal data class MarkdownCodeBlock(val code: String, val language: String?)
internal sealed interface MarkdownPart {
    val htmlLength: Int
    data class Html(val html: String) : MarkdownPart {
        override val htmlLength: Int get() = html.length
        // Retained with the bounded render cache, not with the disposable WebView. Reopening the
        // same document must not temporarily shrink a long message to one pixel and clamp its
        // saved reading offset. Width, density and font scale must match before reusing a height.
        var measuredLayout: Pair<Triple<Int, Int, Float>, Int>? = null
    }
    data class Code(val block: MarkdownCodeBlock) : MarkdownPart { override val htmlLength: Int get() = block.code.length }
}
private data class RenderedMarkdown(val parts: List<MarkdownPart>, val codeBlocks: List<String>)

internal fun renderMarkdownPartsForTest(
    markdown: String,
    colors: DsColors,
    copyLabel: String,
    darkMode: Boolean,
    openAsset: (String) -> java.io.InputStream,
): List<MarkdownPart> = renderMarkdownParts(
    commonMarkParser.parse(normalizeStrongDelimiterWhitespace(markdown)),
    colors,
    copyLabel,
    darkMode,
    openAsset,
)

private fun renderMarkdownParts(
    document: Node,
    colors: DsColors,
    copyLabel: String,
    darkMode: Boolean,
    openAsset: (String) -> java.io.InputStream,
): List<MarkdownPart> {
    val parts = mutableListOf<MarkdownPart>()
    val htmlFragment = StringBuilder()
    fun flushHtml() {
        if (htmlFragment.isNotEmpty()) {
            parts += MarkdownPart.Html(wrapMarkdownHtml(htmlFragment.toString(), colors))
            htmlFragment.clear()
        }
    }
    var codeIndex = 0
    var node = document.firstChild
    while (node != null) {
        val directCode = when (node) {
            is FencedCodeBlock -> MarkdownCodeBlock(
                node.literal.orEmpty(), node.info?.trim()?.substringBefore(' ')?.takeIf(String::isNotBlank),
            )
            is IndentedCodeBlock -> MarkdownCodeBlock(node.literal.orEmpty(), null)
            else -> null
        }
        if (directCode != null) {
            flushHtml()
            parts += MarkdownPart.Code(directCode)
            codeIndex++
        } else {
            val nestedBlocks = collectMarkdownCodeBlocks(node)
            var fragment = commonMarkHtmlRenderer.render(node)
            if (nestedBlocks.isNotEmpty()) {
                fragment = decorateMarkdownCodeBlocks(
                    fragment, nestedBlocks, copyLabel, darkMode, openAsset, startIndex = codeIndex,
                )
                codeIndex += nestedBlocks.size
            }
            htmlFragment.append(fragment)
        }
        node = node.next
    }
    flushHtml()
    return parts
}

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
    startIndex: Int = 0,
): String {
    var codeIndex = startIndex
    val themeAsset = if (darkMode) "textmate-dark.json" else "textmate-light.json"
    return RENDERED_CODE_BLOCK.replace(fragment) { match ->
        val block = codeBlocks.getOrNull(codeIndex) ?: return@replace match.value
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
        val document = commonMarkParser.parse(normalizeStrongDelimiterWhitespace(markdown))
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
    val text = css(colors.labelPrimary)
    val secondary = css(colors.labelSecondary)
    val border = css(colors.borderL1)
    val accent = css(colors.accent)
    val codeBackground = css(colors.inlineCode)
    val panelBackground = css(colors.codeBlockBg)
    val terminalBackground = css(colors.bgModulePlatform)
    return """
        <!doctype html>
        <html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=5">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data: https: http:; style-src 'unsafe-inline'; font-src data: https:;">
        <style>
          * { box-sizing: border-box; }
          html, body { margin: 0; padding: 0; background: transparent; color: $text; }
          body { font-family: sans-serif; font-size: 16px; line-height: 1.5; overflow-wrap: anywhere; }
          p { margin: 0 0 1em; white-space: normal; }
          p:last-child, ul:last-child, ol:last-child, blockquote:last-child, pre:last-child, table:last-child { margin-bottom: 0; }
          h1, h2, h3, h4, h5, h6 { color: $text; line-height: 1.25; margin: 1em 0 .45em; }
          h1 { font-size: 1.8em; } h2 { font-size: 1.55em; } h3 { font-size: 1.3em; }
          h4 { font-size: 1.15em; } h5 { font-size: 1.05em; } h6 { font-size: 1em; }
          ul, ol { padding-left: 1.5em; margin: .35em 0 1em; }
          li { margin: .2em 0; }
          blockquote { color: $secondary; border-left: 3px solid $border; margin: .5em 0; padding: .1em 0 .1em .9em; }
          a { color: $accent; text-decoration: underline; }
          code { font-family: monospace; background: $codeBackground; padding: .08em .25em; border-radius: 3px; }
          pre { white-space: pre; overflow-x: auto; background: $terminalBackground; border: 1px solid $border; border-radius: 10px; padding: 10px 12px; margin: .5em 0 1em; }
          pre code { background: transparent; padding: 0; border-radius: 0; }
          .markdown-code-block { margin: .5em 0 1em; padding: 10px; border: 1px solid $border; border-radius: 12px; overflow: hidden; background: $panelBackground; }
          .markdown-code-header { display: flex; align-items: center; justify-content: space-between; min-height: 28px; padding: 0 2px 8px; color: $secondary; font-size: 12px; }
           .markdown-code-header span { font-family: monospace; font-weight: 600; letter-spacing: .02em; }
          .markdown-copy-code { color: $accent; text-decoration: none; font-weight: 600; padding: 2px 7px; border: 1px solid $border; border-radius: 6px; }
          .markdown-code-block pre { margin: 0; border: 1px solid $border; border-radius: 8px; padding: 10px 12px; }
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

internal fun markdownWebViewContentHeight(contentHeightCssPx: Int, scale: Float): Int =
    ceil(contentHeightCssPx * scale).toInt().coerceAtLeast(1)

/** A new document must never inherit the intrinsic height of the previous HTML page. */
internal fun markdownWebViewMeasuredHeight(contentHeightCssPx: Int, scale: Float, documentReady: Boolean): Int =
    if (documentReady) markdownWebViewContentHeight(contentHeightCssPx, scale) else 1

internal fun canPostMarkdownVisualStateCallback(released: Boolean): Boolean = !released

private class CommonMarkWebView(
    context: android.content.Context,
    private var state: CommonMarkWebState,
) : WebView(context) {
    fun bind(state: CommonMarkWebState) {
        this.state = state
    }

    private var released = false

    fun release() {
        if (released) return
        released = true
        stopLoading()
        destroy()
    }

    var onOpenLink: (String) -> Unit
        get() = state.onOpenLink
        set(value) { state.onOpenLink = value }

    var onCopyCode: (Int) -> Unit
        get() = state.onCopyCode
        set(value) { state.onCopyCode = value }

    private var loadedHtml: String? = null
    private var document: MarkdownPart.Html? = null
    private var documentReady = false
    private var documentGeneration = 0L
    private var documentBaseUrl: String? = null

    init {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
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
                // WebView callbacks do not carry a load ID. Give every document a unique origin
                // and only accept a completion whose URL still belongs to the current document;
                // an old page may finish after render() has already advanced the generation.
                val baseUrl = documentBaseUrl ?: return
                if (url != baseUrl) return
                val generation = documentGeneration
                // onPageFinished can precede WebView's committed visual/layout state. Re-measure only
                // after Chromium confirms the document is ready to draw, then invalidate its parent.
                view.post {
                    if (!canPostMarkdownVisualStateCallback(released) || generation != documentGeneration) {
                        return@post
                    }
                    // A queued WebView task may outlive Compose's disposal. Chromium can also destroy
                    // its renderer independently, so do not let that lifecycle race crash the app.
                    runCatching {
                        view.postVisualStateCallback(System.nanoTime(), object : WebView.VisualStateCallback() {
                            override fun onComplete(requestId: Long) {
                                if (!canPostMarkdownVisualStateCallback(released) || generation != documentGeneration) return
                                documentReady = true
                                view.requestLayout()
                                view.invalidate()
                            }
                        })
                    }
                }
            }
        }
    }

    fun render(part: MarkdownPart.Html) {
        document = part
        val html = part.html
        if (loadedHtml == html) return
        loadedHtml = html
        documentGeneration++
        documentBaseUrl = MARKDOWN_BASE_URL.replace(
            "markdown.invalid", "markdown-$documentGeneration.invalid",
        )
        documentReady = false
        requestLayout()
        loadDataWithBaseURL(documentBaseUrl, html, "text/html", "UTF-8", null)
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
        if (isMarkdownDocumentHost(uri.host) && uri.fragment != null && uri.encodedPath.orEmpty() == "/") return false
        val target = relativeMarkdownNavigationTarget(url) ?: url
        runCatching { onOpenLink(target) }
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val width = resolveSize(measuredWidth, widthMeasureSpec)
        val layoutKey = Triple(width, resources.displayMetrics.densityDpi, resources.configuration.fontScale)
        val height = if (documentReady) {
            markdownWebViewMeasuredHeight(contentHeight, scale, true).also {
                document?.measuredLayout = layoutKey to it
            }
        } else {
            document?.measuredLayout?.takeIf { it.first == layoutKey }?.second ?: 1
        }
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec))
    }
}

/** Clean a Markdown image path before resolving it relative to a workspace document. */
internal fun markdownImagePath(source: String): String? {
    val cleaned = source.trim().trim('<', '>')
    if (cleaned.startsWith("http://", ignoreCase = true) || cleaned.startsWith("https://", ignoreCase = true) ||
        cleaned.startsWith("data:", ignoreCase = true)) return cleaned.takeIf { it.isNotBlank() }
    return cleaned.substringBefore('#').substringBefore('?').takeIf { it.isNotBlank() }
}
