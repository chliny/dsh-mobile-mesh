package dev.dsh.mobile.mesh.data

/** History is not a live execution snapshot; use it only before a status or list value is known. */
internal fun runningStateFromSnapshot(existing: Boolean?, snapshot: Boolean): Boolean = existing ?: snapshot

/** A status event received after a list request outranks that request's older snapshot. */
internal fun runningStateFromList(
    listed: Boolean,
    live: Boolean?,
    statusRevision: Long?,
    requestStartRevision: Long,
): Boolean = if (statusRevision != null && statusRevision > requestStartRevision) live ?: listed else listed
