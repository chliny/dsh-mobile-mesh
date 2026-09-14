package dev.dsh.mobile.mesh.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.WebView
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.mesh.ui.theme.DsColors
import dev.dsh.mobile.mesh.ui.theme.DsShapes
import dev.dsh.mobile.mesh.ui.theme.DsTheme
import dev.dsh.mobile.mesh.ui.theme.DsType
import dev.dsh.mobile.mesh.ui.theme.DshTheme

/**
 * Block-level Markdown renderer: fenced code blocks, #-#### headings, bullet and
 * ordered lists, blockquotes, and paragraphs with inline **bold**, *italic*,
 * `code` chips and [links](https://example.com). Tables render as plain text.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    imageResolver: (suspend (String) -> String?)? = null,
) {
    val colors = DsTheme.colors
    // `remember` covers a composed row, while this bounded process cache also covers LazyColumn
    // disposal/recomposition when older transcript rows leave and re-enter the viewport.
    val blocks = remember(text) { MarkdownParseCache.getOrParse(text) }
    SelectionContainer {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Image -> ResolvedMarkdownImage(block.source, imageResolver)
                is MdBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> DsType.mdH1
                        2 -> DsType.mdH2
                        3 -> DsType.mdH3
                        else -> DsType.mdH4
                    }
                    InlineMarkdown(block.text, style.copy(color = colors.labelPrimary), Modifier.padding(top = 10.dp))
                }
                is MdBlock.Paragraph -> InlineMarkdown(
                    block.lines.joinToString(" "),
                    DsType.mdBody.copy(color = colors.labelPrimary),
                    Modifier.fillMaxWidth(),
                )
                is MdBlock.MdList -> MdListBlock(block)
                is MdBlock.Blockquote -> MdBlockquote(block)
                is MdBlock.Code -> CodeBlock(block.lang, block.code)
                is MdBlock.Table -> block.rows.forEach { row ->
                    InlineMarkdown(row, DsType.mdSmall.copy(color = colors.labelTertiary), Modifier.fillMaxWidth())
                }
            }
            }
        }
    }
}

// ---- Parser (deterministic, line-based) ------------------------------------

private val HEADING_REGEX = Regex("^(#{1,4})\\s+(.*)$")
internal val IMAGE_REGEX = Regex("^!\\[([^]]*)]\\(([^)]+)\\)$")
internal val HTML_IMAGE_REGEX = Regex("<img\\s+[^>]*src=[\\\"']([^\\\"']+)[\\\"'][^>]*>", RegexOption.IGNORE_CASE)
private val ORDERED_REGEX = Regex("^\\d+\\.\\s+")

private sealed interface MdBlock {
    data class Paragraph(val lines: List<String>) : MdBlock
    data class Image(val alt: String, val source: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class MdList(val items: List<String>, val ordered: Boolean) : MdBlock
    data class Blockquote(val lines: List<String>) : MdBlock
    data class Code(val lang: String?, val code: String) : MdBlock
    data class Table(val rows: List<String>) : MdBlock
}

/**
 * Small LRU cache shared by transcript rows. Markdown content is immutable once a durable chat
 * event lands, whereas LazyColumn disposes off-screen rows and would otherwise parse it again.
 * The entry/count and text-size bounds keep unusually large command output from retaining memory.
 */
private object MarkdownParseCache {
    private const val MAX_ENTRIES = 160
    private const val MAX_CACHEABLE_CHARS = 64 * 1024
    private val entries = object : LinkedHashMap<String, List<MdBlock>>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MdBlock>>?): Boolean =
            size > MAX_ENTRIES
    }

    fun getOrParse(markdown: String): List<MdBlock> {
        if (markdown.length > MAX_CACHEABLE_CHARS) return parseMarkdown(markdown)
        synchronized(entries) {
            entries[markdown]?.let { return it }
        }
        val parsed = parseMarkdown(markdown)
        synchronized(entries) {
            return entries[markdown] ?: parsed.also { entries[markdown] = it }
        }
    }
}

