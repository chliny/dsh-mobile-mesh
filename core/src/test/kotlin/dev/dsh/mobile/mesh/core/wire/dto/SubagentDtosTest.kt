package dev.dsh.mobile.mesh.core.wire.dto

import dev.dsh.mobile.mesh.core.wire.decodeFromJsonElement
import dev.dsh.mobile.mesh.core.wire.encodeToJsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class SubagentDtosTest {
    @Test
    fun `modern parent projection decodes one-shot continuable and unknown children`() {
        val entries = Json.decodeFromString<List<SubagentCatalogProjectionEntry>>(
            """[{"id":"one","createdAt":1720000000000,"mode":"one-shot"},""" +
                """{"id":"two","createdAt":1720000000001,"mode":"continuable","label":"Worker"},""" +
                """{"id":"three","createdAt":1720000000002,"mode":"unknown"}]""",
        )
        assertEquals(listOf("one-shot", "continuable", "unknown"), entries.map { it.mode })
        assertEquals(1720000000000L, entries.first().createdAt)
        assertEquals(null, entries.first().label)
        assertEquals("Worker", entries[1].label)
        assertEquals(entries, Json.decodeFromString<List<SubagentCatalogProjectionEntry>>(Json.encodeToString(entries)))
        val projected = SubagentListEntry.Projected(entries[1])
        assertEquals(projected, decodeFromJsonElement(
            SubagentListEntrySerializer,
            encodeToJsonElement(SubagentListEntrySerializer, projected),
        ))
    }

    @Test
    fun `known list entries keep their kind through a JSON round trip`() {
        val entries = listOf(
            SubagentListEntry.ChildOneShot(
                id = "one-shot",
                activity = "idle",
                hasChildren = false,
                label = "Research",
            ),
            SubagentListEntry.ChildContinuable(
                id = "continuable",
                activity = "running",
                hasChildren = true,
                label = "Builder",
            ),
            SubagentListEntry.Diagnostic(
                id = "diagnostic",
                reason = "Transcript unavailable",
            ),
        )

        entries.forEach { entry ->
            val encoded = encodeToJsonElement(SubagentListEntrySerializer, entry)
            assertEquals(entry.kind, encoded.jsonObject.getValue("kind").jsonPrimitive.content)
            assertEquals(entry, decodeFromJsonElement(SubagentListEntrySerializer, encoded))
        }
    }

    @Test
    fun `unknown list entries remain lossless`() {
        val raw = buildJsonObject {
            put("kind", "future-kind")
            put("id", "future-entry")
            put("extra", true)
        }
        val decoded = decodeFromJsonElement(SubagentListEntrySerializer, raw)

        assertEquals(UnknownSubagentListEntry("future-kind", raw), decoded)
        assertEquals(raw, encodeToJsonElement(SubagentListEntrySerializer, decoded))
    }

    @Test
    fun `catalog round trip preserves every known entry subtype`() {
        val catalog = SubagentCatalog(
            entries = listOf(
                SubagentListEntry.ChildOneShot(
                    id = "one-shot",
                    activity = "idle",
                    hasChildren = false,
                    label = "Research",
                ),
                SubagentListEntry.ChildContinuable(
                    id = "continuable",
                    activity = "running",
                    hasChildren = true,
                    label = "Builder",
                ),
                SubagentListEntry.Diagnostic(
                    id = "diagnostic",
                    reason = "Transcript unavailable",
                ),
            ),
            parentAvailable = true,
        )

        val encoded = encodeToJsonElement(SubagentCatalog.serializer(), catalog)

        assertEquals(catalog, decodeFromJsonElement(SubagentCatalog.serializer(), encoded))
    }
}
