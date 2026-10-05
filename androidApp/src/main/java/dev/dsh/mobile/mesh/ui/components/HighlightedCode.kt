package dev.dsh.mobile.mesh.ui.components

import android.util.Log
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Load a bundled TextMate grammar for [language] (a fenced-block language name or `bash`).
 *
 * One load per card, not per row: the grammar parse is the expensive half of highlighting, and a
 * card can hold many output blocks. A language with no bundled grammar yields null, which leaves the
 * caller's library-based fallback in charge rather than failing.
 */
@Composable
internal fun rememberTextMateHighlighterForLanguage(language: String?): TextMateCodeHighlighter? {
    val context = LocalContext.current
    val assets = context.applicationContext.assets
    val darkMode = isSystemInDarkTheme()
    val themeAsset = if (darkMode) "textmate-dark.json" else "textmate-light.json"
    val grammarAsset = textMateGrammarAssetForLanguage(language)
    val loaded by produceState<Pair<String, TextMateCodeHighlighter?>?>(null, grammarAsset, themeAsset) {
        value = "$themeAsset:$grammarAsset" to if (grammarAsset == null) null else withContext(Dispatchers.Default) {
            runCatching {
                TextMateCodeHighlighter(assets.open(grammarAsset), assets.open(themeAsset)) { scope ->
                    textMateGrammarAssetForScope(scope)?.let(assets::open)
                }
            }.onFailure { Log.w("CodeHighlight", "Failed to load grammar for $language", it) }.getOrNull()
        }
    }
    return loaded?.second
}

/**
 * Render [code] as highlighted rows of one chunk list.
 *
 * Every rendered row is a chunk index of the same [chunks] list, so a multi-line command or output
 * keeps one syntax state across its lines the way the transcript's diff rows already do — a heredoc
 * or a quoted string cannot be re-lexed as if it started a new statement.
 */
@Composable
internal fun HighlightedCodeLines(
    code: String,
    language: String,
    textMate: TextMateCodeHighlighter?,
    modifier: Modifier = Modifier,
) {
    val darkMode = isSystemInDarkTheme()
    val lines = remember(code) { diffTextLines(code) }
    Column(modifier) {
        lines.forEachIndexed { index, line ->
            KodeViewCode(
                code = line,
                pathOrLanguage = language,
                textMate = textMate,
                chunks = lines,
                chunkIndex = index,
                sourceVersion = code,
                darkMode = darkMode,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Shell commands and their text output, highlighted with the shell grammar. */
@Composable
internal fun TerminalCode(code: String, textMate: TextMateCodeHighlighter?, modifier: Modifier = Modifier) {
    HighlightedCodeLines(code = code, language = "bash", textMate = textMate, modifier = modifier)
}
