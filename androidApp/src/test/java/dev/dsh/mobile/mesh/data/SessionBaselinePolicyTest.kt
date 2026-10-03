package dev.dsh.mobile.mesh.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionBaselinePolicyTest {
    @Test
    fun `same host session baseline is reusable`() {
        assertFalse(shouldRefreshForConnectedGeneration("host-a", "host-a"))
    }

    @Test
    fun `new host generation refreshes session baseline`() {
        assertTrue(shouldRefreshForConnectedGeneration("host-b", "host-a"))
    }

    @Test
    fun `unknown host does not trigger a baseline`() {
        assertFalse(shouldRefreshForConnectedGeneration(null, "host-a"))
    }

    @Test
    fun `generation baseline reset precedes worker dispatch and ignores repeat publication`() {
        assertTrue(shouldResetSessionBaselineForGeneration("next", "old"))
        assertFalse(shouldResetSessionBaselineForGeneration("next", "next"))
        val source = java.io.File("src/main/java/dev/dsh/mobile/mesh/data/SessionStore.kt").readText()
        val collector = source.substringAfter("connectionManager.connectedGenerations.collect { generation ->")
            .substringBefore("if (connectionManager.supportsPermissionPresetsCatalog)")
        assertTrue(collector.indexOf("_sessionsCurrentGenerationId.value = generation.clientId") < collector.indexOf("triggerBaseline(generation.clientId, source = BaselineTriggerSource.CONNECTED_GENERATION)"))
    }

    @Test
    fun `same-generation fallback does not force a duplicate authoritative list read`() {
        assertFalse(shouldQueueBaselineForRequest("generation-a", "generation-a", force = false))
        assertTrue(shouldQueueBaselineForRequest("generation-b", "generation-a", force = false))
        assertTrue(shouldQueueBaselineForRequest("generation-a", "generation-a", force = true))
        val source = java.io.File("src/main/java/dev/dsh/mobile/mesh/data/SessionStore.kt").readText()
        val stateCollector = source.substringAfter("connectionManager.state.collect { state ->")
            .substringBefore("if (state.phase == ConnectionPhase.RECONNECTING")
        assertFalse(stateCollector.contains("force = true"))
    }

    @Test
    fun `baseline trigger log sources are fixed allowlisted labels`() {
        val source = java.io.File("src/main/java/dev/dsh/mobile/mesh/data/SessionStore.kt").readText()
        val triggerEnum = source.substringAfter("private enum class BaselineTriggerSource(")
            .substringBefore("internal fun shouldResetSessionBaselineForGeneration(")
        listOf("store-initialized", "connected-generation", "connection-state-fallback", "session-added-notification", "session-created")
            .forEach { assertTrue(triggerEnum.contains("\"$it\"")) }
        assertTrue(source.contains("source=${'$'}{source.label}"))
        assertFalse(source.contains("source=${'$'}{generationClientId}"))
    }

    @Test
    fun `late old-host result cannot replace current host list`() {
        assertFalse(isCurrentHostResult("generation-a", "generation-b", "host-a", "host-b"))
        assertTrue(isCurrentHostResult("generation-b", "generation-b", "host-b", "host-b"))
        assertFalse(isCurrentHostResult("generation-a", "generation-b", "host-b", "host-b"))
        // The same generation guard must apply to both successful and failed RPC responses.
        val source = java.io.File("src/main/java/dev/dsh/mobile/mesh/data/SessionStore.kt").readText()
        val resultHandling = source.substringAfter("when (val r = api.sessionList(null)) {")
            .substringBefore("/**\n     * Apply one workspace mutation")
        val success = resultHandling.substringAfter("is RpcResult.Ok -> {").substringBefore("is RpcResult.Err -> {")
        val failure = resultHandling.substringAfter("is RpcResult.Err -> {")
        assertTrue(success.indexOf("if (!generationIsCurrent())") < success.indexOf("sessionListBaselineCount ="))
        assertTrue(failure.indexOf("if (!generationIsCurrent())") < failure.indexOf("sessionListBaselineState ="))
        assertFalse(resultHandling.contains("expectedGenerationId.take(8)"))
        assertFalse(source.substringAfter("private fun triggerBaseline(").substringBefore("private suspend fun baseline()")
            .contains("generationClientId?.take(8)"))
    }
}
