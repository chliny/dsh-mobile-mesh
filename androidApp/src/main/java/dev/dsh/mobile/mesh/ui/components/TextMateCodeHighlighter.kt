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
    val filename = path?.replace('\\', '/')?.substringAfterLast('/')?.lowercase()
    extensionGrammarAsset(filename?.substringAfterLast('.', ""))?.let { return it }
    return filenameGrammarAsset(filename)
}

/**
 * Well-known filenames whose extension says nothing about the language.
 *
 * These are checked after the extension so that a real extension always wins; only names that
 * conventionally carry no meaningful suffix (`Dockerfile`, `Gemfile`, `Cargo.lock`) land here.
 */
private fun filenameGrammarAsset(filename: String?): String? = when {
    filename == null -> null
    filename == "dockerfile" || filename.startsWith("dockerfile.") -> "textmate-docker.tmLanguage.json"
    filename == "containerfile" -> "textmate-docker.tmLanguage.json"
    filename == "makefile" || filename.startsWith("makefile.") || filename == "gnumakefile" -> "textmate-make.tmLanguage.json"
    filename == "cmakelists.txt" -> "textmate-cmake.tmLanguage.json"
    filename == "cargo.lock" -> "textmate-toml.tmLanguage.json"
    filename == "yarn.lock" -> "textmate-yaml.tmLanguage.json"
    filename == "jenkinsfile" -> "textmate-groovy.tmLanguage.json"
    filename == ".env" || filename.startsWith(".env.") -> "textmate-dotenv.tmLanguage.json"
    filename in setOf("gemfile", "rakefile", "vagrantfile", "podfile", "fastfile", "brewfile", "guardfile") ->
        "textmate-ruby.tmLanguage.json"
    filename in setOf("gradlew", "mvnw") -> "textmate-shellscript.tmLanguage.json"
    filename in setOf(".bashrc", ".bash_profile", ".zshrc", ".zprofile", ".profile", ".bash_aliases") ->
        "textmate-shellscript.tmLanguage.json"
    filename in setOf(".editorconfig", ".gitconfig", ".gitmodules", ".gitattributes") -> "textmate-ini.tmLanguage.json"
    else -> null
}

