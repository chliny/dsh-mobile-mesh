package dev.dsh.mobile.mesh.ui.components

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolDiffHighlightTest {
    @Test fun `edit replacement is a unified git diff with shared context only once`() {
        val plan = toolDiffHighlightPlan(DiffHunk(
            "src/Main.kt",
            "fun main() {\n    val old = 1\n    println(old)\n}",
            "fun main() {\n    val fresh = 2\n    println(old)\n}",
        ))
        assertEquals(listOf(' ', '-', '+', ' ', ' '), plan.lines.map { it.marker })
        assertEquals(listOf("fun main() {", "    val old = 1", "    val fresh = 2", "    println(old)", "}"), plan.chunks)
        assertEquals(plan.chunks.indices.toList(), plan.lines.map { it.chunkIndex })
        assertEquals(listOf(' ', '-', '+', ' ', ' '), plan.markers)

        val assets = File("src/main/assets")
        val tokenizer = TextMateCodeHighlighter(
            assets.resolve("kotlin.tmLanguage.json").inputStream(),
            assets.resolve("textmate-light.json").inputStream(),
        )
        val rendered = tokenizer.highlightDiff(plan.chunks, plan.markers, 2)
        assertEquals("    val fresh = 2", rendered.text)
        assertTrue(rendered.spanStyles.isNotEmpty())
    }

    @Test fun `unified diff keeps added Kotlin outside deleted multiline string`() {
        val assets = File("src/main/assets")
        fun tokenizer() = TextMateCodeHighlighter(
            assets.resolve("kotlin.tmLanguage.json").inputStream(),
            assets.resolve("textmate-light.json").inputStream(),
        )
        val lines = listOf("val old = \"\"\"", "val fresh = 42")
        val rendered = tokenizer().highlightDiff(lines, listOf('-', '+'), 1)
        val incorrectlyContinued = tokenizer().highlight(lines, 1)
        val addedColor = rendered.spanStyles.last { it.start == 0 }.item.color
        val oldStringColor = incorrectlyContinued.spanStyles.last { it.start == 0 }.item.color
        assertNotEquals(oldStringColor, addedColor)
        assertEquals(rendered, tokenizer().highlightDiff(listOf(lines[1]), listOf('+'), 0))
    }

    @Test fun `large edits do not allocate quadratic diff table`() {
        val old = (0..149).joinToString("\n") { "old$it" }
        val new = (0..149).joinToString("\n") { "new$it" }
        val lines = unifiedDiffLines(old, new)
        assertEquals(300, lines.size)
        assertEquals(List(150) { '-' } + List(150) { '+' }, lines.map { it.marker })
    }

    @Test fun `empty sides and trailing newline remain represented`() {
        val removedOnly = toolDiffHighlightPlan(DiffHunk("file.ts", "const x = 1\n", null))
        assertEquals(listOf('-', '-'), removedOnly.lines.map { it.marker })
        assertEquals(listOf("const x = 1", ""), removedOnly.chunks)
        val addedOnly = toolDiffHighlightPlan(DiffHunk("file.ts", null, "const z = 3"))
        assertEquals(listOf('+'), addedOnly.lines.map { it.marker })
        assertEquals(listOf("const z = 3"), addedOnly.chunks)
    }

    @Test fun `expanded edit diff routes unified sequence through TextMate`() {
        val source = File("src/main/java/dev/dsh/mobile/mesh/ui/components/ToolCards.kt").readText()
        val body = source.substringAfter("private fun CombinedDiffBlock").substringBefore("private fun SearchBody")
        assertTrue(body.contains("textMateGrammarAsset(hunk.path)"))
        assertTrue(body.contains("textMateGrammarAssetForScope(scope)"))
        assertTrue(body.contains("KodeViewCode("))
        assertTrue(body.contains("chunks = plan.chunks"))
        assertTrue(body.contains("diffMarkers = plan.markers"))
        assertTrue(body.contains("sourceVersion = version"))
        assertTrue(body.contains("colors.codeBlockBg"))
    }
}
