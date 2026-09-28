package dev.dsh.mobile.mesh.core.wire.dto

import dev.dsh.mobile.mesh.core.wire.decodeFromJsonElement
import dev.dsh.mobile.mesh.core.wire.encodeToJsonElement
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Background job lifecycle status. */
@Serializable
enum class JobStatus {
    @SerialName("running") RUNNING,
    @SerialName("stopping") STOPPING,
    @SerialName("completed") COMPLETED,
    @SerialName("killed") KILLED,
    @SerialName("failed") FAILED,
}

/** One retained output chunk; channel and loss markers are open string/optional fields. */
@Serializable
data class JobChunk(
    @SerialName("at") val at: Long,
    @SerialName("text") val text: String,
    @SerialName("channel") val channel: String? = null,
    @SerialName("gapBefore") val gapBefore: Boolean? = null,
)

/** Output ring coordinates included with a job projection. */
@Serializable
data class JobOutputView(
    @SerialName("total") val total: Long = 0,
    @SerialName("earliest") val earliest: Long = 0,
    @SerialName("spillPaths") val spillPaths: List<String>? = null,
)

/** Read-only job projection; producer-specific additions remain optional and unknown fields are ignored. */
@Serializable
data class JobView(
    @SerialName("id") val id: String,
    @SerialName("kind") val kind: String,
    @SerialName("label") val label: String,
    @SerialName("status") val status: JobStatus,
    @SerialName("startedAt") val startedAt: Long,
    @SerialName("owner") val owner: String? = null,
    @SerialName("outputLimitBytes") val outputLimitBytes: Long? = null,
    @SerialName("progress") val progress: String? = null,
    @SerialName("detail") val detail: String? = null,
    @SerialName("finishedAt") val finishedAt: Long? = null,
    @SerialName("output") val output: JobOutputView = JobOutputView(),
)

@Serializable
data class JobListRequest(@SerialName("sessionId") val sessionId: String)

@Serializable
data class JobListFrame(
    @SerialName("type") val type: String = "rows",
    @SerialName("jobs") val jobs: List<JobView> = emptyList(),
)

@Serializable
data class JobFollowRequest(
    @SerialName("sessionId") val sessionId: String? = null,
    @SerialName("jobId") val jobId: String,
    @SerialName("from") val from: Long? = null,
)

@Serializable
data class JobKillRequest(
    @SerialName("sessionId") val sessionId: String,
    @SerialName("jobId") val jobId: String,
)

@Serializable
data class JobKillValue(@SerialName("outcome") val outcome: String)

/** Tagged job observation stream with raw-preserving future frame support. */
@Serializable(with = JobFollowFrameSerializer::class)
sealed class JobFollowFrame {
    @Serializable
    data class Opened(
        @SerialName("type") val type: String = "opened",
        @SerialName("job") val job: JobView,
        @SerialName("from") val from: Long,
    ) : JobFollowFrame()

    @Serializable
    data class Output(
        @SerialName("type") val type: String = "output",
        @SerialName("chunks") val chunks: List<JobChunk> = emptyList(),
        @SerialName("next") val next: Long,
        @SerialName("lossy") val lossy: Boolean? = null,
    ) : JobFollowFrame()

    @Serializable
    data class Status(
        @SerialName("type") val type: String = "status",
        @SerialName("job") val job: JobView,
    ) : JobFollowFrame()

    data class Unknown(val type: String, val raw: JsonElement) : JobFollowFrame()
}

/** Type-dispatching serializer for `job/follow` frames. */
object JobFollowFrameSerializer : KSerializer<JobFollowFrame> {
    override val descriptor = buildClassSerialDescriptor("JobFollowFrame") {
        element("type", PrimitiveSerialDescriptor("type", PrimitiveKind.STRING))
    }

    override fun serialize(encoder: Encoder, value: JobFollowFrame) {
        val json = when (value) {
            is JobFollowFrame.Opened -> encodeToJsonElement(JobFollowFrame.Opened.serializer(), value)
            is JobFollowFrame.Output -> encodeToJsonElement(JobFollowFrame.Output.serializer(), value)
            is JobFollowFrame.Status -> encodeToJsonElement(JobFollowFrame.Status.serializer(), value)
            is JobFollowFrame.Unknown -> value.raw
        }
        (encoder as JsonEncoder).encodeJsonElement(json)
    }

    override fun deserialize(decoder: Decoder): JobFollowFrame {
        val json = (decoder as JsonDecoder).decodeJsonElement().jsonObject
        return when (json["type"]?.jsonPrimitive?.contentOrNull) {
            "opened" -> decodeFromJsonElement(JobFollowFrame.Opened.serializer(), json)
            "output" -> decodeFromJsonElement(JobFollowFrame.Output.serializer(), json)
            "status" -> decodeFromJsonElement(JobFollowFrame.Status.serializer(), json)
            else -> JobFollowFrame.Unknown(json["type"]?.jsonPrimitive?.contentOrNull.orEmpty(), json)
        }
    }
}

/** Serializer for the single `job/list` whole-roster frame. */
object JobListFrameSerializer : KSerializer<JobListFrame> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("JobListFrame") {
        element("type", PrimitiveSerialDescriptor("type", PrimitiveKind.STRING))
    }
    override fun serialize(encoder: Encoder, value: JobListFrame) = JobListFrame.serializer().serialize(encoder, value)
    override fun deserialize(decoder: Decoder): JobListFrame = JobListFrame.serializer().deserialize(decoder)
}
