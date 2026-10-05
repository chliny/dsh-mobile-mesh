package dev.dsh.mobile.mesh.ui.components

import dev.snipme.highlights.model.SyntaxLanguage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalCodeHighlightTest {
    @Test fun `bash grammar highlights shell commands and terminal output`() {
        val assets = File("src/main/assets")
        val grammar = textMateGrammarAssetForLanguage("bash")
        assertEquals("textmate-shellscript.tmLanguage.json", grammar)
        val tokenizer = TextMateCodeHighlighter(
            assets.resolve(grammar!!).inputStream(),
            assets.resolve("textmate-light.json").inputStream(),
        )
        val command = tokenizer.highlight(listOf("if [ -f file ]; then echo \$HOME; fi"), 0)
        val output = tokenizer.highlight(listOf("warning: command not found"), 0)
        assertTrue(command.spanStyles.isNotEmpty())
        assertTrue(output.spanStyles.isNotEmpty())
    }

    @Test fun `a bare language name resolves in the fallback highlighter`() {
        // TextMate carries the command rows; this is the fallback when a grammar fails to load.
        assertEquals(SyntaxLanguage.SHELL, kodeviewLanguage("bash"))
        assertEquals(SyntaxLanguage.SHELL, kodeviewLanguage("sh"))
        assertEquals(SyntaxLanguage.DEFAULT, kodeviewLanguage("powershell"))
    }

    @Test fun `terminal command and each output block share one shell highlighter`() {
        val source = File("src/main/java/dev/dsh/mobile/mesh/ui/components/ToolCards.kt").readText()
        val body = source.substringAfter("private fun TerminalBody").substringBefore("private fun DiffBody")
        assertTrue(body.contains("val textMate = rememberTextMateHighlighterForLanguage(\"bash\")"))
        assertTrue(body.contains("code = command,\n                        textMate = textMate,"))
        assertTrue(body.contains("TerminalCode(code = block, textMate = textMate"))
        // One grammar load per card: a card with many output blocks must not reparse it per block.
        assertEquals(1, Regex("rememberTextMateHighlighterForLanguage\\(").findAll(body).count())
    }

    @Test fun `terminal rows are shell chunks of one block so multi-line state carries over`() {
        val highlighter = File("src/main/java/dev/dsh/mobile/mesh/ui/components/HighlightedCode.kt").readText()
        assertTrue(highlighter.contains("textMateGrammarAssetForLanguage(language)"))
        assertTrue(highlighter.contains("language = \"bash\""))
        assertTrue(highlighter.contains("chunks = lines"))
        assertTrue(highlighter.contains("chunkIndex = index"))
        assertTrue(highlighter.contains("sourceVersion = code"))
    }

    @Test fun `generic shell fallback keeps its language hint and highlights text output`() {
        val mapping = File("src/main/java/dev/dsh/mobile/mesh/ui/screens/main/ToolCardMapping.kt").readText()
        assertTrue(mapping.contains("language = shellOutputLanguage(call.name)"))
        assertTrue(mapping.contains("\"bash\" -> \"bash\""))
        assertTrue(mapping.contains("\"pwsh\" -> \"powershell\""))
        val cards = File("src/main/java/dev/dsh/mobile/mesh/ui/components/ToolCards.kt").readText()
        val generic = cards.substringAfter("private fun GenericBody").substringBefore("private fun SectionLabel")
        assertTrue(generic.contains("rememberTextMateHighlighterForLanguage(language)"))
        assertTrue(generic.contains("HighlightedCodeLines("))
    }
}
