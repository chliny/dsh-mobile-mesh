package dev.dsh.mobile.mesh.data

/** Reconcile a session's running state from the newest authoritative history snapshot. */
internal fun runningStateFromSnapshot(existing: Boolean?, snapshot: Boolean): Boolean = snapshot
