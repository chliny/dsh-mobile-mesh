package dev.dsh.mobile.mesh.data

/** Keep the live status stream authoritative once it has supplied a value for a session. */
internal fun runningStateFromSnapshot(existing: Boolean?, snapshot: Boolean): Boolean = existing ?: snapshot
