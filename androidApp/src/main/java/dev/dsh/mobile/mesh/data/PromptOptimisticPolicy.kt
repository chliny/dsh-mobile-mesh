package dev.dsh.mobile.mesh.data

internal enum class PromptOptimisticDisplay {
    TRANSCRIPT,
    QUEUE,
}

/** A running turn owns queued text; an idle turn can render the accepted prompt in the transcript. */
internal fun promptOptimisticDisplay(running: Boolean): PromptOptimisticDisplay =
    if (running) PromptOptimisticDisplay.QUEUE else PromptOptimisticDisplay.TRANSCRIPT

/** Queue-mode echoes count only when the session was already running at submission time. */
internal fun shouldShowOptimisticQueue(mode: String, runningAtSubmission: Boolean): Boolean =
    mode == "queue" && promptOptimisticDisplay(runningAtSubmission) == PromptOptimisticDisplay.QUEUE

/** Prefer either live running mirror when deciding whether a prompt belongs in the queue. */
internal fun runningAtSubmission(
    runningBySession: Boolean,
    sessionRowRunning: Boolean,
): Boolean = runningBySession || sessionRowRunning
