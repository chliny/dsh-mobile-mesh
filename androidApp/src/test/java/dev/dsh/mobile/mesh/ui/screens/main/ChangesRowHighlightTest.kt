package dev.dsh.mobile.mesh.ui.screens.main

import dev.dsh.mobile.mesh.core.wire.dto.ChangesDiffHunk
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression guard: expanded turn-change rows pass source text and file path to syntax highlighting. */
class ChangesRowHighlightTest {
    @Test
    fun `expanded changed-file preview highlights source lines with the file grammar`() {
        val source = File("src/main/java/dev/dsh/mobile/mesh/ui/screens/main/ChangesRow.kt").readText()
        assertTrue(source.contains("textMateGrammarAsset(path)"))
        assertTrue(source.contains("TextMateCodeHighlighter(assets.open(grammarAsset), assets.open(themeAsset))"))
        assertTrue(source.contains("textMateGrammarAssetForScope(scope)"))
        assertTrue(source.contains("KodeViewCode("))
        assertTrue(source.contains("code = line.source"))
        assertTrue(source.contains("pathOrLanguage = value.path"))
        assertTrue(source.contains("textMate = textMate"))
        assertTrue(source.contains("chunks = plan.chunks"))
        assertTrue(source.contains("chunkIndex = line.chunkIndex"))
        assertTrue(source.contains("resetAt = plan.resetAt"))
    }

    @Test fun `deleted and added lines have separate parse streams and each hunk resets`() {
        val hunks = listOf(
            ChangesDiffHunk(1, 2, 1, 2, listOf("-val old = \"\"\"", "+val new = 42", " return new")),
            ChangesDiffHunk(20, 1, 20, 1, listOf(" val next = 7")),
        )
        val plan = buildDiffHighlightPlan(hunks)
        assertEquals(listOf('-', '+', ' ', ' '), plan.lines.map { it.marker })
        assertEquals("val old = \"\"\"", plan.chunks[plan.lines[0].chunkIndex])
        assertEquals("val new = 42", plan.chunks[plan.lines[1].chunkIndex])
        assertTrue(plan.lines[1].chunkIndex in plan.resetAt)
        assertTrue(plan.lines[3].chunkIndex in plan.resetAt)
        assertEquals("return new", plan.chunks[plan.lines[2].chunkIndex])
    }
}
