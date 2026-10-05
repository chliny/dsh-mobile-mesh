package dev.dsh.mobile.mesh.ui.screens.main

import dev.dsh.mobile.mesh.core.session.CommandNode
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression cover for expansion state outliving the transcript.
 *
 * Opening the full diff of a changed file navigates to a page that unmounts the chat screen
 * entirely, so every `remember`-scoped expanded flag died with the rows that held it and the reader
 * came back to a fully collapsed conversation.
 */
class TranscriptDisclosuresTest {
    private val screenDir = File("src/main/java/dev/dsh/mobile/mesh/ui/screens/main")

    private fun command(seq: Long) = CommandNode(seq, "command/run", buildJsonObject { put("name", "inspect") })

    @Test
    fun `a row opened in one composition is still open in the next`() {
        val scope = DisclosureScope(TranscriptDisclosures(), "session-1")
        val key = DisclosureKeys.changes(42)

        assertFalse(scope.binding(key).expanded)
        scope.binding(key).onToggle()

        // What the transcript gets when the reader comes back from the diff page.
        assertTrue(scope.binding(key).expanded)
    }

    @Test
    fun `a closed row stays closed for the composition that replaces it`() {
        val scope = DisclosureScope(TranscriptDisclosures(), "session-1")
        val key = DisclosureKeys.changes(42)

        scope.binding(key).onToggle()
        scope.binding(key).onToggle()

        assertFalse(scope.binding(key).expanded)
    }

    @Test
    fun `each session keeps its own open rows`() {
        val disclosures = TranscriptDisclosures()
        val here = DisclosureScope(disclosures, "session-1")
        val there = DisclosureScope(disclosures, "session-2")
        val key = DisclosureKeys.changes(42)

        here.binding(key).onToggle()

        assertTrue(here.isOpen(key))
        assertFalse(there.isOpen(key))
    }

    @Test
    fun `two rows on one node do not share a flag`() {
        val scope = DisclosureScope(TranscriptDisclosures(), "session-1")

        scope.binding(DisclosureKeys.todo("call-7")).onToggle()

        assertTrue(scope.isOpen(DisclosureKeys.todo("call-7")))
        assertFalse(scope.isOpen(DisclosureKeys.todoUnchanged("call-7")))
    }

    @Test
    fun `an untouched card keeps its default until the reader sets it`() {
        val scope = DisclosureScope(TranscriptDisclosures(), "session-1")
        val key = DisclosureKeys.detailsCard("session")

        // Open by default, but not yet decided by the reader — which is not the same as closed.
        assertFalse(scope.isSet(key))
        assertFalse(scope.isOpen(key))

        scope.setOpen(key, false)
        assertTrue(scope.isSet(key))
        assertFalse(scope.isOpen(key))

        scope.setOpen(key, true)
        assertTrue(scope.isOpen(key))
    }

    @Test
    fun `the oldest session is dropped whole once the retention bound is passed`() {
        val disclosures = TranscriptDisclosures()
        (0..TRANSCRIPT_DISCLOSURE_MAX_RETAINED_SESSIONS).forEach { index ->
            DisclosureScope(disclosures, "session-$index").binding(DisclosureKeys.changes(1)).onToggle()
        }

        assertTrue(disclosures.sessionCount() <= TRANSCRIPT_DISCLOSURE_MAX_RETAINED_SESSIONS)
        assertFalse(DisclosureScope(disclosures, "session-0").isOpen(DisclosureKeys.changes(1)))
        assertTrue(
            DisclosureScope(disclosures, "session-$TRANSCRIPT_DISCLOSURE_MAX_RETAINED_SESSIONS")
                .isOpen(DisclosureKeys.changes(1)),
        )
    }

    @Test
    fun `the process fold reads the same holder the transcript toggles`() {
        val scope = DisclosureScope(TranscriptDisclosures(), "session-1")
        val parts = listOf(TranscriptPart.Process(2, listOf(command(1), command(3))))
        val rows = { buildTranscriptRows(parts) { scope.isOpen(DisclosureKeys.process(it)) } }

        assertEquals(1, rows().size)

        scope.setOpen(DisclosureKeys.process(2), true)

        assertEquals(3, rows().size)
    }

    /**
     * The invariant the bug violated: a row that keeps its own expanded flag cannot survive leaving
     * the transcript, and every surface below is unmounted by the diff page, the file preview, the
     * terminal and the workspace browser.
     */
    @Test
    fun `conversation surfaces keep no local expanded flag`() {
        listOf(
            "ChangesRow.kt",
            "ChatNodeItem.kt",
            "TranscriptActivityRows.kt",
            "ChatTranscript.kt",
            "Docks.kt",
            "TrajectoryTab.kt",
            "DetailsPanel.kt",
        ).forEach { name ->
            val source = File(screenDir, name).readText()
            assertFalse(name, source.contains("expanded by remember"))
            assertFalse(name, source.contains("expanded = !expanded"))
        }
    }

    @Test
    fun `the disclosure holder is owned above the page that unmounts the transcript`() {
        val appRoot = File("src/main/java/dev/dsh/mobile/mesh/ui/AppRoot.kt").readText()
        assertTrue(appRoot.contains("remember(activeHostKey) { TranscriptDisclosures() }"))

        // MainScreen returns before ChatScreen is composed on the diff, files and terminal pages, so
        // a holder created inside it would be destroyed by exactly the navigation this survives.
        assertFalse(File(screenDir, "MainScreen.kt").readText().contains("TranscriptDisclosures()"))
    }
}