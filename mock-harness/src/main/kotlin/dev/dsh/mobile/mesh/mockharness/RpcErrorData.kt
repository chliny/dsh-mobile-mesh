package dev.dsh.mobile.mesh.mockharness

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * The `ok: false` error carried by a `server-response` envelope:
 * `{"code": ..., "message": ..., "details": {...}}`.
 */
data class RpcErrorData(
    val code: String,
    val message: String,
    val details: JsonObject = buildJsonObject { },
)
