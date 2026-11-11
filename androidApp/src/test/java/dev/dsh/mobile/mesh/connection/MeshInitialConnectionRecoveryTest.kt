package dev.dsh.mobile.mesh.connection

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.dsh.mobile.mesh.mockharness.MockHarness
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertNotNull
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

/** A temporary mesh-not-ready event on a first connection must heal without another user tap. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class MeshInitialConnectionRecoveryTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun `initial mesh not ready retries and publishes a real harness generation`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val callbacksBefore = shadowOf(connectivity).networkCallbacks.toSet()
        val network = ShadowNetwork.newInstance(ConnectivityManager.TYPE_WIFI)
        val info = ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true,
        )
        shadowOf(connectivity).addNetwork(network, info)
        shadowOf(connectivity).setActiveNetworkInfo(info)
        shadowOf(connectivity).setDefaultNetworkActive(true)
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(connectivity).setNetworkCapabilities(network, capabilities)

        val dataScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefs = PreferenceDataStoreFactory.create(scope = dataScope) { files.newFile("mesh-recovery.preferences_pb") }
        val hosts = HostsStore(prefs, context)
        val http = OkHttpClient()
        val harness = MockHarness()
        val port = harness.start()
        val attempts = AtomicInteger()
        val zeroTier = Mockito.mock(ZeroTierConnector::class.java)
        val tailscale = Mockito.mock(TailscaleConnector::class.java)
        val manager = ConnectionManager(
            context,
            HarnessClientFactory(http, HarnessSessionStore(prefs, http)),
            hosts,
            MeshTransportManager(zeroTier, tailscale),
            SshTunnelManager(context, Mockito.mock(SshSecretStore::class.java)),
        )
        try {
            Mockito.doAnswer {
                if (attempts.incrementAndGet() <= 3) throw MeshNotReady("retained node is not ready yet")
                MeshRelay("127.0.0.1", port)
            }.`when`(zeroTier).start(Mockito.any(HostConfig::class.java) ?: HostConfig("match", "match", "127.0.0.1", port))
            manager.recoverForForeground()
            manager.connect(
                HostConfig(
                    id = "mesh-host",
                    name = "mesh-host",
                    host = "127.0.0.1",
                    port = port,
                    meshTransport = MeshTransport.ZERO_TIER,
                    lastProtocol = HarnessProtocol.PARENT_CATALOG,
                ),
            )

            withTimeout(20_000) {
                while (manager.state.value.phase != ConnectionPhase.CONNECTED || manager.generation == null) delay(20)
            }
            assertTrue("repeated transient failures must be retried until the host is reachable", attempts.get() >= 4)
            assertNotNull("the retry must expose the real host API", manager.connectedApi)
        } finally {
            manager.disconnect()
            shadowOf(connectivity).networkCallbacks
                .filter { it !in callbacksBefore }
                .forEach(connectivity::unregisterNetworkCallback)
            harness.stop()
            dataScope.cancel()
            http.dispatcher.executorService.shutdownNow()
            http.connectionPool.evictAll()
        }
    }
}
