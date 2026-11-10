package dev.dsh.mobile.mesh.connection

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import dev.dsh.mobile.mesh.core.wire.HostGeneration
import dev.dsh.mobile.mesh.mockharness.MockHarness
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo

/** Exercises the real manager and loop over HTTP/WebSocket, not a simulated connection policy. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ConnectionManagerBackgroundRecoveryTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun `retained connection restores a replaced generation before returning to foreground`() = runBlocking {
        withConnectedHarness { manager, harness, published, probes, original ->
            manager.onAppBackgrounded()
            harness.closeMuxSockets()
            await { manager.generation != null && manager.generation !== original }
            val replacement = requireNotNull(manager.generation)
            assertNotSame(original, replacement)
            await { manager.state.value.phase == ConnectionPhase.CONNECTED && manager.connectedApi != null }
            assertSame("The replacement must be published for session streams", replacement, withTimeout(10_000) { published.receive() })

            val beforeResume = probes.get()
            manager.recoverForForeground(forceCheck = true)
            await { probes.get() > beforeResume && manager.state.value.phase == ConnectionPhase.CONNECTED && manager.connectedApi != null }
            assertSame("A healthy retained generation must not be replaced on foreground", replacement, manager.generation)
            assertTrue(!manager.state.value.foregroundCheckPending && !manager.state.value.recoveryOverlayVisible)
        }
    }

    @Test
    fun `foreground verification fences the retained connection until the host responds`() = runBlocking {
        val probeStarted = CompletableDeferred<Unit>()
        val finishProbe = CompletableDeferred<Unit>()
        try {
            withConnectedHarness(onProbe = {
                probeStarted.complete(Unit)
                finishProbe.await()
            }, onBeforeClose = { finishProbe.complete(Unit) }) { manager, _, _, _, original ->
                manager.onAppBackgrounded()
                assertNotNull("Background retention must not fence a healthy carrier", manager.connectedApi)

                manager.recoverForForeground(forceCheck = true)
                withTimeout(5_000) { probeStarted.await() }
                assertTrue("Foreground recovery must block input while verification is pending", manager.state.value.foregroundCheckPending)
                assertNull("The live API must not be exposed while verification is pending", manager.connectedApi)
                assertSame(original, manager.generation)

                finishProbe.complete(Unit)
                await { manager.state.value.phase == ConnectionPhase.CONNECTED && manager.connectedApi != null }
                assertSame("A successful probe must keep the live generation", original, manager.generation)
                assertTrue(!manager.state.value.foregroundCheckPending && !manager.state.value.recoveryOverlayVisible)
            }
        } finally {
            finishProbe.complete(Unit)
        }
    }

    @Test
    fun `an old foreground probe cannot strand the replacement generation`() = runBlocking {
        val oldProbeStarted = CompletableDeferred<Unit>()
        val finishOldProbe = CompletableDeferred<Unit>()
        try {
            withConnectedHarness(onProbe = { probeNumber ->
                if (probeNumber == 1) {
                    oldProbeStarted.complete(Unit)
                    finishOldProbe.await()
                }
            }, onBeforeClose = { finishOldProbe.complete(Unit) }) { manager, harness, published, _, original ->
                manager.onAppBackgrounded()
                manager.recoverForForeground(forceCheck = true)
                withTimeout(5_000) { oldProbeStarted.await() }

                harness.closeMuxSockets()
                // The foreground probe times out after five seconds; observe the replacement
                // before that timeout rather than accidentally testing an ordinary retry.
                withTimeout(3_000) { await { manager.generation != null && manager.generation !== original } }
                val replacement = requireNotNull(manager.generation)
                finishOldProbe.complete(Unit)

                await { manager.state.value.phase == ConnectionPhase.CONNECTED && manager.connectedApi != null }
                assertSame("The stale probe must not retire the replacement", replacement, manager.generation)
                assertSame("The replacement must be published without another lifecycle transition", replacement, withTimeout(10_000) { published.receive() })
                assertTrue(!manager.state.value.foregroundCheckPending && !manager.state.value.recoveryOverlayVisible)
            }
        } finally {
            finishOldProbe.complete(Unit)
        }
    }

    @Test
    fun `backgrounding during replacement probe publishes the ready generation`() = runBlocking {
        val oldProbeStarted = CompletableDeferred<Unit>()
        val replacementProbeStarted = CompletableDeferred<Unit>()
        val finishOldProbe = CompletableDeferred<Unit>()
        val finishReplacementProbe = CompletableDeferred<Unit>()
        withConnectedHarness(
            onProbe = { probeNumber ->
                when (probeNumber) {
                    1 -> {
                        oldProbeStarted.complete(Unit)
                        finishOldProbe.await()
                    }
                    2 -> {
                        replacementProbeStarted.complete(Unit)
                        finishReplacementProbe.await()
                    }
                }
            },
            onBeforeClose = {
                finishOldProbe.complete(Unit)
                finishReplacementProbe.complete(Unit)
            },
        ) { manager, harness, published, _, original ->
            manager.onAppBackgrounded()
            manager.recoverForForeground(forceCheck = true)
            withTimeout(5_000) { oldProbeStarted.await() }

            harness.closeMuxSockets()
            withTimeout(3_000) { await { manager.generation != null && manager.generation !== original } }
            val replacement = requireNotNull(manager.generation)
            // B's probe is really in flight, rather than merely assuming a ready callback occurred.
            withTimeout(3_000) { replacementProbeStarted.await() }
            manager.onAppBackgrounded()

            await { manager.generation === replacement && manager.state.value.phase == ConnectionPhase.CONNECTED && manager.connectedApi != null }
            assertSame("The ready replacement must be published in the background", replacement, withTimeout(10_000) { published.receive() })

            // Release the remote handlers before fixture teardown; the observable invariant of
            // this test is publication and availability while still backgrounded.
            finishOldProbe.complete(Unit)
            finishReplacementProbe.complete(Unit)
            assertSame(replacement, manager.generation)
            assertNotNull(manager.connectedApi)
        }
    }

    @Test
    fun `slow recovery warnings stop in background and restart their timer on foreground`() = runBlocking {
        // Use a distinct network identity so unrelated managers or older singleton records cannot
        // satisfy the assertions. Robolectric maps the active NetworkInfo type to this netId.
        val networkId = (System.nanoTime().toInt() and 0x3fffffff) + 10_000
        withConnectedHarness(networkId = networkId) { manager, harness, _, _, _ ->
            val connectivity = RuntimeEnvironment.getApplication().getSystemService(ConnectivityManager::class.java)
            val activeNetwork = requireNotNull(connectivity.activeNetwork).toString()
            val rangeStart = System.currentTimeMillis() - 1_000
            fun slowEvents(): Set<String> = ConnectionTrace.snapshot(rangeStart, Long.MAX_VALUE)
                .lineSequence()
                .filter { it.contains("ConnectionTrace: recovery-slow ") && it.contains("network=$activeNetwork ") }
                .toSet()

            val beforeIncident = slowEvents()
            manager.onAppBackgrounded()
            harness.stop() // A real unreachable host keeps recovery blocked without a test-only hook.
            manager.recoverForForeground(forceCheck = true)
            withTimeout(25_000) {
                while (slowEvents().subtract(beforeIncident).isEmpty()) delay(100)
            }

            manager.onAppBackgrounded()
            val backgroundBaseline = slowEvents()
            // The real watchdog repeats after 30s; it must stay quiet for longer than that while
            // retention keeps trying in the background. No virtual clock or internal API is used.
            delay(32_000)
            assertTrue("background recovery must not emit recurring slow warnings", slowEvents().subtract(backgroundBaseline).isEmpty())

            val beforeResume = slowEvents()
            val resumedAt = System.nanoTime()
            manager.recoverForForeground(forceCheck = true)
            withTimeout(25_000) {
                while (slowEvents().subtract(beforeResume).isEmpty()) delay(100)
            }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - resumedAt)
            assertTrue("a new foreground warning must start a fresh ten-second timer", elapsedMs >= 9_000)
        }
    }

    /** Owns an isolated preferences store and real mock host for each business-flow test. */
    private suspend fun withConnectedHarness(
        onProbe: suspend (Int) -> Unit = {},
        networkId: Int = ConnectivityManager.TYPE_WIFI,
        onStoreReady: (DataStore<Preferences>) -> Unit = {},
        onBeforeClose: () -> Unit = {},
        block: suspend (ConnectionManager, MockHarness, Channel<HostGeneration>, AtomicInteger, HostGeneration) -> Unit,
    ) {
        val context = RuntimeEnvironment.getApplication()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val shadowConnectivity = shadowOf(connectivity)
        val network = ShadowNetwork.newInstance(networkId)
        // Robolectric resolves activeNetwork by NetworkInfo.type; both must use the same ID.
        val networkInfo = ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED, networkId, 0, true, true,
        )
        shadowConnectivity.addNetwork(network, networkInfo)
        shadowConnectivity.setActiveNetworkInfo(networkInfo)
        shadowConnectivity.setDefaultNetworkActive(true)
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowConnectivity.setNetworkCapabilities(network, capabilities)

        val dataScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefs = PreferenceDataStoreFactory.create(scope = dataScope) { files.newFile("settings.preferences_pb") }
        val hosts = HostsStore(prefs, context)
        hosts.setSetting { it.copy(keepConnectedInBackground = true) }
        onStoreReady(prefs)
        val http = OkHttpClient()
        val clientFactory = HarnessClientFactory(http, HarnessSessionStore(prefs, http))
        val mesh = MeshTransportManager(
            Mockito.mock(ZeroTierConnector::class.java), Mockito.mock(TailscaleConnector::class.java),
        )
        val ssh = SshTunnelManager(context, Mockito.mock(SshSecretStore::class.java))
        val callbacksBefore = shadowConnectivity.networkCallbacks.toSet()
        val manager = ConnectionManager(context, clientFactory, hosts, mesh, ssh)
        val managerCallbacks = shadowConnectivity.networkCallbacks - callbacksBefore
        val harness = MockHarness()
        val probes = AtomicInteger()
        harness.sessionHistory("settings/describe") {
            onProbe(probes.incrementAndGet())
            JsonNull
        }
        val port = harness.start()
        val published = Channel<HostGeneration>(Channel.UNLIMITED)
        val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val collector = collectorScope.launch(start = CoroutineStart.UNDISPATCHED) {
            manager.connectedGenerations.collect { published.send(it) }
        }
        try {
            manager.recoverForForeground()
            manager.connect(HostConfig("mock", "mock", "127.0.0.1", port, lastProtocol = HarnessProtocol.PARENT_CATALOG)) { }
            await { manager.state.value.phase == ConnectionPhase.CONNECTED && manager.generation != null }
            val original = requireNotNull(manager.generation)
            assertSame(original, withTimeout(10_000) { published.receive() })
            // Do not background until retention is enabled and the initial connection is persisted.
            await { shadowOf(context).getNextStartedService() != null }
            withTimeout(10_000) { while (hosts.activeConnectionId() != "mock") delay(20) }
            block(manager, harness, published, probes, original)
        } finally {
            onBeforeClose()
            collector.cancel()
            collectorScope.cancel()
            published.close()
            manager.disconnect()
            managerCallbacks.forEach { connectivity.unregisterNetworkCallback(it) }
            harness.stop()
            dataScope.cancel()
            http.dispatcher.executorService.shutdownNow()
            http.connectionPool.evictAll()
        }
    }

    private suspend fun await(condition: () -> Boolean) {
        withTimeout(10_000) { while (!condition()) delay(20) }
    }
}
