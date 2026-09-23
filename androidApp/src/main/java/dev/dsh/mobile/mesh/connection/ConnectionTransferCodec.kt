package dev.dsh.mobile.mesh.connection

import dev.dsh.mobile.mesh.core.wire.WireJson

internal object ConnectionTransferCodec {
    fun encode(transfer: ConnectionTransfer): String =
        WireJson.encodeToString(ConnectionTransfer.serializer(), transfer)

    fun decode(raw: String): ConnectionTransfer =
        WireJson.decodeFromString(ConnectionTransfer.serializer(), raw).also {
            require(it.format == ConnectionTransfer.CURRENT_FORMAT) { "Unsupported connection export format" }
        }
}
