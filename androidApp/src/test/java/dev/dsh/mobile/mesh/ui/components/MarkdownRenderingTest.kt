package dev.dsh.mobile.mesh.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRenderingTest {
    @Test
    fun `commonmark renders all heading levels and setext headings`() {
        val html = renderCommonMarkFragment("# one\n\n###### six\n\nsetext\n===")

        assertTrue(html.contains("<h1>one</h1>"))
        assertTrue(html.contains("<h6>six</h6>"))
        assertTrue(html.contains("<h1>setext</h1>"))
    }

    @Test
    fun `commonmark handles nested emphasis escapes and code spans`() {
        val html = renderCommonMarkFragment("***strong and emphasis*** \\*literal\\* `a *literal* code`")

        assertTrue(html.contains("<em><strong>strong and emphasis</strong></em>"))
        assertTrue(html.contains("*literal*"))
        assertTrue(html.contains("<code>a *literal* code</code>"))
    }

    @Test
    fun `commonmark resolves reference links and inline destinations`() {
        val html = renderCommonMarkFragment("[docs][guide] and [site](https://example.test)\n\n[guide]: https://docs.example.test \"Docs\"")

        assertTrue(html.contains("href=\"https://docs.example.test\" title=\"Docs\""))
        assertTrue(html.contains(">docs</a>"))
        assertTrue(html.contains("href=\"https://example.test\""))
    }

    @Test
    fun `commonmark handles blockquotes lazy continuation and nested lists`() {
        val html = renderCommonMarkFragment("> quote\ncontinued\n\n- parent\n  - child\n  - second child")

        assertTrue(html.contains("<blockquote>"))
        assertTrue(html.contains("quote\ncontinued"))
        assertTrue(html.contains("<ul>"))
        assertTrue(html.contains("child"))
        assertTrue(html.contains("</ul>"))
    }

    @Test
    fun `commonmark renders thematic breaks and fenced code language`() {
        val html = renderCommonMarkFragment("before\n\n---\n\n```kotlin\nval answer = 42\n```")

        assertTrue(html.contains("<hr />"))
        assertTrue(html.contains("class=\"language-kotlin\""))
        assertTrue(html.contains("val answer = 42"))
    }

    @Test
    fun `gfm tables task lists and strikethrough remain supported`() {
        val html = renderCommonMarkFragment("| A | B |\n| --- | --- |\n| x | y |\n\n- [x] done\n- [ ] todo\n\n~~old~~")

        assertTrue(html.contains("<table>"))
        assertTrue(html.contains("<th>A</th>"))
        assertTrue(html.contains("type=\"checkbox\""))
        assertTrue(html.contains("checked"))
        assertTrue(html.contains("<del>old</del>"))
    }
}
