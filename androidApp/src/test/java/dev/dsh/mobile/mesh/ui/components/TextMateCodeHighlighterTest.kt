package dev.dsh.mobile.mesh.ui.components

import androidx.compose.ui.graphics.Color
import dev.textmate.theme.ThemeReader
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextMateCodeHighlighterTest {
    private val assets = File("src/main/assets")

    private fun highlighter(grammar: String = "kotlin.tmLanguage.json") = TextMateCodeHighlighter(
        assets.resolve(grammar).inputStream(),
        assets.resolve("textmate-light.json").inputStream(),
    )

    @Test fun `matches supported file extensions without loading unrelated grammars`() {
        assertEquals("kotlin.tmLanguage.json", textMateGrammarAsset("source/Main.kt"))
        assertEquals("JavaScript.tmLanguage.json", textMateGrammarAsset("index.mjs"))
        assertEquals("textmate-typescript.tmLanguage.json", textMateGrammarAsset("index.ts"))
        assertEquals("JSON.tmLanguage.json", textMateGrammarAsset("config.json"))
        assertEquals(null, textMateGrammarAsset("README.txt"))
    }

    @Test fun `maps mainstream source file extensions to bundled TextMate grammars`() {
        val expected = mapOf(
            "main.kt" to "kotlin.tmLanguage.json", "index.js" to "JavaScript.tmLanguage.json",
            "index.ts" to "textmate-typescript.tmLanguage.json", "component.tsx" to "textmate-tsx.tmLanguage.json",
            "main.java" to "textmate-java.tmLanguage.json", "main.py" to "textmate-python.tmLanguage.json",
            "build.gradle" to "textmate-groovy.tmLanguage.json", "script.lua" to "textmate-lua.tmLanguage.json",
            "main.m" to "textmate-objective-c.tmLanguage.json", "script.pl" to "textmate-perl.tmLanguage.json",
            "module.ps1" to "textmate-powershell.tmLanguage.json", "analysis.r" to "textmate-r.tmLanguage.json",
            "main.scala" to "textmate-scala.tmLanguage.json",
            "main.go" to "textmate-go.tmLanguage.json", "main.rs" to "textmate-rust.tmLanguage.json",
            "main.c" to "textmate-c.tmLanguage.json", "main.cpp" to "textmate-cpp.tmLanguage.json",
            "main.cs" to "textmate-csharp.tmLanguage.json", "main.swift" to "textmate-swift.tmLanguage.json",
            "main.dart" to "textmate-dart.tmLanguage.json", "main.rb" to "textmate-ruby.tmLanguage.json",
            "main.php" to "textmate-php.tmLanguage.json", "run.sh" to "textmate-shellscript.tmLanguage.json",
            "query.sql" to "textmate-sql.tmLanguage.json", "index.html" to "textmate-html.tmLanguage.json",
            "style.css" to "textmate-css.tmLanguage.json", "view.xml" to "textmate-xml.tmLanguage.json",
            "config.json" to "JSON.tmLanguage.json",
        )
        expected.forEach { (path, grammar) -> assertEquals(path, grammar, textMateGrammarAsset(path)) }
    }

    @Test fun `bundled mainstream grammars tokenize representative source`() {
        // One sample per shipped grammar. This engine is a port of vscode-textmate onto Joni, and a
        // grammar that fails there is swallowed at runtime and rendered as plain text, so the sample
        // is the only thing that catches an unsupported regex before users see an uncolored block.
        val examples = mapOf(
            "textmate-typescript.tmLanguage.json" to "const answer: number = 42;",
            "textmate-tsx.tmLanguage.json" to "const View = () => <div />;",
            "textmate-java.tmLanguage.json" to "public class Main { int answer = 42; }",
            "textmate-python.tmLanguage.json" to "def answer(): return 42",
            "textmate-groovy.tmLanguage.json" to "class Main { static int answer = 42 }",
            "textmate-lua.tmLanguage.json" to "function answer() return 42 end",
            "textmate-objective-c.tmLanguage.json" to "int main() { return 42; }",
            "textmate-perl.tmLanguage.json" to "my \$answer = 42;",
            "textmate-powershell.tmLanguage.json" to "function Get-Answer { return 42 }",
            "textmate-r.tmLanguage.json" to "answer <- 42",
            "textmate-scala.tmLanguage.json" to "object Main { val answer: Int = 42 }",
            "textmate-go.tmLanguage.json" to "package main\nfunc main() { println(42) }",
            "textmate-rust.tmLanguage.json" to "fn main() { let answer: i32 = 42; }",
            "textmate-c.tmLanguage.json" to "int main(void) { return 42; }",
            "textmate-cpp.tmLanguage.json" to "class Main { int answer = 42; };",
            "textmate-csharp.tmLanguage.json" to "public class Main { int answer = 42; }",
            "textmate-swift.tmLanguage.json" to "func answer() -> Int { return 42 }",
            "textmate-dart.tmLanguage.json" to "void main() { final answer = 42; }",
            "textmate-ruby.tmLanguage.json" to "def answer\n  42\nend",
            "textmate-php.tmLanguage.json" to "<?php function answer() { return 42; }",
            "textmate-shellscript.tmLanguage.json" to "#!/bin/bash\necho 42",
            "textmate-sql.tmLanguage.json" to "SELECT answer FROM values_table;",
            "textmate-html.tmLanguage.json" to "<div class=\"answer\">42</div>",
            "textmate-css.tmLanguage.json" to ".answer { color: red; }",
            "textmate-xml.tmLanguage.json" to "<answer value=\"42\" />",
            "textmate-elixir.tmLanguage.json" to "defmodule Demo do\n  def hi, do: :ok\nend",
            "textmate-erlang.tmLanguage.json" to "-module(demo).\nstart() -> ok.",
            "textmate-haskell.tmLanguage.json" to "main :: IO ()\nmain = print 42",
            "textmate-clojure.tmLanguage.json" to "(defn greet [name] (println name))",
            "textmate-ocaml.tmLanguage.json" to "let rec fact n = if n <= 1 then 1 else n * fact (n - 1)",
            "textmate-fsharp.tmLanguage.json" to "let rec fact n = if n <= 1 then 1 else n * fact (n - 1)",
            "textmate-zig.tmLanguage.json" to "const std = @import(\"std\");\npub fn main() void {}",
            "textmate-hcl.tmLanguage.json" to "resource \"aws_instance\" \"web\" {\n  ami = \"ami-123\"\n}",
            "textmate-terraform.tmLanguage.json" to "resource \"aws_instance\" \"web\" {\n  instance_type = var.kind\n}",
            "textmate-graphql.tmLanguage.json" to "query Load(${'$'}id: ID!) { user(id: ${'$'}id) { id } }",
            "textmate-proto.tmLanguage.json" to "syntax = \"proto3\";\nmessage User { string name = 1; }",
            "textmate-cmake.tmLanguage.json" to "cmake_minimum_required(VERSION 3.20)\nproject(demo)",
            "textmate-nix.tmLanguage.json" to "{ pkgs ? import <nixpkgs> {} }:\npkgs.mkShell { buildInputs = [ pkgs.go ]; }",
            "textmate-julia.tmLanguage.json" to "function fib(n::Int)\n    n <= 1 ? n : fib(n - 1)\nend",
            "textmate-asm.tmLanguage.json" to "global _start\nsection .text\n_start:\n    mov eax, 1\n    ret",
            "textmate-bat.tmLanguage.json" to "@echo off\nsetlocal\nfor %%f in (*.txt) do echo %%f",
            "textmate-vb.tmLanguage.json" to "Module Demo\n    Sub Main()\n    End Sub\nEnd Module",
            "textmate-pascal.tmLanguage.json" to "program Demo;\nbegin\n  writeln('hi');\nend.",
            "textmate-solidity.tmLanguage.json" to "pragma solidity ^0.8.0;\ncontract Demo { uint256 public value; }",
            "textmate-verilog.tmLanguage.json" to "module demo(input clk);\nalways @(posedge clk) q <= ~q;\nendmodule",
            "textmate-vhdl.tmLanguage.json" to "entity demo is\n  port (clk : in std_logic);\nend entity;",
            "textmate-latex.tmLanguage.json" to "\\documentclass{article}\n\\begin{document}\n\\section{Hi} ${'$'}x^2${'$'}\n\\end{document}",
            "textmate-markdown.tmLanguage.json" to "# Title\n\nSome **bold** and `code`.",
            "textmate-mdx.tmLanguage.json" to "# Title\n\n<Component prop={1} />",
            "textmate-vue.tmLanguage.json" to "<template><p>{{ msg }}</p></template>\n<script setup lang=\"ts\">const msg: string = \"hi\"</script>",
            "textmate-svelte.tmLanguage.json" to "<script lang=\"ts\">let name: string = 'x';</script>\n<h1>Hello {name}</h1>",
            "textmate-astro.tmLanguage.json" to "---\nconst title = 'Hi';\n---\n<h1>{title}</h1>",
            "textmate-awk.tmLanguage.json" to "BEGIN { FS = \",\" }\n{ total += ${'$'}2 }",
            "textmate-diff.tmLanguage.json" to "@@ -1,3 +1,3 @@\n-old line\n+new line",
            "textmate-dotenv.tmLanguage.json" to "# comment\nAPI_KEY=secret",
            "textmate-http.tmLanguage.json" to "GET https://example.com HTTP/1.1\nAccept: application/json",
            "textmate-jsonl.tmLanguage.json" to "{\"a\": 1}\n{\"b\": 2}",
            "textmate-coffee.tmLanguage.json" to "square = (x) -> x * x",
        )
        examples.forEach { (grammar, code) ->
            val rendered = try {
                highlighter(grammar).highlight(listOf(code), 0)
            } catch (error: Exception) {
                throw AssertionError("Failed to load/tokenize $grammar", error)
            }
            assertEquals(grammar, code, rendered.text)
            assertTrue("No token spans from $grammar", rendered.spanStyles.isNotEmpty())
            assertTrue("No themed tokens from $grammar", rendered.spanStyles.any {
                it.item.color != androidx.compose.ui.graphics.Color.Black &&
                    it.item.color != androidx.compose.ui.graphics.Color.Unspecified
            })
        }
    }

    @Test fun `maps previously misclassified files to their own grammars`() {
        val expected = mapOf(
            "Dockerfile" to "textmate-docker.tmLanguage.json",
            "Makefile" to "textmate-make.tmLanguage.json",
            "tasks.mk" to "textmate-make.tmLanguage.json",
            "style.scss" to "textmate-scss.tmLanguage.json",
            "style.less" to "textmate-less.tmLanguage.json",
            "config.ini" to "textmate-ini.tmLanguage.json",
            "config.jsonc" to "textmate-jsonc.tmLanguage.json",
            "config.json5" to "textmate-json5.tmLanguage.json",
            "config.yaml" to "textmate-yaml.tmLanguage.json",
            "config.yml" to "textmate-yaml.tmLanguage.json",
            "config.toml" to "textmate-toml.tmLanguage.json",
        )
        expected.forEach { (path, asset) ->
            assertEquals(path, asset, textMateGrammarAsset(path))
            assertTrue(highlighter(asset).highlight(listOf("a: 42 // comment"), 0).spanStyles.isNotEmpty())
        }
        assertEquals("textmate-markdown.tmLanguage.json", textMateGrammarAsset("notes.rmd"))
        assertEquals("textmate-shellscript.tmLanguage.json", textMateGrammarAssetForLanguage("bash title=demo"))
        assertEquals("textmate-shellscript.tmLanguage.json", textMateGrammarAssetForLanguage("text"))
        assertEquals("textmate-shellscript.tmLanguage.json", textMateGrammarAssetForLanguage("plaintext"))
        assertEquals("textmate-powershell.tmLanguage.json", textMateGrammarAssetForLanguage("powershell"))
        assertEquals("textmate-scss.tmLanguage.json", textMateGrammarAssetForLanguage("scss"))
        // Common Markdown fence aliases must resolve to the same grammar as their canonical name.
        assertEquals("textmate-python.tmLanguage.json", textMateGrammarAssetForLanguage("python"))
        assertEquals("JavaScript.tmLanguage.json", textMateGrammarAssetForLanguage("js"))
        assertEquals("kotlin.tmLanguage.json", textMateGrammarAssetForLanguage("kt"))
        assertEquals("textmate-ruby.tmLanguage.json", textMateGrammarAssetForLanguage("ruby"))
        assertEquals("textmate-rust.tmLanguage.json", textMateGrammarAssetForLanguage("rust"))
        assertEquals("textmate-shellscript.tmLanguage.json", textMateGrammarAssetForLanguage("console"))
        assertEquals("textmate-vue.tmLanguage.json", textMateGrammarAssetForLanguage("vue"))
        assertEquals("textmate-svelte.tmLanguage.json", textMateGrammarAssetForLanguage("svelte"))
        assertEquals("textmate-yaml.tmLanguage.json", textMateGrammarAssetForLanguage("docker-compose"))
        assertEquals("textmate-perl.tmLanguage.json", textMateGrammarAssetForLanguage("perl"))
    }

    @Test fun `Markdown fenced Python and JavaScript code gets syntax colors`() {
        listOf(
            "python" to "def greet(name):\n    return f\"hi {name}\"",
            "js" to "const answer = 42; function greet(name) { return name; }",
            "vue" to "<template><p>{{\u0020message\u0020}}</p></template>",
        ).forEach { (language, code) ->
            val grammar = textMateGrammarAssetForLanguage(language)
            assertTrue(language, grammar != null)
            val rendered = highlightFencedCode(code, grammar!!, "textmate-light.json") { assets.resolve(it).inputStream() }
            assertEquals(code, rendered.text)
            assertTrue(language, rendered.spanStyles.isNotEmpty())
        }
    }

    @Test fun `Markdown fenced Kotlin code gets syntax colors and oversized code stays plain`() {
        val code = "val answer = 42 // comment"
        val grammar = textMateGrammarAssetForLanguage("kotlin")
        val rendered = highlightFencedCode(code, grammar, "textmate-light.json") { assets.resolve(it).inputStream() }
        assertEquals(code, rendered.text)
        assertTrue(rendered.spanStyles.isNotEmpty())
        val large = code.repeat(1024)
        val plain = highlightFencedCode(large, grammar, "textmate-light.json") { assets.resolve(it).inputStream() }
        assertEquals(large, plain.text)
        assertTrue(plain.spanStyles.isEmpty())
    }

    @Test fun `HTML embedded JavaScript and CSS resolve only required grammar assets`() {
        val loaded = mutableSetOf<String>()
        val tokenizer = TextMateCodeHighlighter(
            assets.resolve("textmate-html.tmLanguage.json").inputStream(),
            assets.resolve("textmate-light.json").inputStream(),
        ) { scope ->
            textMateGrammarAssetForScope(scope)?.let { asset ->
                loaded += scope
                assets.resolve(asset).inputStream()
            }
        }
        val code = "<script>const answer = 42;</script><style>body { color: red; }</style>"
        val rendered = tokenizer.highlight(listOf(code), 0)
        assertEquals(code, rendered.text)
        assertTrue("Embedded JavaScript grammar was not loaded", "source.js" in loaded)
        assertTrue("Embedded CSS grammar was not loaded", "source.css" in loaded)
        assertTrue("Unrelated grammar loaded", "source.ruby" !in loaded)
    }

    @Test fun `one highlighter keeps a shared card's command and output apart`() {
        // A terminal card parses one grammar for its command box and every output block. The two
        // documents start their line indices at zero, so a cache keyed by index alone made the
        // output repeat the command's lines (and the command borrow output lines).
        val tokenizer = highlighter("textmate-shellscript.tmLanguage.json")
        val command = listOf(
            "echo \"=== address ===\"; hostname -I",
            "echo",
            "echo \"=== listener ===\"",
        )
        val output = listOf(
            "=== address ===",
            "192.0.2.10",
            "=== listener ===",
        )

        command.indices.forEach { index ->
            assertEquals("command line $index", command[index], tokenizer.highlight(command, index).text)
        }
        output.indices.forEach { index ->
            assertEquals("output line $index", output[index], tokenizer.highlight(output, index).text)
        }
        command.indices.forEach { index ->
            assertEquals("command line $index after output", command[index], tokenizer.highlight(command, index).text)
        }
    }

    @Test fun `colors Kotlin keywords while retaining multiline string state across chunks`() {
        val chunks = listOf("val a = \"\"\"sealed\n", "interface\"\"\"\nsealed interface Result\n")
        val rendered = highlighter().highlight(chunks, 1)
        assertEquals(chunks[1], rendered.text)
        val inside = chunks[1].indexOf("interface")
        val keyword = chunks[1].lastIndexOf("interface")
        val insideColor = rendered.spanStyles.last { it.start <= inside && it.end >= inside + 9 }.item.color
        val keywordColor = rendered.spanStyles.last { it.start <= keyword && it.end >= keyword + 9 }.item.color
        assertNotEquals(insideColor, keywordColor)
    }

    @Test fun `revisiting a styled chunk does not lose its preceding parse state`() {
        val chunks = listOf("/* comment\n", "sealed */\nsealed class Thing\n")
        val tokenizer = highlighter()
        val first = tokenizer.highlight(chunks, 1)
        assertEquals(first, tokenizer.highlight(chunks, 1))
        assertTrue(first.spanStyles.isNotEmpty())
    }

    @Test fun `reset boundary prevents deleted multiline string from coloring added keyword`() {
        val chunks = listOf("val old = \"\"\"", "val fresh = 42")
        val tokenizer = highlighter()
        val withoutReset = tokenizer.highlight(chunks, 1)
        val resetTokenizer = highlighter()
        val withReset = resetTokenizer.highlight(chunks, 1, setOf(1))
        val keyword = 0
        val stringColor = withoutReset.spanStyles.last { it.start <= keyword && it.end > keyword }.item.color
        val keywordColor = withReset.spanStyles.last { it.start <= keyword && it.end > keyword }.item.color
        assertNotEquals(stringColor, keywordColor)
    }

    @Test fun `oversized fragments reset state and later code resumes highlighting`() {
        val chunks = listOf("\"\"\"" + "x".repeat(16_385), "sealed interface Result")
        val tokenizer = highlighter()
        assertTrue(tokenizer.highlight(chunks, 0).spanStyles.isEmpty())
        val following = tokenizer.highlight(chunks, 1)
        assertEquals(chunks[1], following.text)
        assertTrue(following.spanStyles.isNotEmpty())
        assertEquals(following, tokenizer.highlight(chunks, 1))
    }

    @Test fun `Kotlin changed-file diff routes visible lines through TextMate`() {
        val chunks = listOf("fun answer(): Int {", "    return 42", "}")
        val tokenizer = highlighter()
        val expected = tokenizer.highlight(chunks, 1)
        val rendered = highlightPreviewCode(chunks[1], "src/Main.kt", false, tokenizer, chunks, 1)
        assertEquals(expected, rendered)
        assertTrue(rendered.spanStyles.isNotEmpty())
    }

    @Test fun `Kotlin preview retains library syntax colors while grammar is still loading`() {
        val code = "val answer = 42 // comment"
        val rendered = highlightPreviewCode(code, "src/Main.kt", false, null, listOf(code), 0)
        assertEquals(code, rendered.text)
        assertTrue(rendered.spanStyles.any { it.item.color != androidx.compose.ui.graphics.Color.Unspecified })
    }

    @Test fun `JSON grammar colors property keys independently of values`() {
        val code = "{\"enabled\": true}"
        val rendered = highlighter("JSON.tmLanguage.json").highlight(listOf(code), 0)
        assertEquals(code, rendered.text)
        assertTrue(rendered.spanStyles.isNotEmpty())
    }

    @Test fun `newly covered file formats resolve to their grammar`() {
        // One case per mapping table: extension, well-known filename, and dotfile.
        val expected = mapOf(
            "src/App.vue" to "textmate-vue.tmLanguage.json",
            "README.md" to "textmate-markdown.tmLanguage.json",
            "infra/main.tf" to "textmate-terraform.tmLanguage.json",
            "api/user.proto" to "textmate-proto.tmLanguage.json",
            "Cargo.lock" to "textmate-toml.tmLanguage.json",
            ".env" to "textmate-dotenv.tmLanguage.json",
        )
        expected.forEach { (path, asset) -> assertEquals(path, asset, textMateGrammarAsset(path)) }
    }

    @Test fun `fence tags for newly covered languages resolve to their grammar`() {
        assertEquals("textmate-csharp.tmLanguage.json", textMateGrammarAssetForLanguage("csharp"))
        assertEquals("textmate-terraform.tmLanguage.json", textMateGrammarAssetForLanguage("terraform"))
        assertEquals("textmate-graphql.tmLanguage.json", textMateGrammarAssetForLanguage("graphql"))
        assertEquals("textmate-vue.tmLanguage.json", textMateGrammarAssetForLanguage("vue"))
    }

    @Test fun `every grammar asset the highlighter references is bundled`() {
        // A mapping that names an unshipped asset fails silently at runtime: the block renders as
        // plain text instead of reporting anything. Deriving the names from the source keeps this
        // guard from becoming a second copy of the mapping tables.
        val source = File("src/main/java/dev/dsh/mobile/mesh/ui/components/TextMateCodeHighlighter.kt").readText()
        val referenced = Regex("\"[\\w.-]+\\.tmLanguage\\.json\"").findAll(source)
            .map { it.value.removeSurrounding("\"") }.toSet()
        assertTrue("No grammar assets referenced by the highlighter", referenced.isNotEmpty())
        assertEquals("Referenced grammar assets are missing", emptyList<String>(), referenced.filterNot { assets.resolve(it).isFile })
    }

    @Test fun `named embedded includes resolve to their base grammar`() {
        assertEquals("textmate-typescript.tmLanguage.json", textMateGrammarAssetForScope("source.ts#type"))
        assertEquals("textmate-css.tmLanguage.json", textMateGrammarAssetForScope("source.css#property-names"))
        assertEquals("textmate-vue.tmLanguage.json", textMateGrammarAssetForScope("text.html.vue"))
        assertEquals("textmate-graphql.tmLanguage.json", textMateGrammarAssetForScope("source.graphql"))
    }

    @Test fun `bundled themes color declarations and not only keywords`() {
        // The themes shipped before this change colored keywords, strings, comments and numbers but
        // left every function name, type, and parameter at the default foreground, which is what
        // makes a highlighted block look almost unhighlighted.
        val code = "internal fun greet(name: String): String {\n    return \"hi \" + name\n}"
        listOf("textmate-light.json", "textmate-dark.json").forEach { themeAsset ->
            val theme = assets.resolve(themeAsset).inputStream().use(ThemeReader::readTheme)
            val rendered = TextMateCodeHighlighter(
                assets.resolve("kotlin.tmLanguage.json").inputStream(),
                assets.resolve(themeAsset).inputStream(),
            ).highlight(listOf(code), 0)
            val defaultColor = Color(theme.defaultStyle.foreground.toInt())
            fun colorAt(token: String): Color {
                val index = code.indexOf(token)
                return rendered.spanStyles.last { it.start <= index && it.end > index }.item.color
            }
            assertNotEquals("$themeAsset left the function name at the default color", defaultColor, colorAt("greet"))
            assertNotEquals("$themeAsset left the type name at the default color", defaultColor, colorAt("String"))
            assertNotEquals("$themeAsset painted the function name like a keyword", colorAt("fun"), colorAt("greet"))
        }
    }
}
