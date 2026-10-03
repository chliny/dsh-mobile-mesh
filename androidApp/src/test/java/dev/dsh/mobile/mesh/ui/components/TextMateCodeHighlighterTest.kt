package dev.dsh.mobile.mesh.ui.components

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

    @Test fun `oversized fragments do not tokenize or reuse an invalid cross-chunk state`() {
        val chunks = listOf("x".repeat(16_385), "sealed interface Result")
        val tokenizer = highlighter()
        assertTrue(tokenizer.highlight(chunks, 0).spanStyles.isEmpty())
        assertTrue(tokenizer.highlight(chunks, 1).spanStyles.isEmpty())
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
}
