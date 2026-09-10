package dev.dsh.mobile.protocol

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Platform transport contract for DSH Remote HTTP, mux WebSocket, and streamed file operations. */
interface DshClient {
    suspend fun call(endpoint: String, args: JsonObject = JsonObject(emptyMap())): JsonElement
    fun events(): Flow<JsonElement>
    suspend fun upload(sessionId: String, name: String?, bytes: ByteArray): JsonElement
}

const val REMOTE_MUX_PATH = "/api/remote.mux"
