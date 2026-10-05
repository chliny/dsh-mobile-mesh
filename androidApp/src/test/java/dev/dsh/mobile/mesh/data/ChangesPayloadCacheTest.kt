package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.core.wire.dto.ChangedFile
import dev.dsh.mobile.mesh.core.wire.dto.ChangesDiff
import dev.dsh.mobile.mesh.core.wire.dto.ChangesDiffHunk
import dev.dsh.mobile.mesh.core.wire.dto.ChangesSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression cover for the transcript keeping what the harness already answered.
 *
 * Returning from a full diff rebuilt the changed-files rows from nothing, so each one showed its
 * loading line and asked again for content the reader had just been shown.
 */
class ChangesPayloadCacheTest {
    private fun summary(total: Int) = ChangesSummary(turn = 1, files = listOf(ChangedFile("a.kt", "a.kt", 3, 1)), total = total, added = 3, deleted = 1)

    private fun textDiff(chars: Int) = ChangesDiff.Text(
        path = "a.kt",
        display = "a.kt",
        before = true,
        after = true,
        coarse = false,
        hunks = listOf(ChangesDiffHunk(1, 1, 1, 1, listOf("x".repeat(chars)))),
    )

    @Test
    fun `a summary already fetched is served without asking again`() {
        val cache = ChangesPayloadCache()
        cache.recordSummary("s1", 42, summary(1))

        assertEquals(1, cache.summary("s1", 42)?.total)
    }

    @Test
    fun `each event and each file is addressed separately`() {
        val cache = ChangesPayloadCache()
        cache.recordSummary("s1", 42, summary(1))
        cache.recordSummary("s1", 43, summary(2))
        cache.recordDiff("s1", 42, 0, textDiff(4))
        cache.recordDiff("s1", 42, 1, textDiff(8))

        assertEquals(1, cache.summary("s1", 42)?.total)
        assertEquals(2, cache.summary("s1", 43)?.total)
        assertEquals(4, (cache.diff("s1", 42, 0) as ChangesDiff.Text).hunks.single().lines.single().length)
        assertEquals(8, (cache.diff("s1", 42, 1) as ChangesDiff.Text).hunks.single().lines.single().length)
        assertNull(cache.diff("s1", 43, 0))
    }

    @Test
    fun `one session never serves another session's event`() {
        val cache = ChangesPayloadCache()
        cache.recordSummary("s1", 42, summary(1))

        assertNull(cache.summary("s2", 42))
    }

    @Test
    fun `a new host drops everything cached for the old one`() {
        val cache = ChangesPayloadCache()
        cache.recordSummary("s1", 42, summary(1))
        cache.recordDiff("s1", 42, 0, textDiff(4))

        cache.clear()

        assertNull(cache.summary("s1", 42))
        assertNull(cache.diff("s1", 42, 0))
        assertEquals(0, cache.summaryCount())
        assertEquals(0, cache.diffCount())
    }

    @Test
    fun `a diff too large to hold is left to be asked for again`() {
        val cache = ChangesPayloadCache()
        cache.recordDiff("s1", 42, 0, textDiff(MAX_RETAINED_CHANGES_DIFF_CHARS + 1))

        // Holding it for the process's lifetime is not worth one visit's re-fetch.
        assertNull(cache.diff("s1", 42, 0))
        assertEquals(0, cache.diffCount())
    }

    @Test
    fun `an unavailable diff is still cached`() {
        // It is an answer about the file — binary, oversized — not a failure to load one.
        val cache = ChangesPayloadCache()
        cache.recordDiff("s1", 42, 0, ChangesDiff.Unavailable("binary", "logo.png", "logo.png"))

        assertEquals("binary", cache.diff("s1", 42, 0)?.kind)
    }

    @Test
    fun `the least recently used event is dropped once the bound is passed`() {
        val cache = ChangesPayloadCache()
        (0..MAX_CACHED_CHANGES_SUMMARIES).forEach { cache.recordSummary("s1", it.toLong(), summary(it)) }

        assertTrue(cache.summaryCount() <= MAX_CACHED_CHANGES_SUMMARIES)
        assertNull(cache.summary("s1", 0))
        assertTrue(cache.summary("s1", MAX_CACHED_CHANGES_SUMMARIES.toLong()) != null)
    }

    @Test
    fun `a revisited diff is not the one dropped`() {
        val cache = ChangesPayloadCache()
        cache.recordDiff("s1", 42, 0, textDiff(4))
        // Opening the full diff and coming back is exactly this: a hit on what is already held.
        repeat(MAX_CACHED_CHANGES_DIFFS) { index ->
            cache.recordDiff("s1", 43, index, textDiff(4))
            assertTrue(cache.diff("s1", 42, 0) != null)
        }
    }

    /**
     * The cache is only worth holding if the rows read it before they ask. SessionStore owns
     * Android and network dependencies and cannot be built in a JVM test, so the wiring this
     * behaviour depends on is asserted against the sources that carry it.
     */
    @Test
    fun `the changed-files rows render from the cache and only ask when it is empty`() {
        val row = File("src/main/java/dev/dsh/mobile/mesh/ui/screens/main/ChangesRow.kt").readText()

        // A returning row has to have its content on the first frame, not after a loading line.
        assertTrue(row.contains("store.changesSummaryNow(sessionId, node.seq)"))
        assertTrue(row.contains("store.changesDiffNow(sessionId, seq, index)"))
        // And with content in hand it must not spend the round trip again.
        assertTrue(row.contains("if (summary != null) return@LaunchedEffect"))
        assertTrue(row.contains("if (diff != null) return@LaunchedEffect"))
    }

    @Test
    fun `a new host drops the cached payloads with the cached conversations`() {
        val store = File("src/main/java/dev/dsh/mobile/mesh/data/SessionStore.kt").readText()
        val clear = store.substringAfter("conversationCache.clear()", "")
        assertTrue(clear.contains("changesPayloads.clear()"))
    }
}
