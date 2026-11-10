package dev.dsh.mobile.mesh.core.wire.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Passive Host snapshot; a null queue means it cannot be read, not that it is empty. */
@Serializable
data class SessionPassiveState(
    @SerialName("cursor") val cursor: Long,
    @SerialName("active") val active: Boolean,
    @SerialName("running") val running: Boolean,
    @SerialName("queue") val queue: List<JsonElement>?,
    @SerialName("pendingInput") val pendingInput: List<String>,
)

/** Bounded assistant output returned at the wait's stopping cursor. */
@Serializable
data class SessionWaitOutput(
    @SerialName("records") val records: List<JsonElement>,
    @SerialName("truncated") val truncated: Boolean,
)

/** Host wait result; a timeout does not stop the Session. */
@Serializable
data class SessionWaitValue(
    @SerialName("reason") val reason: String,
    @SerialName("state") val state: SessionPassiveState,
    @SerialName("output") val output: SessionWaitOutput,
)
