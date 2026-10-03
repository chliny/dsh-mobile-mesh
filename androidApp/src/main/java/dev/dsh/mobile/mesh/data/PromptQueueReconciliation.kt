package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.core.session.QueueItem

/** Correlate server-owned pending messages with submissions even when the control frame beats the RPC. */
internal class PromptQueueReconciliation {
    private val inFlight = mutableSetOf<String>()
    private val observedWhileInFlight = mutableSetOf<String>()

    fun begin(requestId: String) {
        inFlight += requestId
    }

    /** Returns request ids whose local transcript echoes must be removed. */
    fun observe(queue: List<QueueItem>): Set<String> {
        val queuedIds = queue.mapNotNullTo(mutableSetOf()) { it.rpcId }
        observedWhileInFlight += queuedIds.intersect(inFlight)
        return queuedIds
    }

    /** An inbox frame may already have come and gone before the prompt response arrives. */
    fun shouldRenderTranscript(
        requestId: String,
        mode: String,
        runningAtSubmission: Boolean,
        currentQueue: List<QueueItem>,
    ): Boolean {
        inFlight -= requestId
        val serverQueued = observedWhileInFlight.remove(requestId) ||
            currentQueue.any { it.rpcId == requestId }
        return !serverQueued && !shouldShowOptimisticQueue(mode, runningAtSubmission)
    }

    fun discard(requestId: String) {
        inFlight -= requestId
        observedWhileInFlight.remove(requestId)
    }
}
