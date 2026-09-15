package dev.dsh.mobile.mesh.connection

/** Full native teardown is needed only when leaving the currently active transport family. */
internal fun shouldStopMeshBeforeConnect(
    activeTransport: MeshTransport?,
    nextTransport: MeshTransport?,
    preservePendingIdentity: Boolean,
): Boolean = !preservePendingIdentity && activeTransport != null && activeTransport != nextTransport
