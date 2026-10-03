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
        assertEquals(null, textMateGrammarAsset("index.ts"))
        assertEquals("JSON.tmLanguage.json", textMateGrammarAsset("config.json"))
        assertEquals(null, textMateGrammarAsset("README.txt"))
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