private fun extensionGrammarAsset(extension: String?): String? = when (extension) {
    "dockerfile" -> "textmate-docker.tmLanguage.json"
    "mk", "make" -> "textmate-make.tmLanguage.json"
    "gradle", "groovy", "jenkinsfile" -> "textmate-groovy.tmLanguage.json"
    "kt", "kts" -> "kotlin.tmLanguage.json"
    "js", "jsx", "mjs", "cjs" -> "JavaScript.tmLanguage.json"
    "json" -> "JSON.tmLanguage.json"
    "jsonc" -> "textmate-jsonc.tmLanguage.json"
    "json5" -> "textmate-json5.tmLanguage.json"
    "jsonl", "ndjson" -> "textmate-jsonl.tmLanguage.json"
    "ipynb" -> "JSON.tmLanguage.json"
    "ini", "properties", "cfg", "conf" -> "textmate-ini.tmLanguage.json"
    "yaml", "yml" -> "textmate-yaml.tmLanguage.json"
    "env" -> "textmate-dotenv.tmLanguage.json"
    "toml" -> "textmate-toml.tmLanguage.json"
    "c", "h" -> "textmate-c.tmLanguage.json"
    "cc", "cpp", "cxx", "hpp", "hh", "hxx" -> "textmate-cpp.tmLanguage.json"
    "cs" -> "textmate-csharp.tmLanguage.json"
    "css" -> "textmate-css.tmLanguage.json"
    "scss" -> "textmate-scss.tmLanguage.json"
    "less" -> "textmate-less.tmLanguage.json"
    "dart" -> "textmate-dart.tmLanguage.json"
    "go" -> "textmate-go.tmLanguage.json"
    "lua" -> "textmate-lua.tmLanguage.json"
    "m", "mm" -> "textmate-objective-c.tmLanguage.json"
    "pl", "pm" -> "textmate-perl.tmLanguage.json"
    "ps1", "psm1", "psd1" -> "textmate-powershell.tmLanguage.json"
    "r" -> "textmate-r.tmLanguage.json"
    "rmd" -> "textmate-markdown.tmLanguage.json"
    "scala", "sc", "sbt" -> "textmate-scala.tmLanguage.json"
    "htm", "html" -> "textmate-html.tmLanguage.json"
    "java" -> "textmate-java.tmLanguage.json"
    "php", "phtml" -> "textmate-php.tmLanguage.json"
    "py", "pyw", "pyi" -> "textmate-python.tmLanguage.json"
    "rb", "rake", "gemspec" -> "textmate-ruby.tmLanguage.json"
    "rs" -> "textmate-rust.tmLanguage.json"
    "sh", "bash", "zsh", "fish" -> "textmate-shellscript.tmLanguage.json"
    "sql" -> "textmate-sql.tmLanguage.json"
    "swift" -> "textmate-swift.tmLanguage.json"
    "ts", "mts", "cts" -> "textmate-typescript.tmLanguage.json"
    "tsx" -> "textmate-tsx.tmLanguage.json"
    "xml", "xsl", "xslt", "svg" -> "textmate-xml.tmLanguage.json"
    // Languages added for coverage beyond the mainstream web/JVM set.
    "ex", "exs" -> "textmate-elixir.tmLanguage.json"
    "erl", "hrl", "escript" -> "textmate-erlang.tmLanguage.json"
    "hs", "hsig", "lhs" -> "textmate-haskell.tmLanguage.json"
    "clj", "cljs", "cljc", "edn" -> "textmate-clojure.tmLanguage.json"
    "ml", "mli" -> "textmate-ocaml.tmLanguage.json"
    "fs", "fsi", "fsx" -> "textmate-fsharp.tmLanguage.json"
    "zig", "zon" -> "textmate-zig.tmLanguage.json"
    "hcl" -> "textmate-hcl.tmLanguage.json"
    "tf", "tfvars" -> "textmate-terraform.tmLanguage.json"
    "graphql", "graphqls", "gql" -> "textmate-graphql.tmLanguage.json"
    "proto", "proto3" -> "textmate-proto.tmLanguage.json"
    "cmake" -> "textmate-cmake.tmLanguage.json"
    "nix" -> "textmate-nix.tmLanguage.json"
    "jl" -> "textmate-julia.tmLanguage.json"
    "asm", "nasm", "s" -> "textmate-asm.tmLanguage.json"
    "bat", "cmd" -> "textmate-bat.tmLanguage.json"
    "vb", "vbs" -> "textmate-vb.tmLanguage.json"
    "pas", "pp", "dpr", "lfm", "lpr" -> "textmate-pascal.tmLanguage.json"
    "sol" -> "textmate-solidity.tmLanguage.json"
    "v", "vh", "sv", "svh" -> "textmate-verilog.tmLanguage.json"
    "vhd", "vhdl" -> "textmate-vhdl.tmLanguage.json"
    "tex", "latex", "sty", "cls" -> "textmate-latex.tmLanguage.json"
    "md", "markdown", "mdown", "mkd" -> "textmate-markdown.tmLanguage.json"
    "mdx" -> "textmate-mdx.tmLanguage.json"
    "vue" -> "textmate-vue.tmLanguage.json"
    "svelte" -> "textmate-svelte.tmLanguage.json"
    "astro" -> "textmate-astro.tmLanguage.json"
    "awk" -> "textmate-awk.tmLanguage.json"
    "diff", "patch" -> "textmate-diff.tmLanguage.json"
    "coffee" -> "textmate-coffee.tmLanguage.json"
    "http", "rest" -> "textmate-http.tmLanguage.json"
    else -> null
}

