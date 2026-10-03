package dev.dsh.mobile.mesh.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Retry and explicit refresh must not be lost to once-per-generation baseline deduplication. */
class SessionStoreBaselineSchedulerTest {
    @Test
    fun `new connection generations queue but duplicate generation notifications coalesce`() {
        assertTrue(shouldQueueBaselineForGeneration("g1", null))
        assertFalse(shouldQueueBaselineForGeneration("g1", "g1"))
        assertTrue(shouldQueueBaselineForGeneration("g2", "g1"))
    }

    @Test
    fun `session create and notification refresh bypass generation dedupe`() {
        assertFalse(shouldQueueBaselineForRequest("g1", "g1", force = false))
        assertTrue(shouldQueueBaselineForRequest("g1", "g1", force = true))
        assertTrue(shouldQueueBaselineForRequest("g2", "g1", force = false))

        val source = java.io.File("src/main/java/dev/dsh/mobile/mesh/data/SessionStore.kt").readText()
        val create = source.substringAfter("suspend fun createSession(").substringBefore("suspend fun renameSession(")
        val added = source.substringAfter("\"api-session/added\" -> {").substringBefore("\"api-session/removed\"")
        assertTrue(create.contains("force = true"))
        assertTrue(create.contains("source = BaselineTriggerSource.SESSION_CREATED"))
        assertTrue(added.contains("force = true"))
        assertTrue(added.contains("source = BaselineTriggerSource.SESSION_ADDED_NOTIFICATION"))
        val scheduler = source.substringAfter("private fun triggerBaseline(").substringBefore("private suspend fun baseline()")
        assertTrue(scheduler.contains("baseline request queued source=${'$'}{source.label} force=${'$'}force workerRunning=true"))
    }
}
