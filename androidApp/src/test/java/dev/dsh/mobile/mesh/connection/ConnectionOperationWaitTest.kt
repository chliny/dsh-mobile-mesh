package dev.dsh.mobile.mesh.connection

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.dsh.mobile.mesh.core.wire.HostGeneration
import dev.dsh.mobile.mesh.mockharness.MockHarness
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

/** A real host replacement must wait for the preceding operation and report that wait. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ConnectionOperationWaitTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun `replacement waits for prior connection cleanup and records its own wait`() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val shadowConnectivity = shadowOf(connectivity)
        val network = ShadowNetwork.newInstance(ConnectivityManager.TYPE_WIFI)
        val info = ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true,
        )
        shadowConnectivity.addNetwork(network, info)
        shadowConnectivity.setActiveNetworkInfo(info)
        shadowConnectivity.setDefaultNetworkActive(true)
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowConnectivity.setNetworkCapabilities(network, capabilities)

        val dataScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefs = PreferenceDataStoreFactory.create(scope = dataScope) { files.newFile("operation-wait.preferences_pb") }
        val hosts = HostsStore(prefs, context)
        val http = OkHttpClient()
        val harness = MockHarness()
        val port = harness.start()
        val callbacksBefore = shadowConnectivity.networkCallbacks.toSet()
        val manager = ConnectionManager(
            context,
            HarnessClientFactory(http, HarnessSessionStore(prefs, http)),
            hosts,
            MeshTransportManager(Mockito.mock(ZeroTierConnector::class.java), Mockito.mock(TailscaleConnector::class.java)),
            SshTunnelManager(context, Mockito.mock(SshSecretStore::class.java)),
        )
        val managerCallbacks = shadowConnectivity.networkCallbacks - callbacksBefore
        val published = Channel<HostGeneration>(Channel.UNLIMITED)
        val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val collector = collectorScope.launch(start = CoroutineStart.UNDISPATCHED) {
            manager.connectedGenerations.collect { published.send(it) }
        }
        val firstStarted = CompletableDeferred<Unit>()
        val finishFirst = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val unique = UUID.randomUUID().toString()
        val firstHost = HostConfig("wait-a-$unique", "A", "127.0.0.1", port, lastProtocol = HarnessProtocol.PARENT_CATALOG)
        val secondHost = HostConfig("wait-b-$unique", "B", "127.0.0.1", port, lastProtocol = HarnessProtocol.PARENT_CATALOG)
        try {
            manager.recoverForForeground()
            manager.connect(firstHost) {
                firstStarted.complete(Unit)
                // A real cleanup boundary can outlive cancellation (for example native teardown).
                withContext(NonCancellable) { finishFirst.await() }
            }
            withTimeout(10_000) { firstStarted.await() }

            // Trace privacy uses a per-process salted pseudonym for host identifiers. Record one
            // unique marker through the production journal to identify B without inspecting its salt.
            ConnectionTrace.record("operation-wait-test-marker", "host=${secondHost.id} marker=$unique")
            val markerLine = traceLines().first { it.contains("operation-wait-test-marker") && it.contains("marker=$unique") }
            val hostHash = requireNotNull(Regex("\\bhost=([^\\s]+)").find(markerLine)?.groupValues?.get(1))
            val beforeReplacement = traceLines().toSet()

            manager.connect(secondHost) { secondStarted.complete(Unit) }
            val replacementLine = awaitTraceLine(beforeReplacement) {
                it.contains("ConnectionTrace: operation-replace ") && it.contains("host=$hostHash ")
            }
            val operationId = requireNotNull(Regex("\\boperationId=([^\\s]+)").find(replacementLine)?.groupValues?.get(1)) {
                "operation-replace must identify the operation that owns its wait records"
            }
            awaitTraceLine(beforeReplacement) {
                it.contains("ConnectionTrace: operation-wait ") && it.contains("operationId=$operationId ") &&
                    it.contains("phase=previous-operation result=start")
            }
            assertFalse("B must not start its transport callback while A is still unwinding", secondStarted.isCompleted)

            finishFirst.complete(Unit)
            withTimeout(10_000) { secondStarted.await() }
            withTimeout(10_000) {
                while (manager.state.value.phase != ConnectionPhase.CONNECTED ||
                    manager.state.value.host?.id != secondHost.id || manager.connectedApi == null || manager.generation == null
                ) delay(20)
            }
            val replacement = requireNotNull(manager.generation)
            assertSame("Only the user's replacement host must be published", replacement, withTimeout(10_000) { published.receive() })
            assertNotNull(manager.connectedApi)
            awaitTraceLine(beforeReplacement) {
                it.contains("ConnectionTrace: operation-wait ") && it.contains("operationId=$operationId ") &&
                    it.contains("phase=previous-operation result=completed")
            }
        } finally {
            finishFirst.complete(Unit)
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

    private fun traceLines(): List<String> = ConnectionTrace.snapshot(0, Long.MAX_VALUE).lineSequence().filter { it.isNotEmpty() }.toList()

    private suspend fun awaitTraceLine(baseline: Set<String>, predicate: (String) -> Boolean): String =
        withTimeout(10_000) {
            while (true) {
                val line = traceLines().firstOrNull { it !in baseline && predicate(it) }
                if (line != null) return@withTimeout line
                delay(20)
            }
            error("unreachable")
        }
}
