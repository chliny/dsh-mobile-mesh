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
    fun `destroyed markdown webview rejects queued visual state callback`() {
        assertTrue(canPostMarkdownVisualStateCallback(released = false))
        assertFalse(canPostMarkdownVisualStateCallback(released = true))
    }

    @Test
    fun `webview intrinsic height rounds up so last text baseline is not clipped`() {
        assertEquals(100, markdownWebViewContentHeight(contentHeightCssPx = 40, scale = 2.5f))
        assertEquals(103, markdownWebViewContentHeight(contentHeightCssPx = 41, scale = 2.49f))
        assertEquals(1, markdownWebViewContentHeight(contentHeightCssPx = 0, scale = 1f))
    }

    @Test
    fun `markdown loading preview is immediate and bounded`() {
        val short = "**render after parsing**"
        val long = "x".repeat(MARKDOWN_LOADING_PREVIEW_LIMIT + 12)

        assertEquals(short, markdownLoadingPreviewText(short))
        assertEquals(MARKDOWN_LOADING_PREVIEW_LIMIT + 1, markdownLoadingPreviewText(long).length)
        assertTrue(markdownLoadingPreviewText(long).endsWith("…"))
    }

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
        val cjkBold = renderCommonMarkFragment("**Review 结论: **发现问题")
        assertTrue(cjkBold.contains("<strong>Review 结论:</strong>发现问题"))
        assertTrue(containsCommonMarkElements("- list item"))
        assertTrue(containsCommonMarkElements("AT&amp;T"))
        assertTrue(containsCommonMarkElements("\\*escaped punctuation\\*"))
    }

    @Test
    fun `mixed user prose and markdown retain line breaks without a white webview surface`() {
        val message = "First plain line\nSecond plain line with **bold**\nThird plain line"
        val parts = renderMarkdownPartsForTest(message, DsThemeTokens.light, "Copy", false) { error("No assets expected") }
        val html = (parts.single() as MarkdownPart.Html).html

        assertTrue(html.contains("First plain line<br />Second plain line with <strong>bold</strong><br />Third plain line"))
        assertTrue(html.contains("p { margin: 0 0 1em; white-space: normal; }"))
        assertTrue(html.contains("html, body { margin: 0; padding: 0; background: transparent;"))
        assertFalse(html.contains("background: #FFFFFF;"))
    }

    @Test
    fun `hard breaks do not render twice when followed by renderer whitespace`() {
        val html = (renderMarkdownPartsForTest(
            "first **bold**  \nsecond", DsThemeTokens.light, "Copy", false,
        ) { error("No assets expected") }.single() as MarkdownPart.Html).html

        assertTrue(html.contains("<strong>bold</strong><br />\nsecond"))
        assertTrue(html.contains("p { margin: 0 0 1em; white-space: normal; }"))
        assertFalse(html.contains("white-space: pre-wrap"))
    }

    @Test
    fun `separate formatted paragraphs share one webview so their gap remains visible`() {
        val parts = renderMarkdownPartsForTest(
            "first **bold**\n\nsecond paragraph", DsThemeTokens.light, "Copy", false,
        ) { error("No assets expected") }

        val html = (parts.single() as MarkdownPart.Html).html
        assertTrue(html.contains("<p>first <strong>bold</strong></p>\n<p>second paragraph</p>"))
        assertTrue(html.contains("p { margin: 0 0 1em; white-space: normal; }"))
    }

    @Test
    fun `strong delimiter compatibility does not consume a line break`() {
        val html = renderCommonMarkFragment("**first\n**second")

        assertTrue(html.contains("first<br />"))
        assertTrue(html.contains("second"))
    }

    @Test
    fun `soft line breaks render inside tight lists and nested blockquotes`() {
        val html = renderCommonMarkFragment("- first line\n  second **bold** line\n- next item\n\n> quoted line\n> continuation")

        assertTrue(html.contains("<li>first line<br />second <strong>bold</strong> line</li>"))
        assertTrue(html.contains("quoted line<br />continuation"))
        assertFalse(html.contains("first line\nsecond"))
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
        assertTrue(html.contains("quote<br />continued"))
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
    fun `top-level fenced Markdown is split into native code-card segments`() {
        val parts = renderMarkdownPartsForTest(
            "before\n\n```text\nhello world\n```\n\nafter",
            colors = DsThemeTokens.light,
            copyLabel = "Copy",
            darkMode = false,
            openAsset = { File("src/main/assets").resolve(it).inputStream() },
        )

        assertEquals(3, parts.size)
        assertTrue(parts[0] is MarkdownPart.Html)
        val code = parts[1] as MarkdownPart.Code
        assertEquals("text", code.block.language)
        assertEquals("hello world\n", code.block.code)
        assertTrue(parts[2] is MarkdownPart.Html)
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
