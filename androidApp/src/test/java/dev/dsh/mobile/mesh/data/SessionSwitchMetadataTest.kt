package dev.dsh.mobile.mesh.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSwitchMetadataTest {
    @Test
    fun `blocked catalog request cannot keep selection worker occupied`() = runBlocking {
        val metadataStarted = CompletableDeferred<Unit>()
        val unblockMetadata = CompletableDeferred<Unit>()
        val refresh = launchSessionMetadataRefresh(this) {
            metadataStarted.complete(Unit)
            unblockMetadata.await() // Simulates an old-generation catalog RPC that never answers.
        }
        try {
            withTimeout(1_000) { metadataStarted.await() }
            // The switch caller is free to process another tap while metadata remains blocked.
            assertTrue(refresh.isActive)
            withTimeout(1_000) { launchSessionMetadataRefresh(this) {}.join() }
        } finally {
            refresh.cancelAndJoin()
        }
    }
}
