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
internal fun textMateGrammarAsset(path: String?): String? {
    val extension = path?.substringAfterLast('.', "")?.lowercase()
    val filename = path?.substringAfterLast('/')?.lowercase()
    return when {
        filename == "dockerfile" || filename?.startsWith("dockerfile.") == true -> "textmate-shellscript.tmLanguage.json"
        filename == "makefile" || filename?.startsWith("makefile.") == true -> "textmate-shellscript.tmLanguage.json"
        extension == "gradle" || extension == "groovy" -> "textmate-groovy.tmLanguage.json"
        extension in setOf("kt", "kts") -> "kotlin.tmLanguage.json"
        extension in setOf("js", "jsx", "mjs", "cjs") -> "JavaScript.tmLanguage.json"
        extension in setOf("json", "jsonc", "json5") -> "JSON.tmLanguage.json"
        extension in setOf("c", "h") -> "textmate-c.tmLanguage.json"
        extension in setOf("cc", "cpp", "cxx", "hpp", "hh", "hxx") -> "textmate-cpp.tmLanguage.json"
        extension in setOf("cs") -> "textmate-csharp.tmLanguage.json"
        extension in setOf("css", "scss", "less") -> "textmate-css.tmLanguage.json"
        extension in setOf("dart") -> "textmate-dart.tmLanguage.json"
        extension in setOf("go") -> "textmate-go.tmLanguage.json"
        extension in setOf("lua") -> "textmate-lua.tmLanguage.json"
        extension in setOf("m", "mm") -> "textmate-objective-c.tmLanguage.json"
        extension in setOf("pl", "pm") -> "textmate-perl.tmLanguage.json"
        extension in setOf("ps1", "psm1", "psd1") -> "textmate-powershell.tmLanguage.json"
        extension in setOf("r", "rmd") -> "textmate-r.tmLanguage.json"
        extension in setOf("scala", "sc") -> "textmate-scala.tmLanguage.json"
        extension in setOf("htm", "html") -> "textmate-html.tmLanguage.json"
        extension in setOf("java") -> "textmate-java.tmLanguage.json"
        extension in setOf("php", "phtml") -> "textmate-php.tmLanguage.json"
        extension in setOf("py", "pyw", "pyi") -> "textmate-python.tmLanguage.json"
        extension in setOf("rb", "rake", "gemspec") -> "textmate-ruby.tmLanguage.json"
        extension in setOf("rs") -> "textmate-rust.tmLanguage.json"
        extension in setOf("sh", "bash", "zsh", "fish") -> "textmate-shellscript.tmLanguage.json"
        extension in setOf("sql") -> "textmate-sql.tmLanguage.json"
        extension in setOf("swift") -> "textmate-swift.tmLanguage.json"
        extension in setOf("ts", "mts", "cts") -> "textmate-typescript.tmLanguage.json"
        extension in setOf("tsx") -> "textmate-tsx.tmLanguage.json"
        extension in setOf("xml", "xsl", "xslt", "svg") -> "textmate-xml.tmLanguage.json"
        else -> null
    }
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
