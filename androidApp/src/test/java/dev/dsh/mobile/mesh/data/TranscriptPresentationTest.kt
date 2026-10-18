package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.core.wire.dto.SettingsDescribeValue
import dev.dsh.mobile.mesh.core.wire.dto.SettingsNamespaceView
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptPresentationTest {
    private fun describe(value: String?): SettingsDescribeValue = SettingsDescribeValue(
        writable = true, hasDocument = true,
        namespaces = value?.let {
            listOf(SettingsNamespaceView("ui-chat", Json.parseToJsonElement("{}"),
                Json.parseToJsonElement(it), applies = "live", revision = 1))
        }.orEmpty(),
    )

    @Test fun `host modes map to distinct Web presentation capabilities`() {
        val modes = listOf("compact", "standard", "detailed", "verbose")
        val actual = modes.map { transcriptPresentationOf(describe("""{"transcriptView":"$it"}""")) }
        assertEquals(TranscriptPresentation.Mode.entries, actual.map { it.mode })
        assertEquals(listOf(true, true, false, false), actual.map { it.groupLiveSteps })
        assertEquals(listOf(true, true, true, false), actual.map { it.foldCompletedTurns })
        assertEquals(listOf(false, true, true, true), actual.map { it.settledReasoningPreview })
        assertEquals(listOf(false, true, true, false), actual.map { it.liveProcessDetail })
    }

    @Test fun `older settings read cannot overwrite a newer server invalidation`() {
        val fence = TranscriptPresentationReadFence()
        val first = fence.begin()
        val second = fence.begin()
        assertFalse(fence.isCurrent(first))
        assertTrue(fence.isCurrent(second))
    }

    @Test fun `missing and legacy settings follow non desktop Web defaults`() {
        assertEquals(TranscriptPresentation(), transcriptPresentationOf(describe(null)))
        listOf("normal", "expanded", "unknown").forEach { legacy ->
            assertEquals(TranscriptPresentation.Mode.Detailed,
                transcriptPresentationOf(describe("""{"transcriptView":"$legacy"}""")).mode)
        }
        assertEquals(TranscriptPresentation.CollapseTiming.NextInput,
            transcriptPresentationOf(describe("""{"collapseTiming":"next-input"}""")).collapseTiming)
    }
}
