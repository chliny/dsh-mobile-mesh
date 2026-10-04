package dev.dsh.mobile.mesh.ui.components

import android.util.Log
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.CodeHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Renders [code] with KodeView's Highlights engine instead of a hand-rolled regex highlighter.
 *
 * KodeView's stock CodeTextView owns a verticalScroll modifier. That is unsafe when this component
 * is hosted by the transcript or file-preview LazyColumn, whose items are already vertically
 * measured and scrolled. We therefore use the same KodeView/Highlights token engine and render its
 * annotated result as a non-scrolling Compose text node; the parent remains the sole scroll owner.
 */
@Composable
internal fun KodeViewCode(
    code: String,
    modifier: Modifier = Modifier,
    pathOrLanguage: String? = null,
    darkMode: Boolean = isSystemInDarkTheme(),
    textMate: TextMateCodeHighlighter? = null,
    chunks: List<String>? = null,
    chunkIndex: Int = 0,
    resetAt: Set<Int> = emptySet(),
    diffMarkers: List<Char>? = null,
    sourceVersion: Any? = null,
) {
    var annotatedCode by remember(code, pathOrLanguage, darkMode, textMate, chunkIndex, sourceVersion) { mutableStateOf(AnnotatedString(code)) }
    LaunchedEffect(code, pathOrLanguage, darkMode, textMate, chunkIndex, resetAt, diffMarkers, sourceVersion) {
        try {
            annotatedCode = withContext(Dispatchers.Default) {
                highlightPreviewCode(code, pathOrLanguage, darkMode, textMate, chunks, chunkIndex, resetAt, diffMarkers)
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Log.w("CodeHighlight", "Failed to highlight $pathOrLanguage; rendering fallback", error)
            annotatedCode = try {
                withContext(Dispatchers.Default) { highlightCode(code, pathOrLanguage, darkMode) }
            } catch (fallbackError: Throwable) {
                if (fallbackError is CancellationException) throw fallbackError
                Log.w("CodeHighlight", "Fallback highlighting failed for $pathOrLanguage", fallbackError)
                AnnotatedString(code)
            }
        }
    }
    BasicText(
        text = annotatedCode,
        modifier = modifier,
        style = LocalTextStyle.current,
    )
}

/** Keep the previous library-based highlighting available if TextMate cannot parse this file. */
internal fun highlightPreviewCode(
    code: String,
    path: String?,
    darkMode: Boolean,
    textMate: TextMateCodeHighlighter?,
    chunks: List<String>?,
    chunkIndex: Int,
    resetAt: Set<Int> = emptySet(),
    diffMarkers: List<Char>? = null,
): AnnotatedString {
    if (textMate == null || chunks == null) return highlightCode(code, path, darkMode)
    return try {
        if (diffMarkers != null) textMate.highlightDiff(chunks, diffMarkers, chunkIndex, resetAt)
        else textMate.highlight(chunks, chunkIndex, resetAt)
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        Log.w("CodeHighlight", "TextMate tokenization failed for $path", error)
        highlightCode(code, path, darkMode)
    }
}

/** Highlight only bounded fragments off the UI thread; unsupported grammars use a small config/text tokenizer. */
internal fun highlightCode(code: String, path: String?, darkMode: Boolean): AnnotatedString {
    if (code.length > 16_384) return AnnotatedString(code)
    val language = kodeviewLanguage(path)
    if (language != SyntaxLanguage.DEFAULT) {
        val highlights = Highlights.Builder()
            .code(code)
            .language(language)
            .theme(SyntaxThemes.default(darkMode))
            .build()
        return highlights.getHighlights().toAnnotatedString(code)
    }
    return highlightConfigCode(code, path, darkMode)
}

/** Maps a file path (or raw extension) to the closest [SyntaxLanguage] supported by Highlights. */
internal fun kodeviewLanguage(pathOrLanguage: String?): SyntaxLanguage = when (
    pathOrLanguage?.substringAfterLast('.', "")?.lowercase()
) {
    "c", "h" -> SyntaxLanguage.C
    "cc", "cpp", "cxx", "hpp", "hh", "hxx" -> SyntaxLanguage.CPP
    "cs" -> SyntaxLanguage.CSHARP
    "coffee" -> SyntaxLanguage.COFFEESCRIPT
    "dart" -> SyntaxLanguage.DART
    "go" -> SyntaxLanguage.GO
    "java" -> SyntaxLanguage.JAVA
    "js", "jsx", "mjs", "cjs" -> SyntaxLanguage.JAVASCRIPT
    "kt", "kts" -> SyntaxLanguage.KOTLIN
    "pl", "pm" -> SyntaxLanguage.PERL
    "php" -> SyntaxLanguage.PHP
    "py", "pyw", "pyi" -> SyntaxLanguage.PYTHON
    "rb" -> SyntaxLanguage.RUBY
    "rs" -> SyntaxLanguage.RUST
    "sh", "bash", "zsh", "fish" -> SyntaxLanguage.SHELL
    "swift" -> SyntaxLanguage.SWIFT
    "ts", "tsx", "mts", "cts" -> SyntaxLanguage.TYPESCRIPT
    else -> SyntaxLanguage.DEFAULT
}

/** Colors configuration keys, strings, values, and comments without loading a WebView or parsing whole files. */
internal fun highlightConfigCode(code: String, path: String?, darkMode: Boolean): AnnotatedString {
    val name = path?.substringAfterLast('/')?.lowercase().orEmpty()
    val format = when {
        name.endsWith(".json") || name.endsWith(".jsonc") || name.endsWith(".json5") -> "json"
        name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".toml") || name.endsWith(".ini") || name.endsWith(".properties") -> "config"
        name.endsWith(".xml") || name.endsWith(".html") || name.endsWith(".svg") -> "markup"
        name.endsWith(".css") || name.endsWith(".scss") || name.endsWith(".less") -> "css"
        name.endsWith(".sql") -> "sql"
        name == "dockerfile" || name.startsWith("dockerfile.") -> "config"
        else -> return AnnotatedString(code)
    }
    val key = if (darkMode) Color(0xFF86B7FF) else Color(0xFF6741D9)
    val string = if (darkMode) Color(0xFF8FD69E) else Color(0xFF2F9E44)
    val literal = if (darkMode) Color(0xFFFFB86C) else Color(0xFF1C7ED6)
    val comment = if (darkMode) Color(0xFF8B949E) else Color(0xFF868E96)
    val pattern = when (format) {
        "json" -> Regex("\\\"(?:\\\\.|[^\\\"\\\\])*\\\"(?=\\s*:)|\\\"(?:\\\\.|[^\\\"\\\\])*\\\"|\\b(?:true|false|null|-?\\d+(?:\\.\\d+)?)\\b|//[^\\n]*")
        "markup" -> Regex("<!--[\\s\\S]*?-->|</?[A-Za-z][\\w:.-]*|[\\w:.-]+(?=\\s*=)|\\\"[^\\\"]*\\\"|'[^']*'")
        "css" -> Regex("/\\*[\\s\\S]*?\\*/|\\\"[^\\\"]*\\\"|'[^']*'|#[0-9a-fA-F]{3,8}\\b|[\\w-]+(?=\\s*:)|\\b\\d+(?:\\.\\d+)?(?:px|rem|em|%)?\\b")
        "sql" -> Regex("--[^\\n]*|/\\*[\\s\\S]*?\\*/|'(?:''|[^'])*'|\\\"(?:\\\"\\\"|[^\\\"])*\\\"|(?i)\\b(?:select|from|where|join|inner|left|right|on|insert|into|values|update|set|delete|create|alter|drop|table|index|as|and|or|not|null|is|in|like|order|by|group|having|limit|offset|distinct|case|when|then|else|end|with|union|all|returning)\\b|\\b\\d+(?:\\.\\d+)?\\b")
        else -> Regex("#[^\\n]*|//[^\\n]*|\\\"(?:\\\\.|[^\\\"\\\\])*\\\"|'[^']*'|[\\w.-]+(?=\\s*[:=])|(?m)^[A-Z]+(?=\\s)|\\b(?:true|false|null|-?\\d+(?:\\.\\d+)?)\\b")
    }
    return buildAnnotatedString {
        append(code)
        pattern.findAll(code).forEach { match ->
            val token = match.value
            val color = when {
                token.startsWith("#") && format != "css" || token.startsWith("//") ||
                    token.startsWith("<!--") || token.startsWith("/*") -> comment
                token.startsWith("\"") || token.startsWith("'") -> if (format == "json" &&
                    code.indexOfFirstAfterWhitespace(match.range.last + 1) == ':'
                ) key else string
                token.startsWith("#") || token.first().isDigit() -> literal
                token.startsWith("<") -> key
                token.first() == '-' || token in setOf("true", "false", "null") -> literal
                else -> key
            }
            addStyle(SpanStyle(color = color), match.range.first, match.range.last + 1)
        }
    }
}

private fun String.indexOfFirstAfterWhitespace(start: Int): Char? {
    var position = start
    while (position < length && this[position].isWhitespace()) position++
    return getOrNull(position)
}

/** Same token-to-span conversion used by KodeView, kept non-scrolling for LazyColumn parents. */
private fun List<CodeHighlight>.toAnnotatedString(code: String): AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        append(code)
        forEach { highlight ->
            val start = highlight.location.start.coerceIn(0, code.length)
            val end = highlight.location.end.coerceIn(start, code.length)
            if (start == end) return@forEach
            when (highlight) {
                is BoldHighlight -> addStyle(
                    SpanStyle(fontWeight = FontWeight.Bold),
                    start = start,
                    end = end,
                )
                is ColorHighlight -> addStyle(
                    SpanStyle(color = Color(highlight.rgb).copy(alpha = 1f)),
                    start = start,
                    end = end,
                )
            }
        }
    }
