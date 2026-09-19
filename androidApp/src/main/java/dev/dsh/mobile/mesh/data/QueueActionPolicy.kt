package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.core.session.QueueItem

internal fun canMutateQueueItem(item: QueueItem): Boolean =
    !item.id.startsWith("local:")

internal fun canSteerQueueItem(item: QueueItem, running: Boolean): Boolean =
    canMutateQueueItem(item) && running
