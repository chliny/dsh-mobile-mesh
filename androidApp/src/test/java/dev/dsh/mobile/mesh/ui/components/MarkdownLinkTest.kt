package dev.dsh.mobile.mesh.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownLinkTest {
    @Test
    fun `CommonMark renders links with escaped destinations and labels`() {
        val html = renderCommonMarkFragment("[open & docs](https://example.test/a?x=1&y=2)")

        assertTrue(html.contains("open &amp; docs"))
        assertTrue(html.contains("href=\"https://example.test/a?x=1&amp;y=2\""))
    }

    @Test
    fun `workspace link handler token round trips spaces fragments and unicode`() {
        val target = "docs/配置 guide.md#L10-L12"

        assertEquals(target, decodeMarkdownLink(encodeMarkdownLink(target)))
    }

    @Test
    fun `relative document links resolve from the reserved local base without retaining its host`() {
        assertEquals(
            "docs/next file.md?view=full#section",
            relativeMarkdownNavigationTarget("https://markdown.invalid/docs/next%20file.md?view=full#section"),
        )
        assertEquals(null, relativeMarkdownNavigationTarget("https://example.test/docs/next.md"))
        assertEquals(null, relativeMarkdownNavigationTarget("https://markdown.invalid/#heading"))
    }

    @Test
    fun `unrecognized navigation URLs are not mistaken for Markdown links`() {
        assertEquals(null, decodeMarkdownLink("https://example.test/path"))
        assertEquals(null, decodeMarkdownLink("dsh-markdown://unknown/token"))
    }
}
