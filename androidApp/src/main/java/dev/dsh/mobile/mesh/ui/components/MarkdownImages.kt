package dev.dsh.mobile.mesh.ui.components

import android.graphics.BitmapFactory
import com.caverock.androidsvg.SVG
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.LocalImageTransformer
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import dev.dsh.mobile.mesh.ui.theme.DsTheme
import dev.dsh.mobile.mesh.ui.theme.DsType
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode

/**
 * Clean a Markdown image path before resolving it relative to a workspace document.
 *
 * A local destination keeps only the path part; an external URL or an already-resolved data URL
 * is passed through unchanged.
 */
internal fun markdownImagePath(source: String): String? {
    val cleaned = source.trim().trim('<', '>')
    if (cleaned.startsWith("http://", ignoreCase = true) || cleaned.startsWith("https://", ignoreCase = true) ||
        cleaned.startsWith("data:", ignoreCase = true)
    ) {
        return cleaned.takeIf { it.isNotBlank() }
    }
    return cleaned.substringBefore('#').substringBefore('?').takeIf { it.isNotBlank() }
}

private val HTML_IMAGE_TAG = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
private val HTML_IMAGE_SRC = Regex("src\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
private val HTML_IMAGE_ALT = Regex("alt\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
private val HTML_HEADING = Regex("<h([1-6])\\b[^>]*>(.*?)</h\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val HTML_ANCHOR = Regex(
    "<a\\b[^>]*href\\s*=\\s*[\"']([^\"']*)[\"'][^>]*>(.*?)</a>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val HTML_STRONG = Regex("<(?:b|strong)\\b[^>]*>(.*?)</(?:b|strong)>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val HTML_EMPHASIS = Regex("<(?:i|em)\\b[^>]*>(.*?)</(?:i|em)>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val HTML_CODE = Regex("<code\\b[^>]*>(.*?)</code>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val HTML_BREAK = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
private val HTML_PARAGRAPH_OPEN = Regex("<p\\b[^>]*>", RegexOption.IGNORE_CASE)
private val HTML_PARAGRAPH_CLOSE = Regex("</p>", RegexOption.IGNORE_CASE)
private val HTML_RESIDUAL_TAG = Regex(
    "</?(?:div|center|span|kbd|sup|sub|del|s|u|small|font|details|summary|picture|source|thead|tbody|tr|td|th)\\b[^>]*>",
    RegexOption.IGNORE_CASE,
)
private val INLINE_CODE_SPAN = Regex("(`+)[^`]*\\1")

/**
 * Bring the raw HTML that CommonMark documents commonly contain into the native renderer.
 *
 * Chromium used to render these tags; Compose Markdown treats them as literal text. Images are the
 * element the transcript needs from agent output, while README-style headings, paragraphs,
 * emphasis, code and links keep the file preview readable. Fenced code and inline code spans are
 * left byte-for-byte alone: they are showing HTML as an example, not markup.
 */
internal fun markdownHtmlAsMarkdown(text: String): String {
    if (!text.contains('<')) return text
    val out = StringBuilder(text.length + 128)
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
        out.append(convertHtmlOutsideCodeSpans(line))
    }
    return out.toString()
}

private fun convertHtmlOutsideCodeSpans(line: String): String {
    if (!line.contains('<')) return line
    val result = StringBuilder(line.length)
    var cursor = 0
    INLINE_CODE_SPAN.findAll(line).forEach { match ->
        result.append(convertHtmlFragment(line.substring(cursor, match.range.first)))
        result.append(match.value)
        cursor = match.range.last + 1
    }
    result.append(convertHtmlFragment(line.substring(cursor)))
    return result.toString()
}

private fun convertHtmlFragment(fragment: String): String {
    if (!fragment.contains('<')) return fragment
    var value = fragment
    value = HTML_IMAGE_TAG.replace(value) { match ->
        val source = HTML_IMAGE_SRC.find(match.value)?.groupValues?.getOrNull(1) ?: return@replace match.value
        val alt = HTML_IMAGE_ALT.find(match.value)?.groupValues?.getOrNull(1).orEmpty()
        "![$alt]($source)"
    }
    value = HTML_HEADING.replace(value) { match ->
        val level = match.groupValues[1].toIntOrNull()?.coerceIn(1, 6) ?: 1
        "\n\n" + "#".repeat(level) + " " + match.groupValues[2] + "\n\n"
    }
    value = HTML_ANCHOR.replace(value) { match -> "[" + match.groupValues[2] + "](" + match.groupValues[1] + ")" }
    value = HTML_STRONG.replace(value) { match -> "**" + match.groupValues[1] + "**" }
    value = HTML_EMPHASIS.replace(value) { match -> "*" + match.groupValues[1] + "*" }
    value = HTML_CODE.replace(value) { match -> "`" + match.groupValues[1] + "`" }
    value = HTML_BREAK.replace(value, "\n")
    value = HTML_PARAGRAPH_OPEN.replace(value, "\n\n")
    value = HTML_PARAGRAPH_CLOSE.replace(value, "\n\n")
    value = HTML_RESIDUAL_TAG.replace(value, "")
    return value
}

/**
 * Loads a Markdown image for Compose.
 *
 * A workspace image arrives as a `data:` URL produced by [resolveMarkdownImage]; a remote image
 * arrives as its own URL. The legacy WebView renderer relied on Chromium for both; this transformer
 * performs the two fetches the app actually needs and renders nothing (leaving the caption) when the
 * bytes cannot be read.
 */
internal class MarkdownImageTransformer(
    private val resolver: (suspend (String) -> String?)?,
    private val cacheScope: String? = null,
    private val onUnavailable: ((String) -> Unit)? = null,
) : ImageTransformer {
    @Composable
    override fun transform(link: String): ImageData? {
        val source = markdownImagePath(link) ?: return null
        val cacheKey = remember(source, cacheScope) { markdownImageCacheKey(source, cacheScope) }
        val cached = remember(cacheKey) { MarkdownImageCache.get(cacheKey) }
        val maxWidthPx = with(LocalDensity.current) {
            LocalConfiguration.current.screenWidthDp.dp.roundToPx()
        }.coerceAtLeast(1)
        val image by produceState<ImageBitmap?>(cached, cacheKey, maxWidthPx) {
            if (value == null) {
                value = loadMarkdownImage(source, cacheKey, maxWidthPx)
                if (value == null) onUnavailable?.invoke(source)
            }
        }
        val bitmap = image ?: return null
        return ImageData(
            painter = BitmapPainter(bitmap),
            modifier = Modifier.fillMaxWidth(),
            contentDescription = null,
            alignment = Alignment.CenterStart,
            contentScale = ContentScale.FillWidth,
        )
    }

    override fun placeholderConfig(
        density: androidx.compose.ui.unit.Density,
        containerSize: androidx.compose.ui.geometry.Size,
        intrinsicImageSize: androidx.compose.ui.geometry.Size,
    ): com.mikepenz.markdown.model.PlaceholderConfig {
        // Until an image reports its intrinsic size, reserve nothing: a remote badge is a few
        // pixels tall, and the default 180dp placeholder produced a large blank band in README
        // previews while it loaded (or forever, when it could not be fetched).
        if (intrinsicImageSize.isUnspecified) {
            return com.mikepenz.markdown.model.PlaceholderConfig(androidx.compose.ui.geometry.Size(1f, 1f))
        }
        return super<ImageTransformer>.placeholderConfig(density, containerSize, intrinsicImageSize)
    }

    private suspend fun loadMarkdownImage(source: String, cacheKey: String, maxWidthPx: Int): ImageBitmap? {
        MarkdownImageCache.get(cacheKey)?.let { return it }
        val resolved = when {
            source.startsWith("data:", ignoreCase = true) ||
                source.startsWith("http://", ignoreCase = true) ||
                source.startsWith("https://", ignoreCase = true) -> source
            else -> {
                val resolve = resolver ?: return null
                runCatching { resolve(source) }.getOrNull()
            }
        }
        if (resolved.isNullOrBlank()) return null
        val bytes = withContext(Dispatchers.IO) {
            runCatching { readMarkdownImageBytes(resolved) }.getOrNull()
        } ?: return null
        val svg = resolved.contains("svg", ignoreCase = true)
        val bitmap = withContext(Dispatchers.Default) {
            runCatching { decodeMarkdownBitmap(bytes, maxWidthPx, svg) }.getOrNull()
        } ?: return null
        MarkdownImageCache.put(cacheKey, bitmap)
        return bitmap
    }
}

/** The image slot used for block images; unlike the default it keeps the caption without bytes. */
@Composable
internal fun MarkdownImageWithCaption(model: MarkdownComponentModel) {
    val destination = model.node.findFirstDescendant(MarkdownElementTypes.LINK_DESTINATION)
        ?.getUnescapedTextInNode(model.content)
    val caption = model.node.markdownImageAltText(model.content)
    val image = destination?.let { LocalImageTransformer.current.transform(it) }
    if (image != null) {
        Image(
            painter = image.painter,
            contentDescription = caption.takeIf { it.isNotBlank() },
            modifier = image.modifier,
            alignment = image.alignment,
            contentScale = image.contentScale,
            alpha = image.alpha,
            colorFilter = image.colorFilter,
        )
    } else if (caption.isNotBlank()) {
        androidx.compose.material3.Text(
            text = caption,
            modifier = Modifier.fillMaxWidth(),
            style = DsType.caption11.copy(color = DsTheme.colors.labelTertiary),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun ASTNode.findFirstDescendant(type: IElementType): ASTNode? {
    if (this.type == type) return this
    for (child in children) child.findFirstDescendant(type)?.let { return it }
    return null
}

/** The caption of a Markdown image: the LINK_TEXT child without its surrounding brackets. */
internal fun ASTNode.markdownImageAltText(content: String): String =
    findFirstDescendant(MarkdownElementTypes.LINK_TEXT)
        ?.getUnescapedTextInNode(content)
        ?.trim()
        ?.removeSurrounding("[", "]")
        ?.trim()
        .orEmpty()

private object MarkdownImageCache {
    private val cache = object : LruCache<String, ImageBitmap>(maxSizeBytes()) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    fun get(key: String): ImageBitmap? = cache.get(key)

    fun put(key: String, image: ImageBitmap) {
        cache.put(key, image)
    }

    private fun maxSizeBytes(): Int =
        (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

/** A stable cache key: the raw destination is short, while the session/workspace scope keeps
 * identical relative paths in different workspaces from sharing bytes. */
private fun markdownImageCacheKey(source: String, cacheScope: String?): String =
    "${cacheScope.orEmpty()}|${source.length}|$source"

private const val MAX_MARKDOWN_IMAGE_BYTES = 12 * 1024 * 1024

private fun readMarkdownImageBytes(source: String): ByteArray? {
    if (source.startsWith("data:", ignoreCase = true)) {
        return decodeDataUrl(source)
    }
    if (!source.startsWith("http://", ignoreCase = true) && !source.startsWith("https://", ignoreCase = true)) {
        return null
    }
    val connection = (URL(source).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8_000
        readTimeout = 10_000
        instanceFollowRedirects = true
        setRequestProperty("Accept", "image/*")
    }
    return try {
        if (connection.responseCode !in 200..299) return null
        connection.inputStream.use { stream ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = stream.read(chunk)
                if (read < 0) break
                total += read
                if (total > MAX_MARKDOWN_IMAGE_BYTES) return null
                buffer.write(chunk, 0, read)
            }
            buffer.toByteArray()
        }
    } finally {
        connection.disconnect()
    }
}

private fun decodeDataUrl(source: String): ByteArray? {
    val comma = source.indexOf(',')
    if (comma < 0) return null
    val metadata = source.substring(5, comma)
    val payload = source.substring(comma + 1)
    return runCatching {
        if (metadata.contains("base64", ignoreCase = true)) {
            android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
        } else {
            java.net.URLDecoder.decode(payload, "UTF-8").toByteArray(Charsets.UTF_8)
        }
    }.getOrNull()?.takeIf { it.isNotEmpty() && it.size <= MAX_MARKDOWN_IMAGE_BYTES }
}

private fun decodeMarkdownBitmap(bytes: ByteArray, maxWidthPx: Int, svg: Boolean): ImageBitmap? {
    if (svg) return decodeMarkdownSvg(bytes, maxWidthPx)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val options = BitmapFactory.Options().apply {
        inSampleSize = markdownImageSampleSize(bounds.outWidth, maxWidthPx)
        inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}

private fun decodeMarkdownSvg(bytes: ByteArray, maxWidthPx: Int): ImageBitmap? {
    val document = SVG.getFromInputStream(java.io.ByteArrayInputStream(bytes))
    val docWidth = document.documentWidth.takeIf { it > 0f } ?: 512f
    val docHeight = document.documentHeight.takeIf { it > 0f } ?: 512f
    val scale = if (maxWidthPx > 0 && docWidth > maxWidthPx) maxWidthPx / docWidth else 1f
    val width = (docWidth * scale).toInt().coerceAtLeast(1)
    val height = (docHeight * scale).toInt().coerceAtLeast(1)
    val picture = document.renderToPicture()
    val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(bitmap).apply {
        scale(scale, scale)
        drawPicture(picture)
    }
    return bitmap.asImageBitmap()
}

/** The smallest power-of-two subsampling that still covers [targetWidthPx]. */
internal fun markdownImageSampleSize(intrinsicWidth: Int, targetWidthPx: Int): Int {
    if (intrinsicWidth <= 0 || targetWidthPx <= 0) return 1
    var sample = 1
    while (intrinsicWidth / (sample * 2) >= targetWidthPx) sample *= 2
    return sample
}
