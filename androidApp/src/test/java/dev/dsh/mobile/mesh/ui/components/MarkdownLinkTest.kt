package dev.dsh.mobile.mesh.ui.components

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownLinkTest {
    @Test
    fun `markdown link is clickable and retains surrounding selectable text`() {
        var opened: String? = null
        val annotated = buildInlineContent(
            text = "Before [open docs](https://example.com/docs) after",
            codeStyle = TextStyle(fontFamily = FontFamily.Monospace),
            colors = dev.dsh.mobile.mesh.ui.theme.DsThemeTokens.light,
            onOpenUri = { opened = it },
        )

        assertEquals("Before open docs after", annotated.text)
        assertEquals(emptyList<Any>(), annotated.getLinkAnnotations(0, 6))
        assertEquals(emptyList<Any>(), annotated.getLinkAnnotations(16, annotated.length))
        val link = annotated.getLinkAnnotations(7, 16).single().item as LinkAnnotation.Clickable
        assertEquals("https://example.com/docs", link.tag)
        link.linkInteractionListener?.onClick(link)
        assertEquals("https://example.com/docs", opened)
    }
}
