package dev.dsh.mobile.mesh.ui.screens.main

import dev.dsh.mobile.mesh.core.session.AssistantMessageNode
import dev.dsh.mobile.mesh.core.session.UserMessageNode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptReadingPositionsTest {
    private fun row(seq: Long) = TranscriptRow.Node(UserMessageNode(seq, null, emptyList(), null))

    @Test
    fun `reopening a session restores its own message coordinate after another session`() {
        val positions = TranscriptReadingPositions()
        positions.put("first", TranscriptReadingPosition(42, 27))
        positions.put("second", TranscriptReadingPosition(91, 8))

        // A newly loaded page changes the indices, but not the message sequence or pixel offset.
        val reopenedRows = listOf(row(10), row(20), row(42), row(50))
        val saved = positions.get("first")!!
        assertEquals(2, readingPositionIndex(reopenedRows, saved))
        assertEquals(27, saved.offset)
        assertEquals(91L, positions.get("second")?.seq)
        assertNull(positions.get("new session"))
    }

    @Test
    fun `replaced short follow snapshot cannot overwrite an older reading anchor`() {
        val saved = TranscriptReadingPosition(42, 75)
        val newWindow = listOf(row(100), row(110))
        assertFalse(shouldRecordReadingPosition(newWindow, saved, userScrolling = false, currentAtBottom = false))
        assertTrue(shouldRecordReadingPosition(newWindow, saved, userScrolling = true, currentAtBottom = false))
        assertTrue(shouldRecordReadingPosition(listOf(row(42), row(110)), saved, userScrolling = false, currentAtBottom = false))
        val savedBottom = saved.copy(atBottom = true)
        assertTrue(shouldRecordReadingPosition(newWindow, savedBottom, userScrolling = false, currentAtBottom = true))
        assertFalse(shouldRecordReadingPosition(newWindow, savedBottom, userScrolling = false, currentAtBottom = false))
    }

    @Test
    fun `missing anchor requests only one authoritative page and waits for other paging`() {
        val saved = TranscriptReadingPosition(42, 75)
        val tail = listOf(row(100), row(110))
        assertTrue(shouldFetchReadingAnchorPage(tail, saved, hasMore = true, loadingOlder = false, attempted = false))
        assertFalse(shouldFetchReadingAnchorPage(tail, saved, hasMore = true, loadingOlder = true, attempted = false))
        assertFalse(shouldFetchReadingAnchorPage(tail, saved, hasMore = true, loadingOlder = false, attempted = true))
        assertFalse(shouldFetchReadingAnchorPage(listOf(row(42), row(100)), saved, hasMore = true, loadingOlder = false, attempted = false))
        assertFalse(shouldFetchReadingAnchorPage(tail, saved, hasMore = false, loadingOlder = false, attempted = false))
        assertFalse(shouldFetchReadingAnchorPage(tail, saved.copy(atBottom = true), hasMore = true, loadingOlder = false, attempted = false))
    }

    @Test
    fun `a session saved at bottom returns to tail not first visible node in current turn`() {
        val rows = listOf(row(10), row(20), row(30))
        // At the tail, the viewport can begin on an earlier node of this same long turn.
        val saved = readingPositionOf(rows[1], offset = 125, atBottom = true)
        assertEquals(1, readingPositionIndex(rows, saved))
        assertTrue(shouldRestoreTranscriptToBottom(saved))
        assertFalse(shouldRestoreTranscriptToBottom(saved.copy(atBottom = false)))
    }

    @Test
    fun `provisional assistant survives shifting seq and durable settlement`() {
        fun assistant(seq: Long, turn: Int, step: Int, streaming: Boolean) = TranscriptRow.Node(
            AssistantMessageNode(seq, null, turn, step, emptyList(), streaming = streaming),
        )
        val provisional = readingPositionOf(assistant(101, 4, 2, true), 75)
        val settled = listOf(row(90), row(100), assistant(150, 4, 2, false))
        assertEquals(2, readingPositionIndex(settled, provisional))
        assertEquals(75, provisional.offset)
        // If this attempt has not arrived in the new snapshot, do not misidentify the
        // earlier user turn as the assistant row simply because its seq is smaller.
        assertEquals(-1, readingPositionIndex(settled.dropLast(1), provisional))
    }

    @Test
    fun `previously saved coordinates still restore after saver upgrade`() {
        val old = transcriptReadingPositionsSaver.restore(listOf("session", 42L, 27))
        assertEquals(TranscriptReadingPosition(42, 27), old?.get("session"))
        val v2 = transcriptReadingPositionsSaver.restore(listOf("v2", "session", 101L, 75, 4, 2))
        assertEquals(TranscriptReadingPosition(101, 75, assistantTurn = 4, assistantStep = 2), v2?.get("session"))
        val current = TranscriptReadingPositions().apply {
            put("session", TranscriptReadingPosition(101, 75, assistantTurn = 4, assistantStep = 2, atBottom = true))
        }
        val saved = transcriptReadingPositionsSaver.run {
            // Only primitive values are stored in the Android saved-state bundle.
            with(object : androidx.compose.runtime.saveable.SaverScope {
                override fun canBeSaved(value: Any): Boolean = true
            }) { save(current) }
        }
        assertEquals(current.get("session"), transcriptReadingPositionsSaver.restore(saved!!)?.get("session"))
    }

    @Test
    fun `reading positions remain owned while the session list replaces chat`() {
        val root = File("src/main/java/dev/dsh/mobile/mesh/ui/AppRoot.kt").readText()
        val main = File("src/main/java/dev/dsh/mobile/mesh/ui/screens/main/MainScreen.kt").readText()
        val chat = File("src/main/java/dev/dsh/mobile/mesh/ui/screens/main/ChatScreen.kt").readText()
        assertTrue(root.contains("val readingPositions = rememberSaveable(connection.host?.id ?: renderedConnectedHostId"))
        assertTrue(root.contains("showMain -> MainScreen(\n                readingPositions = readingPositions,"))
        assertTrue(main.contains("ChatScreen(\n                readingPositions = readingPositions,"))
        assertFalse(chat.contains("val readingPositions = rememberSaveable"))
    }

    @Test
    fun `reading inside the final tall turn does not follow its beginning on new messages`() {
        // The last item is visible in both cases. Its index alone cannot tell whether the
        // reader is at its top (hundreds of pixels remain) or at the actual content bottom.
        assertFalse(transcriptNearBottom(3, 2, lastVisibleEnd = 2800, viewportEnd = 800, tolerancePx = 48))
        assertTrue(transcriptNearBottom(3, 2, lastVisibleEnd = 820, viewportEnd = 800, tolerancePx = 48))
        assertFalse(transcriptNearBottom(3, 1, lastVisibleEnd = 800, viewportEnd = 800, tolerancePx = 48))
    }

    @Test
    fun `a cancelled initial scroll never marks the session restored`() {
        val source = File("src/main/java/dev/dsh/mobile/mesh/ui/screens/main/ChatTranscript.kt").readText()
        val effect = source.substringAfter("LaunchedEffect(newestSeq, sessionId) {")
            .substringBefore("// The opening follow snapshot")
        val savedScroll = effect.indexOf("listState.scrollToItem(index")
        val tailScroll = effect.indexOf("scrollTranscriptToEnd(listState")
        val committed = effect.indexOf("lastSession = sessionId")
        assertTrue(savedScroll >= 0 && tailScroll >= 0)
        assertTrue(committed > savedScroll && committed > tailScroll)
        assertTrue(effect.indexOf("restoredSession = sessionId") > committed)
    }

    @Test
    fun `a structural only first page waits for readable rows before restoring`() {
        assertFalse(canRestoreReadingPosition(emptyList()))
        assertTrue(canRestoreReadingPosition(listOf(row(42))))
    }

    @Test
    fun `folded process restores after history window drops its turn start`() {
        val folded = TranscriptRow.Process(TranscriptPart.Process(25, listOf(row(42).node, row(43).node)))
        val saved = readingPositionOf(folded, offset = 9)
        // With no turn/start the next server window renders the same events as ordinary rows.
        assertEquals(42L, saved.seq)
        assertEquals(1, readingPositionIndex(listOf(row(30), row(42), row(43)), saved))
        assertEquals(0, readingPositionIndex(listOf(folded, row(60)), saved))
    }

    @Test
    fun `missing rows never restore to a different turn beginning`() {
        assertEquals(-1, readingPositionIndex(listOf(row(10), row(30), row(60)), TranscriptReadingPosition(42, 0)))
        assertEquals(-1, readingPositionIndex(listOf(row(60), row(90)), TranscriptReadingPosition(42, 0)))
        val disclosure = TranscriptRow.Process(TranscriptPart.Process(25, listOf(row(42).node)))
        assertEquals(1, readingPositionIndex(listOf(row(10), disclosure, row(60)), TranscriptReadingPosition(42, 0)))
    }
}
