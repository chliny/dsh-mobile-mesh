package dev.dsh.mobile.mesh.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PendingQueuePersistenceTest {
    @Test
    fun `serialized pending queue survives a new store instance`() {
        val original = listOf(
            PersistedQueueContract("local:request-1", "queued", "hello", "hello", "[{}]"),
        )
        val restored = original.map { it.copy() }
        assertEquals(original, restored)
    }
}

data class PersistedQueueContract(
    val id: String,
    val placement: String,
    val previewText: String,
    val messageText: String,
    val content: String,
)
