package dev.dsh.mobile.mesh.ui.screens.main

import dev.dsh.mobile.mesh.core.session.ToolCallNode
import dev.dsh.mobile.mesh.core.session.ToolResultNode
import dev.dsh.mobile.mesh.ui.components.ContentBlockView
import dev.dsh.mobile.mesh.ui.components.DiffHunk
import dev.dsh.mobile.mesh.ui.components.ToolCardView
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCardMappingTest {
    private fun call(name: String, arguments: String = "{}"): ToolCallNode =
        ToolCallNode(1, "call-1", name, arguments, 1, 1)

    private fun result(
        content: JsonElement?,
        error: Boolean = false,
        meta: JsonElement? = null,
    ): ToolResultNode = ToolResultNode(2, "call-1", content, error, 1, 1, meta)

    private fun text(value: String) = buildJsonObject {
        put("type", "text")
        put("text", value)
    }

    /** One `apply_patch` call carrying the patch text its schema sends as `input`. */
    private fun patchCall(input: String): ToolCallNode =
        call("apply_patch", buildJsonObject { put("input", input) }.toString())

    /** One applied `FileDiff` from the patch tool's persisted result metadata. */
    private fun fileDiff(path: String, oldText: String?, newText: String) = buildJsonObject {
        put("path", path)
        if (oldText == null) put("oldText", JsonNull) else put("oldText", oldText)
        put("newText", newText)
    }

    @Test
    fun `unknown settled tool keeps text and image output in generic card`() {
        val content = buildJsonArray {
            add(text("finished successfully"))
            add(buildJsonObject {
                put("type", "image")
                put("attachment", buildJsonObject {
                    put("attachmentId", "image-digest")
                    put("mediaType", "image/png")
                    // The wire reference carries its exact stored byte length; the 0.2.2 read
                    // route verifies it, so a tool image without one is not a valid reference.
                    put("bytes", 2048)
                    put("width", 640)
                    put("height", 480)
                    put("name", "preview.png")
                })
            })
        }
        val card = buildToolCardView(call("future_tool", "{not-json"), result(content), false)
            as ToolCardView.GenericCard
        assertEquals("{not-json", card.rawInput)
        assertEquals(ContentBlockView.TextBlock("finished successfully"), card.content?.first())
        val image = card.content?.last() as ContentBlockView.ImageBlock
        // The complete reference survives: the 0.2.2 image read route needs more than the id.
        assertEquals("image-digest", image.attachment.attachmentId)
        assertEquals("image/png", image.attachment.mediaType)
        assertEquals(640, image.attachment.width)
        assertEquals(480, image.attachment.height)
        assertEquals("preview.png", image.attachment.name)
    }

    @Test
    fun `unsupported result blocks remain visible without throwing`() {
        val content = buildJsonArray {
            add(buildJsonObject { put("type", "future"); put("payload", 42) })
            add(JsonNull)
            add(buildJsonObject { put("type", "text"); put("text", buildJsonArray { add(JsonPrimitive(1)) }) })
        }
        val card = buildToolCardView(call("future_tool"), result(content), false) as ToolCardView.GenericCard
        assertEquals(content.map { ContentBlockView.TextBlock(it.toString()) }, card.content)
        val scalar = buildToolCardView(call("future_tool"), result(JsonPrimitive("raw")), false) as ToolCardView.GenericCard
        assertEquals(listOf(ContentBlockView.TextBlock("\"raw\"")), scalar.content)
    }

    @Test
    fun `malformed specialized calls fall back to original input and settled output`() {
        val card = buildToolCardView(call("read", "{bad"), result(buildJsonArray { add(text("read succeeded")) }), false)
            as ToolCardView.GenericCard
        assertEquals("{bad", card.rawInput)
        assertEquals(listOf(ContentBlockView.TextBlock("read succeeded")), card.content)
        val pending = buildToolCardView(call("future_tool"), null, true) as ToolCardView.GenericCard
        assertNull(pending.content)
    }

    @Test
    fun `terminal keeps explicit zero and nonzero exit markers`() {
        val command = call("bash", """{"command":"false","description":"check"}""")
        val failed = buildToolCardView(command, result(buildJsonArray { add(text("failed\n[exit code: 7]")) }), false)
            as ToolCardView.TerminalCard
        assertEquals(7, failed.exitCode)
        assertEquals("failed", failed.output)
        assertEquals(listOf("failed"), failed.outputBlocks)
        val succeeded = buildToolCardView(command, result(buildJsonArray { add(text("ok\n[exit code: 0]")) }), false)
            as ToolCardView.TerminalCard
        assertEquals(0, succeeded.exitCode)
    }

    @Test
    fun `terminal multi-block or non-text result stays generic with all content`() {
        val command = call("bash", """{"command":"echo ok","description":"check"}""")
        val multiple = buildToolCardView(command, result(buildJsonArray {
            add(text("first"))
            add(text("second\n[exit code: 0]"))
        }), false) as ToolCardView.GenericCard
        assertEquals(listOf(ContentBlockView.TextBlock("first"), ContentBlockView.TextBlock("second\n[exit code: 0]")), multiple.content)
        val nonText = buildToolCardView(command, result(buildJsonArray {
            add(buildJsonObject { put("type", "future"); put("data", 1) })
        }), false) as ToolCardView.GenericCard
        assertEquals(1, nonText.content?.size)
    }

    @Test
    fun `spilled shell preview and notice-only result remain generic`() {
        val command = call("bash", """{"command":"ls","description":"check"}""")
        val footer = "(Omitted 123 bytes. Full formatted result stored at: /tmp/shell.txt. Read that file for complete output.)"
        listOf(footer, "partial output\n\n$footer").forEach { output ->
            val card = buildToolCardView(command, result(buildJsonArray { add(text(output)) }), false)
                as ToolCardView.GenericCard
            assertEquals(listOf(ContentBlockView.TextBlock(output)), card.content)
        }
    }

    @Test
    fun `terminal missing or malformed exit marker never claims success`() {
        val command = call("bash", """{"command":"false","description":"check"}""")
        listOf("output only", "failed\n[exit code: unknown]", "failed\n[exit code: 99999999999999999]").forEach { output ->
            val card = buildToolCardView(command, result(buildJsonArray { add(text(output)) }), false)
                as ToolCardView.TerminalCard
            assertNull(card.exitCode)
            assertEquals(output, card.output)
        }
        val signaled = buildToolCardView(command, result(buildJsonArray { add(text("stopped\n[killed by signal: SIGTERM]")) }), false)
            as ToolCardView.TerminalCard
        assertEquals("SIGTERM", signaled.signal)
        assertTrue(signaled.output == "stopped")
    }

    @Test
    fun `apply patch shows every hunk the host reports it applied`() {
        val input = "*** Begin Patch\n*** Update File: app/a.kt\n@@\n-old\n+new\n*** Delete File: app/b.kt\n*** End Patch"
        val meta = buildJsonObject {
            put("stopped", false)
            put("diffs", buildJsonArray {
                add(fileDiff("app/a.kt", "old", "new"))
                add(fileDiff("app/b.kt", "gone\n", ""))
            })
        }
        val content = buildJsonArray {
            add(text("Success. Updated the following files:\nM app/a.kt\nD app/b.kt"))
        }
        val card = buildToolCardView(patchCall(input), result(content, meta = meta), false) as ToolCardView.DiffCard
        assertEquals(
            listOf(DiffHunk("app/a.kt", "old", "new"), DiffHunk("app/b.kt", "gone\n", "")),
            card.diffs,
        )
        assertFalse(card.partial)
        assertNull(card.detail)
    }

    @Test
    fun `apply patch stopped after a failure shows only applied hunks and marks the card partial`() {
        // The Host applies operations in order and stops at the first failure: the first file was
        // changed, the second was never touched. Its metadata lists only the applied hunk.
        val input = "*** Begin Patch\n*** Update File: app/a.kt\n@@\n-old\n+new\n" +
            "*** Update File: app/b.kt\n@@\n-old\n+new\n*** End Patch"
        val meta = buildJsonObject {
            put("stopped", true)
            put("diffs", buildJsonArray { add(fileDiff("app/a.kt", "old", "new")) })
        }
        val hostText = "Patch stopped after 1 of 2 operations.\nCompleted:\nM app/a.kt\nFailed:\n" +
            "- operation 2 (update app/b.kt, patch line 6): permission denied"
        val content = buildJsonArray { add(text(hostText)) }
        val card = buildToolCardView(patchCall(input), result(content, meta = meta), false) as ToolCardView.DiffCard
        assertTrue(card.partial)
        // The requested second file was not applied, so it must not appear as an applied change.
        assertEquals(listOf(DiffHunk("app/a.kt", "old", "new")), card.diffs)
        // The Host's account of the stopped operations stays beside the applied hunks.
        assertEquals(hostText, card.detail)
    }

    @Test
    fun `apply patch without applied metadata falls back to the generic host result`() {
        val input = "*** Begin Patch\n*** Add File: app/new.kt\n+val x = 1\n*** End Patch"
        val hostText = "Success. Updated the following files:\nA app/new.kt"
        val content = buildJsonArray { add(text(hostText)) }
        val noMeta = buildToolCardView(patchCall(input), result(content), false) as ToolCardView.GenericCard
        assertEquals(listOf(ContentBlockView.TextBlock(hostText)), noMeta.content)
        // Creations store no bounded diff, so the Host's own metadata itself lists no hunk: the
        // result text is still the only truthful rendering.
        val creationMeta = buildJsonObject {
            put("stopped", false)
            put("diffs", JsonArray(emptyList()))
        }
        val creation = buildToolCardView(patchCall(input), result(content, meta = creationMeta), false)
            as ToolCardView.GenericCard
        assertEquals(listOf(ContentBlockView.TextBlock(hostText)), creation.content)
        // A stopped patch that applied nothing (its first operation was refused) also carries no
        // hunk to draw: the Host's stopped/failure text must stay visible.
        val stoppedOnly = buildJsonObject {
            put("stopped", true)
            put("diffs", JsonArray(emptyList()))
        }
        val stoppedText = "Patch stopped after 0 of 2 operations.\nFailed:\n" +
            "- operation 1 (update app/a.kt, patch line 4): permission denied"
        val stoppedContent = buildJsonArray { add(text(stoppedText)) }
        val stoppedInput = "*** Begin Patch\n*** Update File: app/a.kt\n@@\n-old\n+new\n*** End Patch"
        val stoppedCard = buildToolCardView(patchCall(stoppedInput), result(stoppedContent, meta = stoppedOnly), false)
            as ToolCardView.GenericCard
        assertEquals(listOf(ContentBlockView.TextBlock(stoppedText)), stoppedCard.content)
        // While the call is still running, the requested patch is not an applied change.
        val pending = buildToolCardView(patchCall(input), null, true) as ToolCardView.GenericCard
        assertEquals(patchCall(input).arguments, pending.rawInput)
    }

    @Test
    fun `failed apply patch keeps the host error instead of a diff card`() {
        // A refusal before any change has no applied metadata, and a failed result outranks any
        // metadata that did arrive: the Host's error text stays the visible outcome.
        val errorText = "Error: Invalid patch at line 1: Expected '*** Begin Patch'"
        val meta = buildJsonObject {
            put("stopped", true)
            put("diffs", buildJsonArray { add(fileDiff("app/a.kt", "old", "new")) })
        }
        val content = buildJsonArray { add(text(errorText)) }
        val card = buildToolCardView(patchCall("invalid"), result(content, error = true, meta = meta), false)
        assertTrue(card is ToolCardView.GenericCard)
        assertEquals(listOf(ContentBlockView.TextBlock(errorText)), (card as ToolCardView.GenericCard).content)
    }
}
