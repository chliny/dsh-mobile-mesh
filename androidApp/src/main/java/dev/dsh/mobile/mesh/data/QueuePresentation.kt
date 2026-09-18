package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.core.session.QueueItem

/** Merge locally accepted queue submissions with the authoritative inbox snapshot. */
internal fun mergePendingQueue(
    authoritative: List<QueueItem>,
    pending: List<QueueItem>,
): List<QueueItem> {
    val unmatched = authoritative.groupingBy { it.messageText }.eachCount().toMutableMap()
    val visiblePending = pending.filter { item ->
        val count = unmatched[item.messageText] ?: 0
        if (count > 0) {
            unmatched[item.messageText] = count - 1
            false
        } else {
            true
        }
    }
    return authoritative + visiblePending
}
