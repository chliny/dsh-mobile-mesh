package dev.dsh.mobile.mesh.connection

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.dsh.mobile.mesh.core.wire.HostGeneration
import dev.dsh.mobile.mesh.mockharness.MockHarness
import java.util.concurrent.CopyOnWriteArrayList
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
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
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

/** Exercises the real handover, retry scheduler and mux against a reachable mock host. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class MeshHandoverRecoveryTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun `background handover backs off failures but a newer network recovers promptly`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val shadowConnectivity = shadowOf(connectivity)
        val initialNetwork = ShadowNetwork.newInstance(ConnectivityManager.TYPE_WIFI)
        val initialInfo = ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true,
        )
        shadowConnectivity.addNetwork(initialNetwork, initialInfo)
        shadowConnectivity.setActiveNetworkInfo(initialInfo)
        shadowConnectivity.setDefaultNetworkActive(true)
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowConnectivity.setNetworkCapabilities(initialNetwork, capabilities)

        val dataScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefs = PreferenceDataStoreFactory.create(scope = dataScope) { files.newFile("handover.preferences_pb") }
        val hosts = HostsStore(prefs, context)
        hosts.setSetting { it.copy(keepConnectedInBackground = true) }
        val http = OkHttpClient()
        val harness = MockHarness()
        val port = harness.start()
        val zeroTier = Mockito.mock(ZeroTierConnector::class.java)
        val tailscale = Mockito.mock(TailscaleConnector::class.java)
        val handover = AtomicBoolean(false)
        val remainingFailures = AtomicInteger(4)
        val attempts = CopyOnWriteArrayList<Long>()
        val fourthAttemptStarted = CompletableDeferred<Unit>()
        val finishFourthAttempt = CountDownLatch(1)
        val fourthGateTimedOut = AtomicBoolean(false)
        fun relay(): MeshRelay {
            if (handover.get()) {
                attempts.add(System.nanoTime())
                if (remainingFailures.getAndDecrement() > 0) {
                    if (attempts.size == 4) {
                        fourthAttemptStarted.complete(Unit)
                        if (!finishFourthAttempt.await(8, TimeUnit.SECONDS)) fourthGateTimedOut.set(true)
                    }
                    throw MeshNotReady("network path temporarily not ready")
                }
            }
            return MeshRelay("127.0.0.1", port)
        }
        val matchConfig = HostConfig("match", "match", "127.0.0.1", port)
        Mockito.doAnswer { relay() }.`when`(zeroTier).start(Mockito.any(HostConfig::class.java) ?: matchConfig)
        Mockito.doAnswer { relay() }.`when`(zeroTier).renewRelay(Mockito.any(HostConfig::class.java) ?: matchConfig)

        val callbacksBefore = shadowConnectivity.networkCallbacks.toSet()
        val manager = ConnectionManager(
            context,
            HarnessClientFactory(http, HarnessSessionStore(prefs, http)),
            hosts,
            MeshTransportManager(zeroTier, tailscale),
            SshTunnelManager(context, Mockito.mock(SshSecretStore::class.java)),
        )
        val managerCallbacks = shadowConnectivity.networkCallbacks - callbacksBefore
        val callback = managerCallbacks.single()
        val published = Channel<HostGeneration>(Channel.UNLIMITED)
        val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val collector = collectorScope.launch(start = CoroutineStart.UNDISPATCHED) {
            manager.connectedGenerations.collect { published.send(it) }
        }
        try {
            manager.recoverForForeground()
            manager.connect(
                HostConfig(
                    id = "handover-host",
                    name = "handover-host",
                    host = "127.0.0.1",
                    port = port,
                    meshTransport = MeshTransport.ZERO_TIER,
                    lastProtocol = HarnessProtocol.PARENT_CATALOG,
                ),
            )
            await { manager.state.value.phase == ConnectionPhase.CONNECTED && manager.generation != null }
            val original = requireNotNull(manager.generation)
            assertSame(original, withTimeout(10_000) { published.receive() })
            await { shadowOf(context).getNextStartedService() != null }
            withTimeout(10_000) { while (hosts.activeConnectionId() != "handover-host") delay(20) }

            manager.onAppBackgrounded()
            handover.set(true)
            val replacementNetwork = ShadowNetwork.newInstance(ConnectivityManager.TYPE_MOBILE)
            val replacementInfo = ShadowNetworkInfo.newInstance(
                NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_MOBILE, 0, true, true,
            )
            shadowConnectivity.addNetwork(replacementNetwork, replacementInfo)
            shadowConnectivity.setActiveNetworkInfo(replacementInfo)
            shadowConnectivity.setNetworkCapabilities(replacementNetwork, capabilities)
            callback.onAvailable(requireNotNull(connectivity.activeNetwork))

            // The first three failures return immediately; the fourth pauses only after its
            // invocation has been observed. The preceding gaps are real retry delays for B, which
            // must grow with each consecutive failure of the same network event.
            withTimeout(20_000) { fourthAttemptStarted.await() }
            val observed = attempts.toList()
            val firstRetry = TimeUnit.NANOSECONDS.toMillis(observed[1] - observed[0])
            val secondRetry = TimeUnit.NANOSECONDS.toMillis(observed[2] - observed[1])
            val thirdRetry = TimeUnit.NANOSECONDS.toMillis(observed[3] - observed[2])
            // Lower bounds only: the scheduler may add delay on a loaded machine, never remove it.
            assertTrue("first retry keeps the fast delay: $firstRetry ms", firstRetry >= 800)
            assertTrue("second retry must be longer than the first: $secondRetry ms", secondRetry >= 1_800)
            assertTrue("third retry must keep growing: $thirdRetry ms", thirdRetry >= 3_800)

            // Android reports a different default network while B's last attempt is unwinding.
            // That newer route must not wait behind B's already-exhausted retry backoff.
            shadowConnectivity.setActiveNetworkInfo(initialInfo)
            shadowConnectivity.setNetworkCapabilities(initialNetwork, capabilities)
            callback.onAvailable(requireNotNull(connectivity.activeNetwork))
            val newNetworkReleasedAt = System.nanoTime()
            finishFourthAttempt.countDown()
            assertFalse("the held mesh attempt timed out before the new network event", fourthGateTimedOut.get())
            withTimeout(1_500) { while (attempts.size < 5) delay(20) }
            val afterNewNetwork = TimeUnit.NANOSECONDS.toMillis(attempts[4] - newNetworkReleasedAt)
            assertTrue("a new network must not wait for the old steady backoff: $afterNewNetwork ms", afterNewNetwork < 1_500)

            await { manager.state.value.phase == ConnectionPhase.CONNECTED && manager.connectedApi != null && manager.generation != null && manager.generation !== original }
            val replacement = requireNotNull(manager.generation)
            assertNotSame(original, replacement)
            assertSame("a recovered generation must reach session subscribers", replacement, withTimeout(10_000) { published.receive() })
            assertNotNull("the recovered host must be usable while retained in background", manager.connectedApi)
        } finally {
            finishFourthAttempt.countDown()
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
