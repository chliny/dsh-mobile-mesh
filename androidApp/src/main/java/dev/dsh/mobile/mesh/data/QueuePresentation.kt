package dev.dsh.mobile.mesh.data

/** True while an accepted queue submission has not appeared in the server queue snapshot. */
internal fun queueSubmissionPending(hasPendingPrompt: Boolean, queue: List<String>): Boolean =
    hasPendingPrompt && queue.none { it == "queued" }
