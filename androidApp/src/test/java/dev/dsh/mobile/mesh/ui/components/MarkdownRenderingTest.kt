package dev.dsh.mobile.mesh.ui.components

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRenderingTest {
    @Test
    fun `task list markers render as checked and unchecked items`() {
        val block = parseMarkdown("- [ ] pending\n- [x] complete\n- regular").single() as MdBlock.MdList

        assertEquals(
            listOf(
                MdBlock.MdListItem("pending", checked = false),
                MdBlock.MdListItem("complete", checked = true),
                MdBlock.MdListItem("regular"),
            ),
            block.items,
        )
    }

    @Test
    fun `ordered task list markers retain checked state and list numbering`() {
        val block = parseMarkdown("1. [X] done\n2. [ ] next").single() as MdBlock.MdList

        assertTrue(block.ordered)
        assertEquals(listOf(true, false), block.items.map { it.checked })
        assertEquals(listOf("done", "next"), block.items.map { it.text })
    }

    @Test
    fun `markdown tables expose trimmed cell content without separator row`() {
        val table = parseMarkdown("| name | value |\n| --- | :---: |\n| answer | 42 |").single() as MdBlock.Table

        assertEquals(listOf("name", "value"), markdownTableCells(table.rows[0]))
        assertEquals(listOf("answer", "42"), markdownTableCells(table.rows[1]))
    }

    @Test
    fun `sixth level headings parse as headings`() {
        assertEquals(MdBlock.Heading(6, "detail"), parseMarkdown("###### detail").single())
    }

    @Test
    fun `strikethrough produces decorated inline text`() {
        val annotated = buildInlineContent(
            text = "before ~~removed~~ after",
            codeStyle = TextStyle(fontFamily = FontFamily.Monospace),
            colors = dev.dsh.mobile.mesh.ui.theme.DsThemeTokens.light,
            onOpenUri = {},
        )

        assertEquals("before removed after", annotated.text)
        assertTrue(annotated.spanStyles.any { range ->
            range.item.textDecoration == TextDecoration.LineThrough && annotated.text.substring(range.start, range.end) == "removed"
        })
    }
}