private fun parseMarkdown(markdown: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = markdown.replace("\r\n", "\n").split("\n")
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("```") -> {
                val lang = trimmed.removePrefix("```").trim().ifEmpty { null }
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    code.append(lines[i]).append('\n')
                    i++
                }
                i++ // skip closing fence
                blocks += MdBlock.Code(lang, code.toString().trimEnd('\n'))
            }
            IMAGE_REGEX.matches(trimmed) -> {
                val match = IMAGE_REGEX.matchEntire(trimmed)!!
                blocks += MdBlock.Image(match.groupValues[1], match.groupValues[2])
                i++
            }
            HTML_IMAGE_REGEX.containsMatchIn(trimmed) -> {
                HTML_IMAGE_REGEX.findAll(trimmed).forEach { match ->
                    blocks += MdBlock.Image("", match.groupValues[1])
                }
                i++
            }
            HEADING_REGEX.matches(trimmed) -> {
                val match = HEADING_REGEX.matchEntire(trimmed)!!
                val level = match.groupValues[1].length
                val text = match.groupValues[2].trim().trimEnd('#').trim()
                blocks += MdBlock.Heading(level, text)
                i++
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                val items = mutableListOf<String>()
                while (i < lines.size) {
                    val t = lines[i].trimStart()
                    if (!t.startsWith("- ") && !t.startsWith("* ")) break
                    items += t.removePrefix("- ").removePrefix("* ").trim()
                    i++
                }
                blocks += MdBlock.MdList(items, ordered = false)
            }
            ORDERED_REGEX.containsMatchIn(trimmed) -> {
                val items = mutableListOf<String>()
                while (i < lines.size && ORDERED_REGEX.containsMatchIn(lines[i].trimStart())) {
                    items += ORDERED_REGEX.replace(lines[i].trim(), "").trim()
                    i++
                }
                blocks += MdBlock.MdList(items, ordered = true)
            }
            trimmed.startsWith(">") -> {
                val quote = mutableListOf<String>()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    quote += lines[i].trim().removePrefix(">").trim()
                    i++
                }
                blocks += MdBlock.Blockquote(quote)
            }
            trimmed.startsWith("|") -> {
                val rows = mutableListOf<String>()
                while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                    if (!isTableSeparator(lines[i])) rows += lines[i]
                    i++
                }
                blocks += MdBlock.Table(rows)
            }
            line.isBlank() -> i++
            else -> {
                val para = mutableListOf(line)
                i++
                while (i < lines.size && lines[i].isNotBlank() && !isSpecialLine(lines[i])) {
                    para += lines[i]
                    i++
                }
                blocks += MdBlock.Paragraph(para)
            }
        }
    }
    return blocks
}

private fun isSpecialLine(line: String): Boolean {
    val trimmed = line.trimStart()
    return trimmed.startsWith("```") ||
        IMAGE_REGEX.matches(trimmed) ||
        HTML_IMAGE_REGEX.containsMatchIn(trimmed) ||
        HEADING_REGEX.matches(trimmed) ||
        trimmed.startsWith("- ") ||
        trimmed.startsWith("* ") ||
        ORDERED_REGEX.containsMatchIn(trimmed) ||
        trimmed.startsWith(">") ||
        trimmed.startsWith("|")
}

/** Table separator rows (only pipes, dashes, colons and spaces) are dropped. */
private fun isTableSeparator(line: String): Boolean =
    line.replace(Regex("[|:\\-\\s]"), "").isEmpty()

// ---- Inline rendering ------------------------------------------------------

internal fun markdownImagePath(source: String): String? = source.trim().trim('<', '>').substringBefore('#').substringBefore('?').takeIf { it.isNotBlank() }

@Composable
private fun ResolvedMarkdownImage(source: String, resolver: (suspend (String) -> String?)?) {
    var resolved by remember(source) { mutableStateOf<String?>(null) }
    LaunchedEffect(source, resolver) {
        resolved = resolver?.invoke(source) ?: source
    }
    resolved?.let { MarkdownImage(it, Modifier.fillMaxWidth()) }
}

@Composable
internal fun MarkdownImage(source: String, modifier: Modifier = Modifier) {
    val path = markdownImagePath(source)
    if (path == null) return
    AndroidView(
        factory = { context -> WebView(context).apply { settings.javaScriptEnabled = false; settings.allowFileAccess = true } },
        update = { webView ->
            val escaped = path.replace("\"", "&quot;")
            webView.loadDataWithBaseURL(null, "<html><body><img src=\"$escaped\" style=\"max-width:100%;height:auto;\"/></body></html>", "text/html", "UTF-8", null)
        },
        modifier = modifier.fillMaxWidth().heightIn(min = 24.dp),
    )
}

private sealed interface InlineSegment {
    data class Plain(val text: String) : InlineSegment
    data class Bold(val text: String) : InlineSegment
    data class Italic(val text: String) : InlineSegment
    data class Code(val text: String) : InlineSegment
    data class Link(val text: String, val url: String) : InlineSegment
}