/**
 * Map a Markdown fence language (or file extension) to a bundled TextMate grammar.
 *
 * This table maps every accepted spelling to the extension [textMateGrammarAsset] recognizes.
 * Keeping the canonical extension here matters: a fence tagged `python` must resolve to the
 * `py` grammar, not to an asset named `code.python`, otherwise the block silently falls back to
 * the limited keyword highlighter and loses most token colors.
 */
private val textMateGrammarExtensionByLanguage = mapOf(
    // Plain text and shell-like output.
    "text" to "sh", "plaintext" to "sh", "txt" to "sh", "console" to "sh", "shellsession" to "sh",
    "shell" to "sh", "shellscript" to "sh", "bash" to "sh", "zsh" to "sh", "fish" to "sh", "sh" to "sh",
    "dotenv" to "env", "env" to "env",
    // JVM / JavaScript / TypeScript.
    "kotlin" to "kt", "kts" to "kt", "kt" to "kt",
    "javascript" to "js", "node" to "js", "js" to "js", "mjs" to "js", "cjs" to "js",
    "typescript" to "ts", "ts" to "ts", "mts" to "ts", "cts" to "ts",
    "tsx" to "tsx", "jsx" to "jsx", "java" to "java", "scala" to "scala", "sc" to "scala", "sbt" to "sbt",
    "groovy" to "groovy", "gradle" to "gradle",
    // Scripting and other common fence languages.
    "python" to "py", "python3" to "py", "py" to "py", "pyw" to "py", "pyi" to "py",
    "ruby" to "rb", "rb" to "rb", "rake" to "rb", "gemspec" to "rb",
    "rust" to "rs", "rs" to "rs", "php" to "php", "phtml" to "php",
    "lua" to "lua", "dart" to "dart", "swift" to "swift", "go" to "go", "golang" to "go",
    "powershell" to "ps1", "ps1" to "ps1", "psm1" to "ps1", "psd1" to "ps1",
    "coffee" to "coffee", "coffeescript" to "coffee",
    "elixir" to "ex", "ex" to "ex", "exs" to "exs",
    "erlang" to "erl", "erl" to "erl",
    "haskell" to "hs", "hs" to "hs",
    "clojure" to "clj", "clj" to "clj", "cljs" to "cljs", "cljc" to "cljc",
    "ocaml" to "ml", "ml" to "ml",
    "fsharp" to "fs", "f#" to "fs", "fs" to "fs",
    "zig" to "zig", "zon" to "zon",
    "julia" to "jl", "jl" to "jl",
    "awk" to "awk", "solidity" to "sol", "sol" to "sol",
    "pascal" to "pas", "delphi" to "pas", "objectpascal" to "pas",
    "vb" to "vb", "vbnet" to "vb", "visual-basic" to "vb",
    "bat" to "bat", "batch" to "bat", "cmd" to "bat",
    "asm" to "asm", "nasm" to "asm", "assembly" to "asm", "x86asm" to "asm",
    "verilog" to "v", "systemverilog" to "sv", "v" to "v",
    "vhdl" to "vhdl", "vhd" to "vhd",
    // C family.
    "c" to "c", "h" to "c",
    "cpp" to "cpp", "c++" to "cpp", "cc" to "cpp", "cxx" to "cpp", "hpp" to "cpp", "hh" to "cpp", "hxx" to "cpp",
    "cs" to "cs", "c#" to "cs", "csharp" to "cs", "dotnet" to "cs",
    "objc" to "m", "objective-c" to "m", "objectivec" to "m",
    // Markup, data and build files.
    // Vue/Svelte/Astro and template dialects have dedicated grammars when bundled; the HTML-shaped
    // fallbacks that remain (handlebars, jinja, twig) still color tags, attributes and embedded
    // script/style blocks through the HTML grammar's includes.
    "html" to "html", "htm" to "html", "xml" to "xml", "svg" to "xml", "css" to "css",
    "scss" to "scss", "sass" to "scss", "less" to "less",
    "json" to "json", "jsonc" to "jsonc", "json5" to "json5", "jsonl" to "jsonl", "ndjson" to "jsonl",
    "yaml" to "yaml", "yml" to "yaml", "compose" to "yaml", "docker-compose" to "yaml",
    "toml" to "toml", "ini" to "ini", "properties" to "ini",
    "dockerfile" to "dockerfile", "docker" to "dockerfile",
    "make" to "make", "makefile" to "make", "mk" to "make",
    "cmake" to "cmake", "hcl" to "hcl", "terraform" to "tf", "tf" to "tf",
    "graphql" to "graphql", "gql" to "graphql",
    "protobuf" to "proto", "proto" to "proto", "proto3" to "proto3",
    "nix" to "nix", "latex" to "tex", "tex" to "tex",
    "markdown" to "md", "md" to "md", "gfm" to "md", "mdx" to "mdx",
    "vue" to "vue", "svelte" to "svelte", "astro" to "astro",
    "diff" to "diff", "patch" to "diff", "udiff" to "diff",
    "http" to "http", "rest" to "http",
    "sql" to "sql", "r" to "r", "rmd" to "rmd",
    "perl" to "pl", "pl" to "pl", "pm" to "pl",
    "handlebars" to "html", "hbs" to "html", "mustache" to "html",
    "jinja" to "html", "jinja2" to "html", "django" to "html", "twig" to "html", "jsp" to "html",
)

