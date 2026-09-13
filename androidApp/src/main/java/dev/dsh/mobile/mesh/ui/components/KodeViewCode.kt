package dev.dsh.mobile.mesh.ui.components

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
fun KodeViewCode(
    code: String,
    modifier: Modifier = Modifier,
    pathOrLanguage: String? = null,
    darkMode: Boolean = isSystemInDarkTheme(),
) {
    val language = remember(pathOrLanguage) { kodeviewLanguage(pathOrLanguage) }
    val highlights = remember(code, language, darkMode) {
        Highlights.Builder()
            .code(code)
            .language(language)
            .theme(SyntaxThemes.default(darkMode))
            .build()
    }
    var annotatedCode by remember(highlights) { mutableStateOf(AnnotatedString(code)) }
    LaunchedEffect(highlights) {
        try {
            annotatedCode = withContext(Dispatchers.Default) {
                highlights.getHighlights().toAnnotatedString(highlights.getCode())
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            annotatedCode = AnnotatedString(code)
        }
    }
    BasicText(
        text = annotatedCode,
        modifier = modifier,
        style = LocalTextStyle.current,
    )
}

/** Maps a file path (or raw extension) to the closest [SyntaxLanguage] supported by Highlights. */
internal fun kodeviewLanguage(pathOrLanguage: String?): SyntaxLanguage = when (
    pathOrLanguage?.substringAfterLast('.', "")?.lowercase()
) {
    "c", "h" -> SyntaxLanguage.C
    "cc", "cpp", "cxx", "hpp", "hh" -> SyntaxLanguage.CPP
    "cs" -> SyntaxLanguage.CSHARP
    "coffee" -> SyntaxLanguage.COFFEESCRIPT
    "dart" -> SyntaxLanguage.DART
    "go" -> SyntaxLanguage.GO
    "java" -> SyntaxLanguage.JAVA
    "js", "jsx", "mjs", "cjs" -> SyntaxLanguage.JAVASCRIPT
    "kt", "kts" -> SyntaxLanguage.KOTLIN
    "pl", "pm" -> SyntaxLanguage.PERL
    "php" -> SyntaxLanguage.PHP
    "py" -> SyntaxLanguage.PYTHON
    "rb" -> SyntaxLanguage.RUBY
    "rs" -> SyntaxLanguage.RUST
    "sh", "bash", "zsh", "fish" -> SyntaxLanguage.SHELL
    "swift" -> SyntaxLanguage.SWIFT
    "ts", "tsx" -> SyntaxLanguage.TYPESCRIPT
    else -> SyntaxLanguage.DEFAULT
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
