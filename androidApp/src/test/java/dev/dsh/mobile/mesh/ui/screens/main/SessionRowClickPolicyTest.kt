package dev.dsh.mobile.mesh.ui.screens.main

import dev.dsh.mobile.mesh.core.session.ConversationSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

class SessionRowClickPolicyTest {
    @Test
    fun `child row click opens its own session rather than toggling parent`() {
        assertTrue(shouldOpenSessionOnSessionClick(childCount = 0, isSubagent = true))
    }

    @Test
    fun `queued switch does not expose previous conversation while network is slow`() = runBlocking {
        val selected = MutableStateFlow<String?>("previous")
        val closeDrawer = async(start = CoroutineStart.UNDISPATCHED) {
            awaitSessionSelected(selected, "requested")
        }
        assertFalse(closeDrawer.isCompleted) // The open request was only queued.
        selected.value = "unrelated"
        assertFalse(closeDrawer.isCompleted) // A different selection is not completion.
        selected.value = "requested"
        closeDrawer.await()
        assertTrue(closeDrawer.isCompleted)
    }

    @Test
    fun `docks cannot display the previous session while selection and snapshot are out of sync`() {
        val previous = ConversationSnapshot("previous")
        assertNull(conversationForSelectedSession(previous, "requested"))
        assertNull(conversationForSelectedSession(null, "requested"))
        assertSame(previous, conversationForSelectedSession(previous, "previous"))
    }

    @Test
    fun `selected session opens before its follow snapshot arrives`() = runBlocking {
        awaitSessionSelected(MutableStateFlow("requested"), "requested")
    }

    @Test
    fun `parent row click expands when it has collapsed children`() {
        assertTrue(shouldExpandChildrenOnSessionClick(childCount = 1, childrenExpanded = false))
    }

    @Test
    fun `parent row click does not re-expand already expanded children`() {
        assertFalse(shouldExpandChildrenOnSessionClick(childCount = 1, childrenExpanded = true))
    }

    @Test
    fun `leaf row click never expands`() {
        assertFalse(shouldExpandChildrenOnSessionClick(childCount = 0, childrenExpanded = false))
    }
}
