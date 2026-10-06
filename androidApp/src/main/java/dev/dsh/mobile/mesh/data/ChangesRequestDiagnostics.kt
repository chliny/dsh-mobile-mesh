package dev.dsh.mobile.mesh.data

import android.util.Log
import dev.dsh.mobile.mesh.core.wire.RpcError
import dev.dsh.mobile.mesh.core.wire.RpcResult
import dev.dsh.mobile.mesh.core.wire.TransportFailure
import dev.dsh.mobile.mesh.core.wire.TransportFailures

/** Log only fixed route names and numeric coordinates; never log session IDs, URLs or response bodies. */
internal fun changesRequestDiagnostic(route: String, seq: Long, index: Int?, error: RpcError): String =
    "GET /api/$route seq=$seq${index?.let { " index=$it" } ?: ""} " +
        "code=${error.code} status=${TransportFailures.statusOf(error) ?: "none"} " +
        "transport=${TransportFailures.of(error)}"

internal fun logChangesRequestFailure(route: String, seq: Long, index: Int?, error: RpcError) {
    Log.w("ChangesRequest", changesRequestDiagnostic(route, seq, index, error))
}

/** 404 is ambiguous until the same route rejects a parameterless request with 400. */
internal fun classifyChangesNotFound(error: RpcError, routeProbe: RpcResult<Boolean>?): RpcError {
    if (TransportFailures.of(error) != TransportFailure.NOT_FOUND ||
        TransportFailures.statusOf(error) != 404) return error
    val available = (routeProbe as? RpcResult.Ok)?.value ?: return error
    return error.copy(
        code = if (available) "changes/resource-unavailable" else "changes/route-unavailable",
        message = if (available) "Change data is no longer available on the host" else "Host does not provide this changed-files route",
    )
}
