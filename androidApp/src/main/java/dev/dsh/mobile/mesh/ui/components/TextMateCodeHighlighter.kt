package dev.dsh.mobile.mesh.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import dev.textmate.grammar.Grammar
import dev.textmate.grammar.raw.GrammarReader
import dev.textmate.grammar.tokenize.StateStack
import dev.textmate.regex.JoniOnigLib
import dev.textmate.theme.Theme
import dev.textmate.theme.ThemeReader
import java.io.InputStream

/** Grammar assets are opened only for files using that grammar. */
internal fun textMateGrammarAsset(path: String?): String? = when (path?.substringAfterLast('.', "")?.lowercase()) {
    "kt", "kts" -> "kotlin.tmLanguage.json"
    "js", "jsx", "mjs", "cjs" -> "JavaScript.tmLanguage.json"
    "json", "jsonc", "json5" -> "JSON.tmLanguage.json"
    else -> null
}

/** A single-file TextMate tokenizer: incremental line state, bounded styled-result cache. */
internal class TextMateCodeHighlighter(
    grammarStream: InputStream,
    themeStream: InputStream,
) {
    private val grammar = grammarStream.use { stream ->
        val raw = GrammarReader.readGrammar(stream)
        Grammar(raw.scopeName, raw, JoniOnigLib())
    }
    private val theme: Theme = themeStream.use(ThemeReader::readTheme)
    private val endStates = ArrayList<StateStack>()
    private var skippedOversizedChunk = false
    private val styled = object : LinkedHashMap<Int, AnnotatedString>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, AnnotatedString>): Boolean = size > 24
    }

    /** Tokenizes only through the requested chunk; skipped chunks update state without retaining text styles. */
    @Synchronized
    fun highlight(chunks: List<String>, index: Int): AnnotatedString {
        require(index in chunks.indices)
        if (chunks[index].length > 16_384) return AnnotatedString(chunks[index])
        if (skippedOversizedChunk) return AnnotatedString(chunks[index])
        styled[index]?.let { return it }
        var next = endStates.size
        while (next <= index) {
            if (chunks[next].length > 16_384) {
                skippedOversizedChunk = true
                return AnnotatedString(chunks[index])
            }
            val state = endStates.lastOrNull()
            val (rendered, endState) = tokenize(chunks[next], state, next == index)
            endStates.add(endState)
            if (next == index) {
                styled[index] = rendered
                return rendered
            }
            next++
        }
        // A visible row whose styled result was evicted starts from the checkpoint before it.
        val rendered = tokenize(chunks[index], endStates.getOrNull(index - 1), true).first
        styled[index] = rendered
        return rendered
    }

    private fun tokenize(code: String, initial: StateStack?, render: Boolean): Pair<AnnotatedString, StateStack> {
        val output = if (render) AnnotatedString.Builder(code) else null
        var state = initial
        var start = 0
        while (start < code.length || start == 0) {
            val end = code.indexOf('\n', start).let { if (it < 0) code.length else it }
            val result = grammar.tokenizeLine(code.substring(start, end), state)
            state = result.ruleStack
            if (output != null) for (token in result.tokens) {
                val from = (start + token.startIndex).coerceIn(start, end)
                val to = (start + token.endIndex).coerceIn(from, end)
                if (from != to) output.addStyle(SpanStyle(color = Color(theme.match(token.scopes).foreground.toInt())), from, to)
            }
            if (end == code.length) break
            start = end + 1
        }
        return (output?.toAnnotatedString() ?: AnnotatedString("")) to requireNotNull(state)
    }
}
