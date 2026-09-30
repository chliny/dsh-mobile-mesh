package dev.dsh.mobile.mesh.core.wire.dto

import dev.dsh.mobile.mesh.core.wire.decodeFromJsonElement
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteContractCompatibilityTest {
    @Test fun `current preset roster without legacy metadata decodes`() {
        val current = decodeFromJsonElement(
            AgentPresetListValue.serializer(),
            Json.parseToJsonElement("""{"presets":[{"id":"standard","isDefault":true,"name":"Standard"}]}"""),
        )
        assertEquals(false, current.authorable)
        assertEquals(false, current.hasDocument)
        assertEquals(null, current.presets.single().trust)
        assertEquals("Standard", current.presets.single().name)
    }

    @Test fun `current preset read without legacy trust decodes`() {
        val current = decodeFromJsonElement(
            AgentPresetDocument.serializer(),
            Json.parseToJsonElement("""{"agentPreset":"standard","content":"plugins: []"}"""),
        )
        assertEquals(null, current.trust)
        assertTrue(current.content.contains("plugins: []"))
    }

    @Test fun `workspace bytes decode current raw JSON byte arrays and legacy base64`() {
        val raw = decodeFromJsonElement(
            WorkspaceFileBytes.serializer(),
            Json.parseToJsonElement("""{"absolutePath":"a.bin","version":"v1","offset":0,"data":[0,127,255],"eof":true}"""),
        )
        assertArrayEquals(byteArrayOf(0, 127, -1), raw.bytesData())

        val old = decodeFromJsonElement(
            WorkspaceFileBytes.serializer(),
            Json.parseToJsonElement("""{"absolutePath":"a.bin","version":"v1","offset":0,"data":"AH//","eof":true}"""),
        )
        assertArrayEquals(byteArrayOf(0, 127, -1), old.bytesData())
    }

    @Test fun `unknown session subagent mode is rejected rather than opened`() {
        val address = Json.parseToJsonElement("""{"kind":"subagent","parentSessionId":"p","childSessionId":"c","mode":"unknown"}""")
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            decodeFromJsonElement(SessionAddressSerializer, address)
        }
    }

    @Test fun `old projection hints without sequence discriminator use cached space`() {
        val block = decodeFromJsonElement(
            SessionProjectionsBlock.serializer(),
            Json.parseToJsonElement("""{"asOfSeq":12,"values":{}}"""),
        )
        assertEquals("cached", block.kind)
        assertEquals(false, block.watermarkComparableToLive)
    }
}
