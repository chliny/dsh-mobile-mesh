package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.core.wire.RpcError
import kotlinx.serialization.json.contentOrNull

/** True only for structured Gateway failures identifying the requested job/list endpoint. */
internal fun isUnsupportedJobListFailure(error: RpcError, carrierFailure: Boolean): Boolean {
    if (carrierFailure) return false
    val endpoint = (error.details as? kotlinx.serialization.json.JsonObject)
        ?.get("endpoint")
        ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
    if (endpoint != JOB_LIST_ENDPOINT) return false
    return error.code == "gateway/invocation-unavailable"
}

internal const val JOB_LIST_ENDPOINT = "job/list"
