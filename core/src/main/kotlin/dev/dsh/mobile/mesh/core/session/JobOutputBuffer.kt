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
                val oldCursor = snapshot.cursor
                val nextCursor = maxOf(oldCursor ?: frame.from, frame.from)
                val freshPastHead = snapshot.text.isEmpty() && frame.from > 0
                val evictedPrefix = nextCursor < frame.job.output.earliest
                val openingGap = evictedPrefix || freshPastHead || (oldCursor != null && frame.from > oldCursor)
                snapshot = snapshot.copy(
                    job = frame.job,
                    text = if (openingGap) "" else snapshot.text,
                    cursor = if (evictedPrefix) frame.job.output.earliest else nextCursor,
                    gapBefore = snapshot.gapBefore || openingGap,
                    lossy = snapshot.lossy || evictedPrefix,
                    error = null,
                )
            }
            is JobFollowFrame.Output -> {
                val oldCursor = snapshot.cursor
                val lastMarkedGap = frame.chunks.indexOfLast { it.gapBefore == true }
                val openingGap = oldCursor != null && frame.chunks.firstOrNull()?.at?.let { it > oldCursor } == true
                val emptyAdvance = frame.chunks.isEmpty() && oldCursor != null && frame.next > oldCursor
                val hadChunkGap = lastMarkedGap >= 0 || openingGap || emptyAdvance
                // The UI has only one gap marker, before the rendered text. Keep the contiguous
                // suffix after the last hole rather than joining disjoint output into one log.
                val suffixStart = if (lastMarkedGap >= 0) lastMarkedGap else 0
                val prefix = if (hadChunkGap) "" else snapshot.text
                val append = prefix + frame.chunks.drop(suffixStart).joinToString(separator = "") { it.text }
                val gap = snapshot.gapBefore || frame.lossy == true || hadChunkGap
                val trimmed = append.length > maxChars
                val tail = append.takeLast(maxChars)
                // String length counts UTF-16 code units. If trimming cuts through a surrogate
                // pair, omit the orphaned low surrogate instead of rendering a replacement glyph.
                val safeTail = if (trimmed && tail.firstOrNull()?.isLowSurrogate() == true) tail.drop(1) else tail
                snapshot = snapshot.copy(
                    text = safeTail,
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
