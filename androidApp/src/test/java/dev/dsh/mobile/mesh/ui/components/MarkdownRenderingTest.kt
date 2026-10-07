package dev.dsh.mobile.mesh.ui.components

import androidx.compose.ui.Alignment
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRenderingTest {
    @Test
    fun `list markers align to the top of wrapped text`() {
        assertEquals(Alignment.Top, markdownListMarkerAlignment)
    }

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
    fun `single backtick code spans render in monospace with inline code background`() {
        val colors = dev.dsh.mobile.mesh.ui.theme.DsThemeTokens.light
        val annotated = buildInlineContent(
            text = "Use `config.yaml` here",
            codeStyle = TextStyle(fontFamily = FontFamily.Monospace),
            colors = colors,
            onOpenUri = {},
        )

        assertEquals("Use config.yaml here", annotated.text)
        val codeSpan = annotated.spanStyles.single { it.item.fontFamily == FontFamily.Monospace }
        assertEquals("config.yaml", annotated.text.substring(codeSpan.start, codeSpan.end))
        assertEquals(colors.inlineCode, codeSpan.item.background)
    }

    @Test
    fun `markdown tables expose trimmed cell content without separator row`() {
        val table = parseMarkdown("| name | value |\n| --- | :---: |\n| answer | 42 |").single() as MdBlock.Table

        assertEquals(listOf("name", "value"), markdownTableCells(table.rows[0]))
        assertEquals(listOf("answer", "42"), markdownTableCells(table.rows[1]))
    }

    @Test
    fun `markdown tables without outer pipes render as tables`() {
        val table = parseMarkdown("field | type\n--- | ---\nvalue | string").single() as MdBlock.Table

        assertEquals(listOf("field", "type"), markdownTableCells(table.rows[0]))
        assertEquals(listOf("value", "string"), markdownTableCells(table.rows[1]))
    }

    @Test
    fun `horizontal rules parse as standalone blocks`() {
        assertEquals(
            listOf(MdBlock.Paragraph(listOf("before")), MdBlock.HorizontalRule, MdBlock.Paragraph(listOf("after"))),
            parseMarkdown("before\n\n---\n\nafter"),
        )
    }

    @Test
    fun `markdown table rows share columns so vertical boundaries stay aligned`() {
        assertEquals(
            listOf(
                listOf("field", "type", "notes"),
                listOf("name", "string", "detail"),
                listOf("count", "integer", ""),
            ),
            markdownTableRows(listOf("| field | type | notes |", "| name | string | detail |", "| count | integer |")),
        )
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
