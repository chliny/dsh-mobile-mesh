package dev.dsh.mobile.mesh.core.session

import dev.dsh.mobile.mesh.core.wire.dto.JobFollowFrame
import dev.dsh.mobile.mesh.core.wire.dto.JobView

/** Bounded rendered output and absolute resume offset for one followed background job. */
data class JobOutputSnapshot(
    val job: JobView? = null,
    val text: String = "",
    val cursor: Long? = null,
    val gapBefore: Boolean = false,
    val lossy: Boolean = false,
    val error: String? = null,
)

/** Applies ordered job-follow frames while retaining only the most recent [maxChars] characters. */
class JobOutputBuffer(private val maxChars: Int = DEFAULT_MAX_CHARS) {
    init {
        require(maxChars > 0)
    }

    var snapshot: JobOutputSnapshot = JobOutputSnapshot()
        private set

    fun apply(frame: JobFollowFrame) {
        when (frame) {
            is JobFollowFrame.Opened -> {
                val nextCursor = snapshot.cursor ?: frame.from
                val freshPastHead = snapshot.text.isEmpty() && frame.from > 0
                val evictedPrefix = nextCursor < frame.job.output.earliest
                val openingGap = evictedPrefix || freshPastHead
                snapshot = snapshot.copy(
                    job = frame.job,
                    cursor = if (evictedPrefix) frame.job.output.earliest else nextCursor,
                    gapBefore = snapshot.gapBefore || openingGap,
                    lossy = snapshot.lossy || (nextCursor < frame.job.output.earliest),
                    error = null,
                )
            }
            is JobFollowFrame.Output -> {
                val oldCursor = snapshot.cursor
                val hadChunkGap = frame.chunks.any { it.gapBefore == true } ||
                    (oldCursor != null && frame.chunks.firstOrNull()?.at?.let { it > oldCursor } == true)
                val append = snapshot.text + frame.chunks.joinToString(separator = "") { it.text }
                val gap = snapshot.gapBefore || frame.lossy == true || hadChunkGap
                val trimmed = append.length > maxChars
                snapshot = snapshot.copy(
                    text = append.takeLast(maxChars),
                    cursor = maxOf(oldCursor ?: frame.next, frame.next),
                    gapBefore = gap || trimmed,
                    lossy = snapshot.lossy || frame.lossy == true || frame.chunks.any { it.gapBefore == true },
                )
            }
            is JobFollowFrame.Status -> snapshot = snapshot.copy(job = frame.job, error = null)
            is JobFollowFrame.Unknown -> Unit
        }
    }

    fun fail(message: String) {
        snapshot = snapshot.copy(error = message)
    }

    companion object {
        const val DEFAULT_MAX_CHARS = 32_000
    }
}
