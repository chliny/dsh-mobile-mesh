package dev.dsh.mobile.mesh.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.utils.MARKDOWN_TAG_IMAGE_URL
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import com.mikepenz.markdown.model.ReferenceLinkHandler
import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.ui.theme.DsColors
import dev.dsh.mobile.mesh.ui.theme.DsTheme
import dev.dsh.mobile.mesh.ui.theme.DsType
import kotlinx.coroutines.delay
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/** Re-parse cadence while an answer is still streaming; faster than this only re-renders the same partial tree. */
private const val MARKDOWN_STREAM_REFRESH_MS = 120L

/**
 * The Markdown parser can leave strong delimiters literal when a CJK punctuation mark immediately
 * precedes the closing `**`/`__` and CJK text follows it. A word-joiner inside the strong span
 * makes the delimiter unambiguous without introducing a visible space. Fenced and inline code are
 * kept untouched.
 */
private val INLINE_CODE_SPAN_IN_MARKDOWN = Regex("(`+)[^`]*\\1")
private val CJK_STRONG_PUNCTUATION_BOUNDARY = Regex(
    """(\*\*[^*\n]*\p{P})(\*\*)(?=[\p{IsHan}\p{IsHiragana}\p{IsKatakana}\p{IsHangul}])|(__[^_\n]*\p{P})(__)(?=[\p{IsHan}\p{IsHiragana}\p{IsKatakana}\p{IsHangul}])""",
)

private fun normalizeCjkStrongBoundaries(text: String): String {
    if (!text.contains("**") && !text.contains("__")) return text
    val out = StringBuilder(text.length + 8)
    var inFence = false
    var fenceMarker = ""
    text.lineSequence().forEachIndexed { index, line ->
        if (index > 0) out.append('\n')
        val trimmed = line.trimStart()
        if (inFence) {
            out.append(line)
            if (trimmed.startsWith(fenceMarker)) inFence = false
            return@forEachIndexed
        }
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            inFence = true
            fenceMarker = trimmed.take(3)
            out.append(line)
            return@forEachIndexed
        }
        var cursor = 0
        INLINE_CODE_SPAN_IN_MARKDOWN.findAll(line).forEach { match ->
            out.append(normalizeCjkStrongFragment(line.substring(cursor, match.range.first)))
            out.append(match.value)
            cursor = match.range.last + 1
        }
        out.append(normalizeCjkStrongFragment(line.substring(cursor)))
    }
    return out.toString()
}

private fun normalizeCjkStrongFragment(fragment: String): String =
    CJK_STRONG_PUNCTUATION_BOUNDARY.replace(fragment) { match ->
        val strongText = match.groupValues[1]
        if (strongText.isNotEmpty()) {
            "$strongText\u2060${match.groupValues[2]}"
        } else {
            "${match.groupValues[3]}\u2060${match.groupValues[4]}"
        }
    }

