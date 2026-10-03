package dev.dsh.mobile.mesh.ui.screens.main

import dev.dsh.mobile.mesh.core.session.ToolCallNode
import dev.dsh.mobile.mesh.core.session.ToolResultNode
import dev.dsh.mobile.mesh.ui.components.ContentBlockView
import dev.dsh.mobile.mesh.ui.components.ToolCardView
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCardMappingTest {
    private fun call(name: String, arguments: String = "{}"): ToolCallNode =
        ToolCallNode(1, "call-1", name, arguments, 1, 1)

    private fun result(content: JsonElement?, error: Boolean = false): ToolResultNode =
        ToolResultNode(2, "call-1", content, error, 1, 1)

    private fun text(value: String) = buildJsonObject {
        put("type", "text")
        put("text", value)
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
        assertEquals(ContentBlockView.ImageBlock("image-digest", "image/png", 640, 480, "preview.png"), card.content?.last())
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
}
