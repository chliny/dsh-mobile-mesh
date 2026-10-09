package dev.dsh.mobile.mesh.ui.components

import java.io.File
import org.junit.Assert.assertEquals
import dev.dsh.mobile.mesh.ui.theme.DsThemeTokens
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRenderingTest {
    @Test
    fun `render cache distinguishes resolved image destinations for identical markdown`() {
        val markdown = "![logo](assets/logo.svg)"
        val fromWorkspaceA = MarkdownRenderCacheKey(
            markdown, DsThemeTokens.light, false, "Copy", listOf("data:image/svg+xml;base64,AAA"),
        )
        val fromWorkspaceB = MarkdownRenderCacheKey(
            markdown, DsThemeTokens.light, false, "Copy", listOf("data:image/svg+xml;base64,BBB"),
        )

        assertNotEquals(fromWorkspaceA, fromWorkspaceB)
    }

    @Test
    fun `plain user text bypasses markdown rendering but formatting uses it`() {
        val plain = "A normal message with a URL https://example.test"
        assertFalse(mightContainCommonMarkElements(plain))
        assertFalse(containsCommonMarkElements(plain))
        assertEquals(false, cachedCommonMarkDecision(plain))
        assertFalse(containsCommonMarkElements("Math remains plain: 2 * 3"))
        assertFalse(containsCommonMarkElements("Two plain lines\nwith a line break"))
        assertTrue(containsCommonMarkElements("A `code` span"))
        assertTrue(containsCommonMarkElements("**bold** and _emphasis_"))
        assertTrue(containsCommonMarkElements("- list item"))
        assertTrue(containsCommonMarkElements("AT&amp;T"))
        assertTrue(containsCommonMarkElements("\\*escaped punctuation\\*"))
    }

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
    fun `fenced code html keeps syntax token colors and exposes copy action`() {
        val html = renderMarkdownCodeBlockHtml(
            index = 2,
            language = "kotlin",
            codeClass = "language-kotlin",
            highlightedCodeHtml = "<span style=\"color:#FF0000\">val answer</span>",
            copyLabel = "Copy",
        )

        assertTrue(html.contains("language-kotlin"))
        assertTrue(html.contains("style=\"color:#FF0000\""))
        assertTrue(html.contains("href=\"dsh-markdown-copy://copy/2\""))
        assertTrue(html.contains(">Copy</a>"))
    }

    @Test
    fun `CommonMark fenced code is highlighted and wired to copy action`() {
        val code = "val answer = 42 // comment\n"
        val fragment = renderCommonMarkFragment("```kotlin\n$code```")
        val assets = File("src/main/assets")
        val html = decorateMarkdownCodeBlocks(
            fragment = fragment,
            codeBlocks = listOf(MarkdownCodeBlock(code, "kotlin")),
            copyLabel = "Copy",
            darkMode = false,
            openAsset = { assets.resolve(it).inputStream() },
        )

        assertTrue(html.contains("class=\"language-kotlin\""))
        assertTrue(html.contains("style=\"color:#"))
        assertTrue(html.contains("dsh-markdown-copy://copy/0"))
        assertTrue(html.contains(">val</span>"))
        assertTrue(html.contains("answer"))
        assertTrue(html.contains("comment"))
    }

    @Test
    fun `fenced code with html-sensitive characters still gets copy controls`() {
        val code = "val html = \"<tag>&\"\n"
        val fragment = renderCommonMarkFragment("```kotlin\n$code```")
        assertTrue(fragment.contains("&lt;tag&gt;&amp;"))
        val assets = File("src/main/assets")
        val html = decorateMarkdownCodeBlocks(
            fragment = fragment,
            codeBlocks = listOf(MarkdownCodeBlock(code, "kotlin")),
            copyLabel = "Copy",
            darkMode = false,
            openAsset = { assets.resolve(it).inputStream() },
        )

        assertTrue(html.contains("dsh-markdown-copy://copy/0"))
        assertTrue(html.contains("language-kotlin"))
        assertTrue(html.contains("tag"))
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