/**
 * Shared Markdown renderer for transcript content and workspace Markdown files.
 *
 * Rendering is pure Compose (mikepenz/multiplatform-markdown-renderer). Marked-up answers used to be
 * handed to a WebView, which consumed taps, measured its own height asynchronously and had to be kept
 * out of the streaming path; none of that applies to Compose text, so the renderer is the same for a
 * live answer and a settled one.
 *
 * @param imageResolver resolves a workspace-relative image destination to a displayable URL.
 * @param imageCacheScope keeps identically named workspace images from different workspaces apart.
 * @param onOpenLink routes a link click; when null the platform URI handler opens it.
 * @param streaming throttles re-parsing of an answer that is still being written.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    imageResolver: (suspend (String) -> String?)? = null,
    imageCacheScope: String? = null,
    onOpenLink: ((String) -> Unit)? = null,
    streaming: Boolean = false,
) {
    if (text.isBlank()) return
    val colors = DsTheme.colors
    val baseUriHandler = LocalUriHandler.current
    val linkHandler = remember(onOpenLink, baseUriHandler) {
        object : UriHandler {
            override fun openUri(uri: String) {
                // A link the app cannot route must not crash the transcript; the WebView renderer
                // swallowed the equivalent failure itself.
                runCatching {
                    if (onOpenLink != null) onOpenLink(uri) else baseUriHandler.openUri(uri)
                }
            }
        }
    }
    val prepared = remember(text) { normalizeCjkStrongBoundaries(markdownHtmlAsMarkdown(text)) }
    val content = rememberStreamingMarkdown(prepared, streaming)
    // An inline image that cannot be read must fall back to its caption instead of reserving a
    // blank image slot; the annotator below swaps in the alt text once the load reports failure.
    val unavailableImages = remember { mutableStateMapOf<String, Boolean>() }
    val imageTransformer = remember(imageResolver, imageCacheScope, unavailableImages) {
        MarkdownImageTransformer(imageResolver, imageCacheScope) { source ->
            if (source !in unavailableImages) unavailableImages[source] = true
        }
    }
    val imageAnnotator = remember(unavailableImages) {
        markdownAnnotator { markdownContent, child ->
            if (child.type != MarkdownElementTypes.IMAGE) return@markdownAnnotator false
            val destination = child.findFirstDescendant(MarkdownElementTypes.LINK_DESTINATION)
                ?.getUnescapedTextInNode(markdownContent) ?: return@markdownAnnotator false
            if (unavailableImages.containsKey(destination)) {
                append(child.markdownImageAltText(markdownContent))
            } else {
                appendInlineContent(MARKDOWN_TAG_IMAGE_URL, destination)
            }
            true
        }
    }
    val markdownColors = markdownColor(
        text = colors.labelPrimary,
        codeBackground = colors.codeBlockBg,
        inlineCodeBackground = colors.inlineCode,
        dividerColor = colors.borderL1,
        tableBackground = colors.bgModulePlatform,
    )
    val markdownTypography = markdownTypography(
        h1 = DsType.mdH1.copy(color = colors.labelPrimary),
        h2 = DsType.mdH2.copy(color = colors.labelPrimary),
        h3 = DsType.mdH3.copy(color = colors.labelPrimary),
        h4 = DsType.mdH4.copy(color = colors.labelPrimary),
        h5 = DsType.mdH4.copy(color = colors.labelPrimary),
        h6 = DsType.mdH4.copy(color = colors.labelPrimary),
        text = DsType.mdBody.copy(color = colors.labelPrimary),
        code = DsType.mdCode.copy(color = colors.labelPrimary),
        inlineCode = DsType.mdBody.copy(color = colors.labelPrimary, fontFamily = DsType.codeFont),
        quote = DsType.mdBody.copy(color = colors.labelSecondary),
        paragraph = DsType.mdBody.copy(color = colors.labelPrimary),
        ordered = DsType.mdBody.copy(color = colors.labelPrimary),
        bullet = DsType.mdBody.copy(color = colors.labelPrimary),
        list = DsType.mdBody.copy(color = colors.labelPrimary),
        link = DsType.mdBody.copy(color = colors.accent, textDecoration = TextDecoration.Underline),
        textLink = TextLinkStyles(
            style = SpanStyle(
                color = colors.accent,
                textDecoration = TextDecoration.Underline,
                fontWeight = FontWeight.Normal,
            ),
        ),
        table = DsType.mdBody.copy(color = colors.labelPrimary),
    )
    val copyLabel = stringResource(R.string.common_copy)
    val components = remember(copyLabel) {
        markdownComponents(
            codeFence = { model ->
                MarkdownCodeFence(model.content, model.node, model.typography.code) { code, language, _ ->
                    MarkdownCodeCard(code = code, language = language, copyLabel = copyLabel)
                }
            },
            codeBlock = { model ->
                MarkdownCodeBlock(model.content, model.node, model.typography.code) { code, language, _ ->
                    MarkdownCodeCard(code = code, language = language, copyLabel = copyLabel)
                }
            },
            image = { model -> MarkdownImageWithCaption(model) },
            table = { model ->
                // The default table truncates every cell to one line. Transcripts and READMEs carry
                // wide explanatory cells, so wrap them and let the table scroll horizontally.
                MarkdownTable(
                    model.content,
                    model.node,
                    model.typography.table,
                    headerBlock = { tableContent, header, width, style ->
                        MarkdownTableHeader(tableContent, header, width, style, maxLines = Int.MAX_VALUE)
                    },
                    rowBlock = { tableContent, row, width, style ->
                        MarkdownTableRow(tableContent, row, width, style, maxLines = Int.MAX_VALUE)
                    },
                )
            },
        )
    }

    // Parsing happens in the composition, not a frame later: a row that changes height after the
    // reader has been positioned is what makes a restored transcript jump to the wrong place. A
    // bounded cache makes scrolling a previously-read message free, and the streaming path
    // throttles how often a growing answer is re-parsed.
    val parsedState = remember(content, streaming) {
        MarkdownStateCache.getOrParse(content, cacheable = !streaming)
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalUriHandler provides linkHandler) {
        Markdown(
            state = parsedState,
            colors = markdownColors,
            typography = markdownTypography,
            modifier = modifier.fillMaxWidth(),
            padding = markdownPadding(
                block = 4.dp,
                list = 4.dp,
                listItemTop = 2.dp,
                listItemBottom = 2.dp,
                listIndent = 12.dp,
            ),
            dimens = markdownDimens(tableCellPadding = 10.dp),
            imageTransformer = imageTransformer,
            annotator = imageAnnotator,
            components = components,
            loading = { loadingModifier ->
                MarkdownPlainFallback(markdownLoadingPreviewText(content), colors, loadingModifier)
            },
            error = { errorModifier ->
                MarkdownPlainFallback(markdownLoadingPreviewText(content), colors, errorModifier)
            },
        )
    }
}

/**
 * Bounded process cache of parsed Markdown.
 *
 * Parsing is deterministic for a given document, and a transcript row that leaves and re-enters the
 * viewport would otherwise be parsed again. The entry and character bounds keep an unusually large
 * answer from retaining memory; a streaming answer is not cached because every partial revision
 * would evict useful entries.
 */
