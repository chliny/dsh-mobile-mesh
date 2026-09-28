package dev.dsh.mobile.mesh.core.wire

import dev.dsh.mobile.mesh.core.wire.dto.RemoteStreamClientMessage
import dev.dsh.mobile.mesh.core.wire.dto.RemoteStreamClientMessageSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamProtocolTest {
    @Test
    fun `client item encodes value and preserves explicit json null`() {
        val value = WireJson.encodeToJsonElement(
            RemoteStreamClientMessageSerializer,
            RemoteStreamClientMessage.Item(streamId = "s1", value = JsonPrimitive("hello")),
        ).jsonObject
        assertEquals("item", value["type"]?.toString()?.trim('"'))
        assertEquals("hello", value["value"]?.toString()?.trim('"'))

        val explicitNull = WireJson.encodeToJsonElement(
            RemoteStreamClientMessageSerializer,
            RemoteStreamClientMessage.Item(streamId = "s1", value = JsonNull),
        ).jsonObject
        assertTrue(explicitNull.containsKey("value"))
        assertEquals(JsonNull, explicitNull["value"])
    }

    @Test
    fun `void client item omits value and decodes`() {
        val encoded = WireJson.encodeToJsonElement(
            RemoteStreamClientMessageSerializer,
            RemoteStreamClientMessage.Item(streamId = "s1"),
        ).jsonObject
        assertFalse(encoded.containsKey("value"))
        assertEquals(
            RemoteStreamClientMessage.Item(streamId = "s1"),
            WireJson.decodeFromString(RemoteStreamClientMessageSerializer, encoded.toString()),
        )
    }

    @Test
    fun `client uplink end encodes and decodes`() {
        val message = RemoteStreamClientMessage.End(streamId = "s1")
        val encoded = WireJson.encodeToJsonElement(RemoteStreamClientMessageSerializer, message)
        assertEquals("""{"type":"end","streamId":"s1"}""", encoded.toString())
        assertEquals(message, WireJson.decodeFromString(RemoteStreamClientMessageSerializer, encoded.toString()))
    }
}
