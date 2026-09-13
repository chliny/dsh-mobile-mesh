package dev.dsh.mobile.mesh.ui.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes
import dev.snipme.kodeview.view.CodeTextView

/**
 * Renders [code] with KodeView (backed by the Highlights engine) instead of a hand rolled
 * regex highlighter. Callers pass either a file path (the extension selects the language) or
 * an explicit language name; unknown input falls back to [SyntaxLanguage.DEFAULT].
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
    CodeTextView(modifier = modifier, highlights = highlights)
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
