package dev.dsh.mobile.mesh.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionReconnectPolicyTest {
    @Test
    fun `blocked optional catalog cannot delay follow reopening or the next generation`() {
        runBlocking {
            val releaseCatalog = CompletableDeferred<Unit>()
            val catalogStarted = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            recoverConnectedSession(
                scope = this,
                reopen = { events += "follow-1" },
                refreshCatalog = {
                    events += "catalog-1"
                    catalogStarted.complete(Unit)
                    releaseCatalog.await()
                },
            )
            assertEquals(listOf("follow-1"), events)
            catalogStarted.await()
            recoverConnectedSession(
                scope = this,
                reopen = { events += "follow-2" },
                refreshCatalog = { events += "catalog-2" },
            )
            assertEquals(listOf("follow-1", "catalog-1", "follow-2"), events)
            yield()
            assertEquals(listOf("follow-1", "catalog-1", "follow-2", "catalog-2"), events)
            releaseCatalog.complete(Unit)
        }
    }

    @Test
    fun `generation without selected session still refreshes catalog exactly once`() = runBlocking {
        var calls = 0
        recoverConnectedSession(this, reopen = {}, refreshCatalog = { calls++ })
        yield()
        assertEquals(1, calls)
    }

    @Test
    fun `permission catalog refresh is coalesced per generation and retried after failure`() {
        assertTrue(shouldRefreshPermissionCatalog("generation-1", null))
        assertFalse(shouldRefreshPermissionCatalog("generation-1", "generation-1"))
        assertTrue(shouldRefreshPermissionCatalog("generation-2", "generation-1"))
    }

    @Test
    fun `selected session reopens only after a new connection generation exists`() {
        assertTrue(shouldReopenSessionAfterReconnect(true, true))
        assertFalse(shouldReopenSessionAfterReconnect(true, false))
        assertFalse(shouldReopenSessionAfterReconnect(false, true))
    }
}
