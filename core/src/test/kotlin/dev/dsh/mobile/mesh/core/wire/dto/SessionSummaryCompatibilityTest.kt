package dev.dsh.mobile.mesh.core.wire.dto

import dev.dsh.mobile.mesh.core.wire.decodeFromString
import dev.dsh.mobile.mesh.core.wire.encodeToJsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertFalse
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
    fun `projection baseline has no hint sequence-space discriminator`() {
        val value = SessionProjectionsValue(asOfSeq = 8, values = mapOf("x" to JsonPrimitive(true)))
        val encoded = encodeToJsonElement(SessionProjectionsValue.serializer(), value).jsonObject
        assertTrue(encoded.containsKey("asOfSeq"))
        assertTrue(encoded.containsKey("values"))
        assertFalse(encoded.containsKey("kind"))
    }
}