internal fun textMateGrammarAssetForLanguage(info: String?): String? {
    val language = info?.trim()?.substringBefore(' ')?.lowercase()?.removePrefix("language-")
    val extension = textMateGrammarExtensionByLanguage[language] ?: return null
    return textMateGrammarAsset("code.$extension")
}

/**
 * Resolve bundled embedded grammars only when an include actually needs one.
 *
 * An include may carry a rule name (`source.ts#type`); the grammar it belongs to is still the base
 * scope, so the rule suffix is dropped before lookup. Without that, every Vue/Svelte/astro block
 * type that includes a named rule would silently render without syntax colors.
 */
internal fun textMateGrammarAssetForScope(scope: String): String? = when (scope.substringBefore('#')) {
    "source.kotlin" -> "kotlin.tmLanguage.json"
    "source.js", "source.js.jsx" -> "JavaScript.tmLanguage.json"
    "source.json" -> "JSON.tmLanguage.json"
    "source.json.comments" -> "textmate-jsonc.tmLanguage.json"
    "source.json5" -> "textmate-json5.tmLanguage.json"
    "source.json.lines" -> "textmate-jsonl.tmLanguage.json"
    "source.dockerfile" -> "textmate-docker.tmLanguage.json"
    "source.makefile" -> "textmate-make.tmLanguage.json"
    "source.ini" -> "textmate-ini.tmLanguage.json"
    "source.yaml" -> "textmate-yaml.tmLanguage.json"
    "source.toml" -> "textmate-toml.tmLanguage.json"
    "source.dotenv" -> "textmate-dotenv.tmLanguage.json"
    "source.css" -> "textmate-css.tmLanguage.json"
    "source.css.scss" -> "textmate-scss.tmLanguage.json"
    "source.css.less" -> "textmate-less.tmLanguage.json"
    "source.css.postcss", "source.postcss" -> "textmate-css.tmLanguage.json"
    "source.sass" -> "textmate-scss.tmLanguage.json"
    "source.stylus" -> "textmate-css.tmLanguage.json"
    "source.c" -> "textmate-c.tmLanguage.json"
    "source.cpp" -> "textmate-cpp.tmLanguage.json"
    "source.cs" -> "textmate-csharp.tmLanguage.json"
    "source.dart" -> "textmate-dart.tmLanguage.json"
    "source.go" -> "textmate-go.tmLanguage.json"
    "source.groovy" -> "textmate-groovy.tmLanguage.json"
    "text.html.basic", "text.html.derivative", "text.html.handlebars", "text.pug" -> "textmate-html.tmLanguage.json"
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
    "text.xml", "text.xml.xsl" -> "textmate-xml.tmLanguage.json"
    "source.elixir", "text.elixir" -> "textmate-elixir.tmLanguage.json"
    "source.erlang" -> "textmate-erlang.tmLanguage.json"
    "source.haskell" -> "textmate-haskell.tmLanguage.json"
    "source.clojure" -> "textmate-clojure.tmLanguage.json"
    "source.ocaml" -> "textmate-ocaml.tmLanguage.json"
    "source.fsharp" -> "textmate-fsharp.tmLanguage.json"
    "source.zig" -> "textmate-zig.tmLanguage.json"
    "source.hcl", "source.hcl.terraform" -> "textmate-hcl.tmLanguage.json"
    "source.graphql" -> "textmate-graphql.tmLanguage.json"
    "source.proto" -> "textmate-proto.tmLanguage.json"
    "source.cmake" -> "textmate-cmake.tmLanguage.json"
    "source.nix" -> "textmate-nix.tmLanguage.json"
    "source.julia" -> "textmate-julia.tmLanguage.json"
    "source.asm", "source.asm.x86_64" -> "textmate-asm.tmLanguage.json"
    "source.batchfile" -> "textmate-bat.tmLanguage.json"
    "source.asp.vb.net" -> "textmate-vb.tmLanguage.json"
    "source.pascal" -> "textmate-pascal.tmLanguage.json"
    "source.solidity" -> "textmate-solidity.tmLanguage.json"
    "source.verilog", "source.systemverilog" -> "textmate-verilog.tmLanguage.json"
    "source.vhdl" -> "textmate-vhdl.tmLanguage.json"
    "text.tex", "text.tex.latex" -> "textmate-latex.tmLanguage.json"
    "text.html.markdown" -> "textmate-markdown.tmLanguage.json"
    "source.mdx" -> "textmate-mdx.tmLanguage.json"
    "text.html.vue" -> "textmate-vue.tmLanguage.json"
    "source.svelte" -> "textmate-svelte.tmLanguage.json"
    "source.astro" -> "textmate-astro.tmLanguage.json"
    "source.awk" -> "textmate-awk.tmLanguage.json"
    "source.diff" -> "textmate-diff.tmLanguage.json"
    "source.http" -> "textmate-http.tmLanguage.json"
    "source.coffee" -> "textmate-coffee.tmLanguage.json"
    else -> null
}

