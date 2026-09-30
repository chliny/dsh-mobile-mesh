@file:OptIn(
    kotlinx.serialization.InternalSerializationApi::class,
    kotlinx.serialization.ExperimentalSerializationApi::class,
)

package dev.dsh.mobile.mesh.core.wire.dto

import java.util.Base64
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.descriptors.buildSerialDescriptor

/** Bounded workspace file metadata from the harness workspaceFiles Remote. */
@Serializable
data class WorkspaceFileStat(
    @SerialName("absolutePath") val absolutePath: String,
    @SerialName("version") val version: String,
    @SerialName("bytes") val bytes: Long? = null,
)

@Serializable
data class WorkspaceFileRange(
    @SerialName("offset") val offset: Int? = null,
    @SerialName("limit") val limit: Int? = null,
)

@Serializable
data class WorkspaceFileText(
    @SerialName("absolutePath") val absolutePath: String,
    @SerialName("version") val version: String,
    @SerialName("bytes") val bytes: Long? = null,
    @SerialName("offset") val offset: Int,
    @SerialName("text") val text: String,
    @SerialName("lines") val lines: Int,
    @SerialName("eof") val eof: Boolean,
)

@Serializable
data class WorkspaceFileBytes(
    @SerialName("absolutePath") val absolutePath: String,
    @SerialName("version") val version: String,
    @SerialName("bytes") val bytes: Long? = null,
    @SerialName("offset") val offset: Int,
    @Serializable(with = WorkspaceFileDataSerializer::class)
    @SerialName("data") val data: ByteArray,
    @SerialName("eof") val eof: Boolean,
) {
    fun bytesData(): ByteArray = data.copyOf()
}

/** Accept the current JSON byte-array encoding and the older base64 string encoding. */
object WorkspaceFileDataSerializer : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor = kotlinx.serialization.descriptors.buildSerialDescriptor(
        "WorkspaceFileData", PrimitiveKind.STRING,
    )

    override fun deserialize(decoder: Decoder): ByteArray {
        val element = (decoder as JsonDecoder).decodeJsonElement()
        return when (element) {
            is JsonArray -> element.map { item ->
                val byte = (item as? JsonPrimitive)?.intOrNull
                    ?: throw IllegalArgumentException("workspace file byte must be an integer")
                require(byte in 0..255) { "workspace file byte is outside 0..255" }
                byte.toByte()
            }.toByteArray()
            is JsonPrimitive -> {
                require(element.isString) { "workspace file data must be bytes or base64" }
                val text = element.contentOrNull.orEmpty()
                runCatching { Base64.getMimeDecoder().decode(text) }
                    .getOrElse { Base64.getUrlDecoder().decode(text) }
            }
            else -> throw IllegalArgumentException("workspace file data must be bytes or base64")
        }
    }

    override fun serialize(encoder: Encoder, value: ByteArray) {
        (encoder as JsonEncoder).encodeJsonElement(buildJsonArray {
            value.forEach { add(JsonPrimitive(it.toInt() and 0xff)) }
        })
    }
}

@Serializable
data class WorkspaceDirectoryEntry(
    @SerialName("name") val name: String,
    @SerialName("type") val type: String,
    @SerialName("size") val size: Long? = null,
)

@Serializable
data class WorkspaceDirectoryListing(
    @SerialName("path") val path: String,
    @SerialName("entries") val entries: List<WorkspaceDirectoryEntry> = emptyList(),
    @SerialName("truncated") val truncated: Boolean = false,
)
