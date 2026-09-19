package dev.dsh.mobile.mesh.ui

internal enum class ConnectionListOrigin {
    SESSION,
    SETTINGS,
    CONNECT_FORM,
    STARTUP,
}

internal fun connectionListBackTarget(origin: ConnectionListOrigin): ConnectionListOrigin = origin