/**
 * A TextMate tokenizer: incremental line state and a bounded styled-result cache.
 *
 * One instance can be shared by several documents — a tool card parses one grammar for its command
 * and every output block — so all caches and parse states are kept per document. They cannot be
 * keyed by line index alone: the command and its output start at the same index, and a shared cache
 * would make the output's first line render the command's first line instead (and vice versa).
 * Documents are identified by the [chunks] list instance the caller passes.
 */
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

    /** One document's parse state and styled-line cache. */
    private class DocumentState {
        val endStates = ArrayList<StateStack?>()
        val diffOldStates = ArrayList<StateStack?>()
        val diffNewStates = ArrayList<StateStack?>()
        val diffStyled = object : LinkedHashMap<Int, AnnotatedString>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, AnnotatedString>): Boolean = size > 24
        }
        val styled = object : LinkedHashMap<Int, AnnotatedString>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, AnnotatedString>): Boolean = size > 24
        }
    }

    /**
     * Identity key for a document. Content equality must not merge two blocks: equal command and
     * output lines would then share a parse state, which is exactly the confusion the per-document
     * state exists to prevent. It also avoids hashing a potentially large line list on every call.
     */
    private class DocumentKey(private val chunks: List<String>) {
        override fun hashCode(): Int = System.identityHashCode(chunks)
        override fun equals(other: Any?): Boolean = other is DocumentKey && other.chunks === chunks
    }

    /** A card usually has a command plus a few output blocks; older documents only cost a reparse. */
    private val documents = object : LinkedHashMap<DocumentKey, DocumentState>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<DocumentKey, DocumentState>): Boolean = size > 8
    }

    private fun document(chunks: List<String>): DocumentState =
        documents.getOrPut(DocumentKey(chunks)) { DocumentState() }

    /** Tokenizes only through the requested chunk; skipped chunks update state without retaining text styles. */
    @Synchronized
    fun highlight(chunks: List<String>, index: Int, resetAt: Set<Int> = emptySet()): AnnotatedString {
        require(index in chunks.indices)
        if (chunks[index].length > 16_384) return AnnotatedString(chunks[index])
        val document = document(chunks)
        document.styled[index]?.let { return it }
        var next = document.endStates.size
        while (next <= index) {
            if (chunks[next].length > 16_384) {
                document.endStates.add(null) // Unknown state: resume with a fresh grammar stack after this block.
                if (next == index) return AnnotatedString(chunks[index])
                next++
                continue
            }
            val state = if (next in resetAt) null else document.endStates.lastOrNull()
            val (rendered, endState) = tokenize(chunks[next], state, next == index)
            document.endStates.add(endState)
            if (next == index) {
                document.styled[index] = rendered
                return rendered
            }
            next++
        }
        // A visible row whose styled result was evicted starts from the checkpoint before it.
        val previous = if (index in resetAt) null else document.endStates.getOrNull(index - 1)
        val rendered = tokenize(chunks[index], previous, true).first
        document.styled[index] = rendered
        return rendered
    }

    /** One unified display stream, with independent old/new syntax states as git diff requires. */
    @Synchronized
    fun highlightDiff(chunks: List<String>, markers: List<Char>, index: Int, resetAt: Set<Int> = emptySet()): AnnotatedString {
        require(chunks.size == markers.size && index in chunks.indices)
        if (chunks[index].length > 16_384) return AnnotatedString(chunks[index])
        val document = document(chunks)
        document.diffStyled[index]?.let { return it }
        var next = document.diffOldStates.size
        while (next <= index) {
            val old = if (next in resetAt) null else document.diffOldStates.lastOrNull()
            val new = if (next in resetAt) null else document.diffNewStates.lastOrNull()
            val (rendered, oldEnd, newEnd) = tokenizeDiffLine(chunks[next], markers[next], old, new, next == index)
            document.diffOldStates += oldEnd
            document.diffNewStates += newEnd
            if (next == index) {
                if (chunks[next].length <= 16_384) document.diffStyled[index] = rendered
                return rendered
            }
            next++
        }
        val old = if (index in resetAt) null else document.diffOldStates.getOrNull(index - 1)
        val new = if (index in resetAt) null else document.diffNewStates.getOrNull(index - 1)
        val rendered = tokenizeDiffLine(chunks[index], markers[index], old, new, true).first
        document.diffStyled[index] = rendered
        return rendered
    }

    private fun tokenizeDiffLine(
        code: String, marker: Char, old: StateStack?, new: StateStack?, render: Boolean,
    ): Triple<AnnotatedString, StateStack?, StateStack?> {
        require(marker == '-' || marker == '+' || marker == ' ')
        if (code.length > 16_384) return Triple(
            AnnotatedString(code), if (marker == '+') old else null, if (marker == '-') new else null,
        )
        val oldResult = if (marker != '+') tokenize(code, old, render && marker == '-') else null
        val newResult = if (marker != '-') tokenize(code, new, render && marker != '-') else null
        return Triple(
            (if (marker == '-') oldResult else newResult)?.first ?: AnnotatedString(code),
            oldResult?.second ?: old,
            newResult?.second ?: new,
        )
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
