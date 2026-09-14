package dev.dsh.mobile.mesh.ui.screens.connect

/** Identity fence for asynchronous host selections; only the latest request may publish results. */
internal class ConnectRequestFence {
    private var current = 0L

    @Synchronized
    fun next(): Long = ++current

    @Synchronized
    fun accepts(requestId: Long): Boolean = requestId == current
}