private fun parseInlineSegments(text: String): List<InlineSegment> {
    val segments = mutableListOf<InlineSegment>()
    val sb = StringBuilder()
    var i = 0
    fun flush() {
        if (sb.isNotEmpty()) {
            segments += InlineSegment.Plain(sb.toString())
            sb.clear()
        }
    }
    while (i < text.length) {
        when {
            text.startsWith("`", i) -> {
                val end = text.indexOf('`', i + 1)
                if (end != -1) {
                    flush()
                    segments += InlineSegment.Code(text.substring(i + 1, end))
                    i = end + 1
                } else {
                    sb.append(text[i]); i++
                }
            }
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end != -1) {
                    flush()
                    segments += InlineSegment.Bold(text.substring(i + 2, end))
                    i = end + 2
                } else {
                    sb.append(text[i]); i++
                }
            }
            text.startsWith("*", i) -> {
                val end = text.indexOf("*", i + 1)
                if (end != -1) {
                    flush()
                    segments += InlineSegment.Italic(text.substring(i + 1, end))
                    i = end + 1
                } else {
                    sb.append(text[i]); i++
                }
            }
            text.startsWith("[", i) -> {
                val close = text.indexOf("](", i + 1)
                if (close != -1) {
                    val end = text.indexOf(')', close + 2)
                    if (end != -1) {
                        flush()
                        segments += InlineSegment.Link(text.substring(i + 1, close), text.substring(close + 2, end))
                        i = end + 1
                    } else {
                        sb.append(text[i]); i++
                    }
                } else {
                    sb.append(text[i]); i++
                }
            }
            text.startsWith("![", i) -> {
                val close = text.indexOf("](", i + 2)
                val end = if (close >= 0) text.indexOf(')', close + 2) else -1
                if (close >= 0 && end >= 0) {
                    flush()
                    segments += InlineSegment.Link(text.substring(i + 2, close), text.substring(close + 2, end))
                    i = end + 1
                } else {
                    sb.append(text[i]); i++
                }
            }
            else -> {
                sb.append(text[i]); i++
            }
        }
    }
    flush()
    return segments
}

/** Renders one line of markdown with bold/italic/code/link spans. */
@Composable
private fun InlineMarkdown(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    val codeStyle = style.copy(
        fontFamily = DsType.codeFont,
        color = colors.labelPrimary,
    )
    val result = remember(text, style, codeStyle, colors) {
        buildInlineContent(text, codeStyle, colors)
    }
    BasicText(
        result,
        modifier = modifier,
        style = style,
    )
}

private fun buildInlineContent(
    text: String,
    codeStyle: TextStyle,
    colors: DsColors,
): AnnotatedString {
    val builder = AnnotatedString.Builder()
    parseInlineSegments(text).forEach { segment ->
        when (segment) {
            is InlineSegment.Plain -> builder.append(segment.text)
            is InlineSegment.Bold -> builder.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(segment.text) }
            is InlineSegment.Italic -> builder.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(segment.text) }
            is InlineSegment.Code -> builder.withStyle(
                SpanStyle(fontFamily = codeStyle.fontFamily, color = codeStyle.color),
            ) { append(segment.text) }
            is InlineSegment.Link -> {
                // v1 renders links as accent-colored text (no click-through).
                builder.withStyle(SpanStyle(color = colors.accent)) { append(segment.text) }
            }
        }
    }
    return builder.toAnnotatedString()
}

// ---- Block renderers --------------------------------------------------------

@Composable
private fun MdListBlock(block: MdBlock.MdList) {
    val colors = DsTheme.colors
    Column(Modifier.fillMaxWidth().padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        block.items.forEachIndexed { index, item ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(
                    if (block.ordered) "${index + 1}." else "•",
                    style = DsType.mdBody.copy(color = colors.labelSecondary),
                    textAlign = if (block.ordered) TextAlign.End else TextAlign.Start,
                    modifier = Modifier.width(if (block.ordered) 28.dp else 18.dp),
                )
                Spacer(Modifier.width(6.dp))
                InlineMarkdown(item, DsType.mdBody.copy(color = colors.labelPrimary), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MdBlockquote(block: MdBlock.Blockquote) {
    val colors = DsTheme.colors
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = 2.dp)) {
        Box(
            Modifier
                .width(2.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(1.dp))
                .background(colors.citation),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            block.lines.forEach { line ->
                InlineMarkdown(line, DsType.mdSmall.copy(color = colors.labelTertiary), Modifier.fillMaxWidth())
            }
        }
    }
}


/** Fenced code block with a sticky banner (lang · copy) and a mono pre. */
@Composable
private fun CodeBlock(lang: String?, code: String, modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(DsShapes.block)
            .background(colors.codeBlockBg)
            .border(1.dp, colors.borderL1, DsShapes.block),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.codeBlockBanner)
                .padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                lang?.let { "$it · copy" } ?: "copy",
                style = DsType.caption11Strong.copy(fontFamily = DsType.codeFont, color = colors.labelCaption),
                color = colors.labelCaption,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Filled.ContentCopy,
                contentDescription = "Copy code",
                tint = colors.labelTertiary,
                modifier = Modifier
                    .size(16.dp)
                    .clip(DsShapes.chip)
                    .clickable { clipboard.setText(AnnotatedString(code)) }
                    .padding(2.dp),
            )
        }
        Text(
            code,
            style = DsType.mdCode,
            color = colors.labelPrimary,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun MarkdownTextPreview() {
    DshTheme {
        MarkdownText(
            text = """
                # Heading

                A paragraph with **bold**, *italic* and `inline code` plus a [link](https://example.com).

                - first item
                - second item

                1. ordered one
                2. ordered two

                > A quoted thought.

                ```kotlin
                val answer = 42
                ```

                | col a | col b |
                | ----- | ----- |
                | 1     | 2     |
            """.trimIndent(),
            modifier = Modifier.padding(16.dp),
        )
    }
}