private object MarkdownStateCache {
    private const val MAX_ENTRIES = 48
    private const val MAX_CACHEABLE_CHARS = 64 * 1024
    private const val MAX_TOTAL_CHARS = 512 * 1024

    private val flavour = GFMFlavourDescriptor()
    private val parser = MarkdownParser(flavour)
    private val entries = LinkedHashMap<String, State.Success>(MAX_ENTRIES, 0.75f, true)

    fun getOrParse(content: String, cacheable: Boolean): State {
        synchronized(entries) {
            entries[content]?.let { return it }
        }
        val parsed = parse(content)
        if (parsed !is State.Success) return parsed
        if (cacheable && content.length <= MAX_CACHEABLE_CHARS) {
            synchronized(entries) {
                entries[content] = parsed
                while (entries.size > MAX_ENTRIES || entries.values.sumOf { it.content.length } > MAX_TOTAL_CHARS) {
                    val eldest = entries.entries.iterator()
                    if (!eldest.hasNext()) break
                    eldest.next()
                    eldest.remove()
                }
            }
        }
        return parsed
    }

    private fun parse(content: String): State = try {
        val node = parser.buildMarkdownTreeFromString(content)
        val links = ReferenceLinkHandlerImpl()
        storeLinkDefinitions(node, content, links)
        State.Success(node = node, content = content, linksLookedUp = true, referenceLinkHandler = links)
    } catch (error: Throwable) {
        // A renderer must never take the transcript down; the caller shows the plain fallback.
        State.Error(error)
    }

    /** Reference-style links resolve through the handler the same way the library's own parse does. */
    private fun storeLinkDefinitions(node: ASTNode, content: String, links: ReferenceLinkHandler) {
        when (node.type) {
            MarkdownElementTypes.LINK_DEFINITION -> {
                val label = node.findChildOfType(MarkdownElementTypes.LINK_LABEL)?.getUnescapedTextInNode(content)
                val destination = node.findChildOfType(MarkdownElementTypes.LINK_DESTINATION)?.getUnescapedTextInNode(content)
                if (label != null) links.store(label, destination)
            }
            MarkdownElementTypes.INLINE_LINK -> {
                val label = node.findChildOfType(MarkdownElementTypes.LINK_TEXT)?.getUnescapedTextInNode(content)
                val destination = node.findChildOfType(MarkdownElementTypes.LINK_DESTINATION)?.getUnescapedTextInNode(content)
                if (label != null) links.store(label, destination)
            }
        }
        node.children.forEach { child -> storeLinkDefinitions(child, content, links) }
    }
}

@Composable
private fun MarkdownCodeCard(code: String, language: String?, copyLabel: String) {
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    MarkdownCodeBlockCard(
        code = code,
        language = language,
        copyLabel = copyLabel,
        onCopy = { clipboard.setText(AnnotatedString(code)) },
        textMate = rememberTextMateHighlighterForLanguage(language ?: "text"),
    )
}

/**
 * While an answer streams, each update would otherwise re-parse the whole accumulated document.
 * Sampling the live text keeps the rendered answer moving without paying that cost per token.
 */
@Composable
private fun rememberStreamingMarkdown(text: String, streaming: Boolean): String {
    if (!streaming) return text
    val latest by rememberUpdatedState(text)
    val throttled = remember { mutableStateOf(text) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(MARKDOWN_STREAM_REFRESH_MS)
            throttled.value = latest
        }
    }
    return throttled.value
}

/** Bounded plain-text preview shown for the first parse of a message. */
@Composable
private fun MarkdownPlainFallback(text: String, colors: DsColors, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.fillMaxWidth(),
        style = DsType.mdBody.copy(color = colors.labelPrimary),
        maxLines = 12,
        overflow = TextOverflow.Ellipsis,
    )
}

internal const val MARKDOWN_LOADING_PREVIEW_LIMIT = 8_192

internal fun markdownLoadingPreviewText(text: String): String =
    if (text.length > MARKDOWN_LOADING_PREVIEW_LIMIT) text.take(MARKDOWN_LOADING_PREVIEW_LIMIT) + "…" else text
