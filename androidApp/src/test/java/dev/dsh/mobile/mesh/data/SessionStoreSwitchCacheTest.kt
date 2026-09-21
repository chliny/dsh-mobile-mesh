package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.core.session.ConversationSnapshot
import dev.dsh.mobile.mesh.core.session.QueueItem
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the state-machine invariants around SessionStore.openSession().
 *
 * SessionStore owns Android/coroutine/network dependencies, so constructing it in a JVM test would
 * exercise wiring rather than the behavior this plan is concerned with. This small deterministic
 * model deliberately exposes the two contracts that must remain true in the production code:
 * latest-wins selection and cache-first rendering with current-session snapshot isolation.
 */
class SessionStoreSwitchCacheTest {
    @Test
    fun `connected state without published api is a publication race`() {
        assertTrue(requiresApiPublication(dev.dsh.mobile.mesh.connection.ConnectionPhase.CONNECTED, apiPresent = false))
        assertFalse(requiresApiPublication(dev.dsh.mobile.mesh.connection.ConnectionPhase.CONNECTED, apiPresent = true))
    }

    @Test
    fun `session creation notification requests an authoritative list refresh`() {
        assertTrue(shouldRefreshSessionsForNotification("api-session/added"))
        assertFalse(shouldRefreshSessionsForNotification("api-session/activity"))
    }

    @Test
    fun `already connected store must open control baseline`() {
        assertTrue(shouldOpenControlBaseline(dev.dsh.mobile.mesh.connection.ConnectionPhase.CONNECTED))
        assertFalse(shouldOpenControlBaseline(dev.dsh.mobile.mesh.connection.ConnectionPhase.RECONNECTING))
    }

    @Test
    fun `remote cancellation can correlate against the visible interaction`() {
        assertEquals("session-1", correlateCancelledSession("event-1", "event-1", "session-1"))
        assertNull(correlateCancelledSession("event-2", "event-1", "session-1"))
    }

    @Test
    fun `authoritative idle session state clears stale interaction`() {
        assertTrue(shouldClearPendingInteractionFromSessionState(running = false))
        assertFalse(shouldClearPendingInteractionFromSessionState(running = true))
    }

    @Test
    fun `first open without cache has no conversation until follow snapshot arrives`() {
        val model = SessionSwitchCacheModel()

        assertNull(model.requestOpen("first"))
        assertEquals("first", model.currentSessionId)
        assertNull(model.currentConversation)

        val snapshot = snapshot("first", blank = false, lastSeq = 3)
        assertTrue(model.acceptFollowSnapshot("first", snapshot))
        assertEquals(snapshot, model.currentConversation)
    }

    @Test
    fun `reopening a visited session publishes cache before delayed follow snapshot`() {
        val model = SessionSwitchCacheModel()
        val cached = snapshot(
            "cached",
            blank = false,
            hasMore = true,
            lastSeq = 12,
            queue = listOf(queue("queued-1")),
            projections = mapOf("title" to JsonPrimitive("Cached title")),
        )
        model.seed(cached)
        model.requestOpen("other")

        assertEquals(cached, model.requestOpen("cached"))
        assertEquals("cached", model.currentSessionId)
        assertFalse(model.currentConversation!!.blank)
        assertTrue(model.currentConversation!!.hasMore)
        assertEquals(cached.queue, model.currentConversation!!.queue)
        assertEquals(cached.projections, model.currentConversation!!.projections)
    }

    @Test
    fun `late snapshot from old session cannot overwrite latest selected session`() {
        val model = SessionSwitchCacheModel()
        val old = snapshot("old", blank = false, lastSeq = 1)
        val newest = snapshot("newest", blank = false, lastSeq = 9)
        model.seed(old)

        model.requestOpen("old")
        model.requestOpen("newest")
        assertFalse(model.acceptFollowSnapshot("old", snapshot("old", blank = false, lastSeq = 99)))

        assertEquals("newest", model.currentSessionId)
        assertNull(model.currentConversation)
        assertFalse(model.acceptFollowSnapshot("old", old))
        assertTrue(model.acceptFollowSnapshot("newest", newest))
        assertEquals(newest, model.currentConversation)
    }

    @Test
    fun `rapid taps keep only final pending target`() {
        val model = SessionSwitchCacheModel()

        model.enqueue("a")
        model.enqueue("b")
        model.enqueue("c")

        assertEquals("c", model.drainLatest())
        assertNull(model.drainLatest())
        assertEquals(listOf("c"), model.openedSessions)
    }

    @Test
    fun `selection received while the switch worker finishes is processed next`() {
        val model = SessionSwitchCacheModel()

        model.enqueue("first")
        assertEquals("first", model.drainLatest())
        model.enqueue("second")

        assertEquals("second", model.drainLatest())
        assertEquals(listOf("first", "second"), model.openedSessions)
    }

    @Test
    fun `cache evicts least recently used conversation after eight entries`() {
        val model = SessionSwitchCacheModel()
        repeat(8) { index -> model.seed(snapshot("s$index", lastSeq = index.toLong())) }

        // Reading s0 makes it most recently used, so s1 is the oldest after this insertion.
        assertEquals("s0", model.requestOpen("s0")?.sessionId)
        model.seed(snapshot("s8", lastSeq = 8))

        assertEquals("s0", model.requestOpen("s0")?.sessionId)
        assertNull(model.requestOpen("s1"))
    }

    private fun snapshot(
        id: String,
        blank: Boolean = true,
        hasMore: Boolean = false,
        lastSeq: Long = -1,
        queue: List<QueueItem> = emptyList(),
        projections: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
    ) = ConversationSnapshot(
        sessionId = id,
        blank = blank,
        hasMore = hasMore,
        lastSeq = lastSeq,
        queue = queue,
        projections = projections,
    )

    private fun queue(id: String) = QueueItem(
        id = id,
        placement = "queued",
        previewText = "message",
        content = JsonPrimitive("message"),
    )
}

/** JVM-only executable contract for SessionStore's latest-wins/cache-first behavior. */
private class SessionSwitchCacheModel {
    private val cache = object : LinkedHashMap<String, ConversationSnapshot>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ConversationSnapshot>?): Boolean = size > 8
    }
    private var pending: String? = null
    private var selected: String? = null
    private var conversation: ConversationSnapshot? = null

    val currentSessionId: String? get() = selected
    val currentConversation: ConversationSnapshot? get() = conversation
    val openedSessions = mutableListOf<String>()

    fun seed(snapshot: ConversationSnapshot) {
        cache[snapshot.sessionId] = snapshot
    }

    fun requestOpen(sessionId: String): ConversationSnapshot? {
        selected = sessionId
        conversation = cache[sessionId]
        return conversation
    }

    fun acceptFollowSnapshot(sessionId: String, snapshot: ConversationSnapshot): Boolean {
        if (selected != sessionId) return false
        cache[sessionId] = snapshot
        conversation = snapshot
        return true
    }

    fun enqueue(sessionId: String) {
        pending = sessionId
    }

    fun drainLatest(): String? {
        val next = pending ?: return null
        pending = null
        requestOpen(next)
        openedSessions += next
        return next
    }
}
