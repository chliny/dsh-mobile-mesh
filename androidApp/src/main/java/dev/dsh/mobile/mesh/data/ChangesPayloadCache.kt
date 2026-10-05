package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.core.wire.dto.ChangesDiff
import dev.dsh.mobile.mesh.core.wire.dto.ChangesSummary

internal const val MAX_CACHED_CHANGES_SUMMARIES = 32
internal const val MAX_CACHED_CHANGES_DIFFS = 64
internal const val MAX_RETAINED_CHANGES_DIFF_CHARS = 512 * 1024

/**
 * What the harness last answered for the changed-files rows of one session's transcript.
 *
 * `changes.summary` and `changes.diff` are addressed by the durable event's own `(seq, index)`, so
 * their answers are facts about an event rather than live state. They still cost two round trips
 * each, and the transcript is thrown away every time the reader opens a full diff, a file preview,
 * the terminal or the workspace browser — so a row came back showing "loading" and re-fetched
 * content it had already been given. Keeping the answer keyed by the same identity the row uses
 * makes returning to the transcript render the same content with no request at all.
 *
 * This is a render cache, not a second source of truth: the server still computes every value and
 * the cache only ever holds what it returned, dropped on [clear] when the host changes.
 *
 * Diffs hold whole file bodies, and a reader only ever opens a few, so entries are bounded twice
 * over: by count, and by refusing an individual diff too large to keep. Refusing one is deliberate —
 * re-fetching a multi-megabyte diff on the next visit is cheap next to holding it for the process's
 * lifetime.
 */
internal class ChangesPayloadCache {
    private val lock = Any()
    private val summaries = LruCache<SummaryKey, ChangesSummary>(MAX_CACHED_CHANGES_SUMMARIES)
    private val diffs = LruCache<DiffKey, ChangesDiff>(MAX_CACHED_CHANGES_DIFFS)

    private fun summary(sessionId: String, seq: Long): ChangesSummary? = synchronized(lock) { summaries.get(SummaryKey(sessionId, seq)) }

    fun diff(sessionId: String, seq: Long, index: Int): ChangesDiff? = synchronized(lock) { diffs.get(DiffKey(sessionId, seq, index)) }

    fun recordSummary(sessionId: String, seq: Long, value: ChangesSummary) = synchronized(lock) {
        summaries.put(SummaryKey(sessionId, seq), value)
    }

    fun recordDiff(sessionId: String, seq: Long, index: Int, value: ChangesDiff) = synchronized(lock) {
        if (value.retainedChars() > MAX_RETAINED_CHANGES_DIFF_CHARS) return@synchronized
        diffs.put(DiffKey(sessionId, seq, index), value)
    }

    /** A new host is a new set of sessions: nothing cached for the old one means anything. */
    fun clear() = synchronized(lock) {
        summaries.clear()
        diffs.clear()
    }

    internal fun summaryCount(): Int = synchronized(lock) { summaries.size }

    internal fun diffCount(): Int = synchronized(lock) { diffs.size }

    private data class SummaryKey(val sessionId: String, val seq: Long)

    private data class DiffKey(val sessionId: String, val seq: Long, val index: Int)
}

/** Roughly what one cached diff costs to hold, so the cap can be a size and not a file count. */
internal fun ChangesDiff.retainedChars(): Int = when (this) {
    is ChangesDiff.Text -> hunks.sumOf { hunk -> hunk.lines.sumOf { it.length } }
    is ChangesDiff.Unavailable -> 0
}

/** Access-ordered and self-trimming: [get] counts as a use, so a revisited diff is not the one dropped. */
private class LruCache<K, V>(private val maxSize: Int) {
    private val entries = object : LinkedHashMap<K, V>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxSize
    }

    fun get(key: K): V? = entries[key]

    fun put(key: K, value: V) {
        entries[key] = value
    }

    fun clear() = entries.clear()

    val size: Int get() = entries.size
}
