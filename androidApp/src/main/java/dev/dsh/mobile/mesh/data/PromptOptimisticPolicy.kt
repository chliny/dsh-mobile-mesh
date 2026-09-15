package dev.dsh.mobile.mesh.data

internal enum class PromptOptimisticDisplay {
    TRANSCRIPT,
    QUEUE,
}

/** A running turn owns queued text; an idle turn can render the accepted prompt in the transcript. */
internal fun promptOptimisticDisplay(running: Boolean): PromptOptimisticDisplay =
    if (running) PromptOptimisticDisplay.QUEUE else PromptOptimisticDisplay.TRANSCRIPT
