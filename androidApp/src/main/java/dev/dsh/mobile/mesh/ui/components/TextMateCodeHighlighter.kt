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

/** Syntax-color an entire fenced block only when bounded; unknown languages remain plain. */
internal fun highlightFencedCode(
    code: String,
    grammarAsset: String?,
    themeAsset: String,
    openAsset: (String) -> InputStream,
): AnnotatedString {
    if (grammarAsset == null || code.length > 16_384) return AnnotatedString(code)
    return runCatching {
        TextMateCodeHighlighter(openAsset(grammarAsset), openAsset(themeAsset)) { scope ->
            textMateGrammarAssetForScope(scope)?.let(openAsset)
        }.highlight(listOf(code), 0)
    }.getOrElse { AnnotatedString(code) }
}

/** Grammar assets are opened only for files using that grammar. */
internal fun textMateGrammarAsset(path: String?): String? {
    val extension = path?.substringAfterLast('.', "")?.lowercase()
    val filename = path?.substringAfterLast('/')?.lowercase()
    return when {
        filename == "dockerfile" || filename?.startsWith("dockerfile.") == true -> "textmate-docker.tmLanguage.json"
        filename == "makefile" || filename?.startsWith("makefile.") == true -> "textmate-make.tmLanguage.json"
        extension in setOf("dockerfile") -> "textmate-docker.tmLanguage.json"
        extension in setOf("mk", "make") -> "textmate-make.tmLanguage.json"
        extension == "gradle" || extension == "groovy" -> "textmate-groovy.tmLanguage.json"
        extension in setOf("kt", "kts") -> "kotlin.tmLanguage.json"
        extension in setOf("js", "jsx", "mjs", "cjs") -> "JavaScript.tmLanguage.json"
        extension == "json" -> "JSON.tmLanguage.json"
        extension == "jsonc" -> "textmate-jsonc.tmLanguage.json"
        extension == "json5" -> "textmate-json5.tmLanguage.json"
        extension in setOf("ini", "properties", "cfg") -> "textmate-ini.tmLanguage.json"
        extension in setOf("yaml", "yml") -> "textmate-yaml.tmLanguage.json"
        extension == "toml" -> "textmate-toml.tmLanguage.json"
        extension in setOf("c", "h") -> "textmate-c.tmLanguage.json"
        extension in setOf("cc", "cpp", "cxx", "hpp", "hh", "hxx") -> "textmate-cpp.tmLanguage.json"
        extension in setOf("cs") -> "textmate-csharp.tmLanguage.json"
        extension == "css" -> "textmate-css.tmLanguage.json"
        extension == "scss" -> "textmate-scss.tmLanguage.json"
        extension == "less" -> "textmate-less.tmLanguage.json"
        extension in setOf("dart") -> "textmate-dart.tmLanguage.json"
        extension in setOf("go") -> "textmate-go.tmLanguage.json"
        extension in setOf("lua") -> "textmate-lua.tmLanguage.json"
        extension in setOf("m", "mm") -> "textmate-objective-c.tmLanguage.json"
        extension in setOf("pl", "pm") -> "textmate-perl.tmLanguage.json"
        extension in setOf("ps1", "psm1", "psd1") -> "textmate-powershell.tmLanguage.json"
        extension == "r" -> "textmate-r.tmLanguage.json"
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

internal fun textMateGrammarAssetForLanguage(info: String?): String? {
    val language = info?.trim()?.substringBefore(' ')?.lowercase()?.removePrefix("language-")
    val extension = when (language) {
        "kotlin" -> "kt"
        "javascript", "node" -> "js"
        "typescript" -> "ts"
        "tsx", "jsx", "json", "jsonc", "json5", "html", "xml", "css", "scss", "less",
        "java", "python", "ruby", "rust", "swift", "dart", "php", "sql", "go", "lua",
        "scala", "groovy", "c", "cpp", "cs", "sh", "dockerfile", "ini", "make", "yaml", "yml", "toml" -> language
        "bash", "powershell", "makefile" -> when (language) {
            "bash" -> "sh"
            "powershell" -> "ps1"
            else -> "make"
        }
        "py" -> "py"
        "rb" -> "rb"
        "shell", "zsh" -> "sh"
        "c++" -> "cpp"
        "c#" -> "cs"
        "objc", "objective-c" -> "m"
        "ps1" -> "ps1"
        else -> return null
    }
    return textMateGrammarAsset("code.$extension")
}

/** Resolve bundled embedded grammars only when an include actually needs one. */
internal fun textMateGrammarAssetForScope(scope: String): String? = when (scope) {
    "source.kotlin" -> "kotlin.tmLanguage.json"
    "source.js" -> "JavaScript.tmLanguage.json"
    "source.json" -> "JSON.tmLanguage.json"
    "source.json.comments" -> "textmate-jsonc.tmLanguage.json"
    "source.json5" -> "textmate-json5.tmLanguage.json"
    "source.dockerfile" -> "textmate-docker.tmLanguage.json"
    "source.makefile" -> "textmate-make.tmLanguage.json"
    "source.ini" -> "textmate-ini.tmLanguage.json"
    "source.yaml" -> "textmate-yaml.tmLanguage.json"
    "source.toml" -> "textmate-toml.tmLanguage.json"
    "source.css.scss" -> "textmate-scss.tmLanguage.json"
    "source.css.less" -> "textmate-less.tmLanguage.json"
    "source.c" -> "textmate-c.tmLanguage.json"
    "source.cpp" -> "textmate-cpp.tmLanguage.json"
    "source.cs" -> "textmate-csharp.tmLanguage.json"
    "source.css" -> "textmate-css.tmLanguage.json"
    "source.dart" -> "textmate-dart.tmLanguage.json"
    "source.go" -> "textmate-go.tmLanguage.json"
    "source.groovy" -> "textmate-groovy.tmLanguage.json"
    "text.html.basic" -> "textmate-html.tmLanguage.json"
    "source.java" -> "textmate-java.tmLanguage.json"
    "source.lua" -> "textmate-lua.tmLanguage.json"
    "source.objc" -> "textmate-objective-c.tmLanguage.json"
    "source.perl" -> "textmate-perl.tmLanguage.json"
    "source.php" -> "textmate-php.tmLanguage.json"
    "source.powershell" -> "textmate-powershell.tmLanguage.json"
    "source.python" -> "textmate-python.tmLanguage.json"
    "source.r" -> "textmate-r.tmLanguage.json"
    "source.ruby" -> "textmate-ruby.tmLanguage.json"
    "source.rust" -> "textmate-rust.tmLanguage.json"
    "source.scala" -> "textmate-scala.tmLanguage.json"
    "source.shell" -> "textmate-shellscript.tmLanguage.json"
    "source.sql" -> "textmate-sql.tmLanguage.json"
    "source.swift" -> "textmate-swift.tmLanguage.json"
    "source.ts" -> "textmate-typescript.tmLanguage.json"
    "source.tsx" -> "textmate-tsx.tmLanguage.json"
    "text.xml" -> "textmate-xml.tmLanguage.json"
    else -> null
}

/** A single-file TextMate tokenizer: incremental line state, bounded styled-result cache. */
internal class TextMateCodeHighlighter(
    grammarStream: InputStream,
    themeStream: InputStream,
    grammarLookup: ((String) -> InputStream?)? = null,
) {
    private val grammar: Grammar
    private val theme: Theme
    init {
        grammarStream.use { source ->
            themeStream.use { colors ->
                val raw = GrammarReader.readGrammar(source)
                theme = ThemeReader.readTheme(colors)
                grammar = Grammar(raw.scopeName, raw, JoniOnigLib(), { scope ->
                    grammarLookup?.invoke(scope)?.use(GrammarReader::readGrammar)
                })
            }
        }
    }
    private val endStates = ArrayList<StateStack?>()
    private val styled = object : LinkedHashMap<Int, AnnotatedString>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, AnnotatedString>): Boolean = size > 24
    }

    /** Tokenizes only through the requested chunk; skipped chunks update state without retaining text styles. */
    @Synchronized
    fun highlight(chunks: List<String>, index: Int, resetAt: Set<Int> = emptySet()): AnnotatedString {
        require(index in chunks.indices)
        if (chunks[index].length > 16_384) return AnnotatedString(chunks[index])
        styled[index]?.let { return it }
        var next = endStates.size
        while (next <= index) {
            if (chunks[next].length > 16_384) {
                endStates.add(null) // Unknown state: resume with a fresh grammar stack after this block.
                if (next == index) return AnnotatedString(chunks[index])
                next++
                continue
            }
            val state = if (next in resetAt) null else endStates.lastOrNull()
            val (rendered, endState) = tokenize(chunks[next], state, next == index)
            endStates.add(endState)
            if (next == index) {
                styled[index] = rendered
                return rendered
            }
            next++
        }
        // A visible row whose styled result was evicted starts from the checkpoint before it.
        val previous = if (index in resetAt) null else endStates.getOrNull(index - 1)
        val rendered = tokenize(chunks[index], previous, true).first
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
