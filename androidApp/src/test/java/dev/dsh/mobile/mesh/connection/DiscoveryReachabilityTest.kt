package dev.dsh.mobile.mesh.connection

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import dev.dsh.mobile.mesh.mockharness.MockHarness
import dev.dsh.mobile.mesh.ui.screens.connect.HostProbe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import dev.dsh.mobile.mesh.ui.screens.connect.hostProbeFromOutcome
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryReachabilityTest {
    @Test
    fun `anonymous successful probe retains reachability without home`() = runBlocking {
        val harness = MockHarness()
        val port = harness.start()
        try {
            val unusedSessions = object : DataStore<Preferences> {
                override val data: Flow<Preferences> = flowOf(emptyPreferences())
                override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                    error("anonymous probe must not read or write sessions")
            }
            val http = OkHttpClient()
            val discovery = DiscoveryEngine(HarnessClientFactory(http, HarnessSessionStore(unusedSessions, http)))
            val outcome = discovery.probeOutcome("127.0.0.1", port, ProbeTimeouts.Manual)
            assertTrue(outcome is ProbeOutcome.Reachable)
            assertNull((outcome as ProbeOutcome.Reachable).description)
            assertTrue(discoveredHostFromOutcome("127.0.0.1", port, outcome)!!.trusted)
        } finally {
            harness.stop()
        }
    }

    @Test
    fun `successful protocol probe is trusted even when home was not queried`() {
        val found = discoveredHostFromOutcome("192.168.1.2", 3080, ProbeOutcome.Reachable(null))!!
        assertTrue(found.trusted)
        assertNull(found.description)
        assertFalse(discoveredHostFromOutcome("192.168.1.3", 3080, ProbeOutcome.TrustFence)!!.trusted)
    }

    @Test
    fun `remembered host keeps reachable status without a directory caption`() {
        val status = hostProbeFromOutcome(ProbeOutcome.Reachable(null))
        assertTrue(status is HostProbe.Reachable)
        assertNull((status as HostProbe.Reachable).description)
    }
}
