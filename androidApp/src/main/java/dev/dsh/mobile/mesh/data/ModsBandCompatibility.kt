package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.core.wire.RpcError
/** A missing optional route is not a failed session connection; other stream failures remain visible. */
internal fun isUnsupportedModsBandFailure(error: RpcError, carrierFailure: Boolean): Boolean =
    isUnsupportedOptionalStream(error, carrierFailure, "claudeCodeMods/watchBand")
