package dev.dsh.mobile.mesh.core.wire.dto

import dev.dsh.mobile.mesh.core.wire.decodeFromString
import dev.dsh.mobile.mesh.core.wire.encodeToJsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Protects projection sequence-space handling and Agent availability decoding. */
class SessionSummaryCompatibilityTest {
    @Test
    fun `cached projection watermark is not comparable to live sequence watermarks`() {
        val hint = decodeFromString<SessionProjectionsBlock>(
            """{"kind":"cached","asOfSeq":50,"values":{}}""",
        )
        assertFalse(hint.watermarkComparableToLive)
    }

    @Test
    fun `sequenced projection watermark can be compared to live sequence watermarks`() {
        val hint = decodeFromString<SessionProjectionsBlock>(
            """{"kind":"sequenced","asOfSeq":50,"values":{}}""",
        )
        assertTrue(hint.watermarkComparableToLive)
    }

    @Test
    fun `agent availability is independent of running status`() {
        val summary = decodeFromString<SessionSummary>(
            """{"agentAvailable":true,"sessionId":"s1","updatedAt":5,"running":false,"blank":true}""",
        )
        assertTrue(summary.agentAvailable)
        assertFalse(summary.running)
    }

    @Test
    fun `legacy projection response without kind remains sequenced`() {
        val value = decodeFromString<SessionProjectionsValue>(
            """{"asOfSeq":17,"values":{"userQuestions":{}}}""",
        )
        assertEquals("sequenced", value.kind)
        assertEquals(17, value.asOfSeq)
        assertTrue(value.watermarkComparableToLive)
    }

    @Test
    fun `migration-required projection hint decodes without sequence watermark`() {
        val value = decodeFromString<SessionProjectionsValue>(
            """{"kind":"migration-required","values":{"sessionListMetadata":{"blank":false}}}""",
        )
        assertEquals("migration-required", value.kind)
        assertNull(value.asOfSeq)
        assertFalse(value.watermarkComparableToLive)
    }

    @Test
    fun `new optional session format status remains absent on legacy summaries`() {
        val legacy = decodeFromString<SessionSummary>(
            """{"sessionId":"s1","updatedAt":1,"running":false,"blank":false}""",
        )
        val current = decodeFromString<SessionSummary>(
            """{"sessionId":"s2","updatedAt":1,"running":false,"blank":false,"formatStatus":"migration-required"}""",
        )
        assertNull(legacy.formatStatus)
        assertEquals("migration-required", current.formatStatus)
    }

    @Test
    fun `fork request keeps new migration option omitted by default`() {
        val encoded = encodeToJsonElement(
            SessionForkRequest.serializer(),
            SessionForkRequest(sessionId = "s1"),
        ).jsonObject
        assertFalse(encoded.containsKey("allowMigration"))
    }

    @Test
    fun `projection value serializes the current response discriminator`() {
        val value = SessionProjectionsValue(asOfSeq = 8, values = mapOf("x" to JsonPrimitive(true)))
        val encoded = encodeToJsonElement(SessionProjectionsValue.serializer(), value).jsonObject
        assertTrue(encoded.containsKey("asOfSeq"))
        assertTrue(encoded.containsKey("values"))
        assertEquals(JsonPrimitive("sequenced"), encoded["kind"])
    }
}
