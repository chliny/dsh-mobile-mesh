package dev.dsh.mobile.mesh.connection

import android.app.NotificationManager
import android.content.Intent
import android.view.KeyEvent
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import dev.dsh.mobile.mesh.MainActivity
import dev.dsh.mobile.mesh.ui.SessionStoreEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the actual app-scoped carrier without clearing the phone's remembered hosts. */
@RunWith(AndroidJUnit4::class)
class BackgroundRetentionDeviceTest {
    @Test
    fun quickServiceStartAndStopDoesNotTripAndroidForegroundDeadline() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext.applicationContext
        val manager = EntryPointAccessors.fromApplication(context, SessionStoreEntryPoint::class.java).connectionManager()
        instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        // Exercise the real service scheduling path without persisting any preference change.
        val start = ConnectionManager::class.java.getDeclaredMethod("startService").apply { isAccessible = true }
        val stop = ConnectionManager::class.java.getDeclaredMethod("stopService").apply { isAccessible = true }
        start.invoke(manager)
        stop.invoke(manager)
        // Android raises ForegroundServiceDidNotStartInTimeException after this deadline if a
        // stop outruns startForeground(); remaining alive is the device-level invariant.
        delay(7_000)
        val notifications = context.getSystemService(NotificationManager::class.java)
        assertTrue(notifications.activeNotifications.none { it.id == 10 })
    }

    @Test
    fun disablingRetentionAfterBackgroundingSuspendsCarrierAndKeepsResumeTarget() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext.applicationContext
        val entry = EntryPointAccessors.fromApplication(context, SessionStoreEntryPoint::class.java)
        val hosts = entry.hostsStore()
        val manager = entry.connectionManager()
        val originalRetention = hosts.settingsOnce().keepConnectedInBackground
        try {
            val activity = instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
            withTimeout(60_000) {
                while (manager.state.value.phase != ConnectionPhase.CONNECTED || manager.generation == null) delay(100)
            }
            hosts.setSetting { it.copy(keepConnectedInBackground = true) }
            // Do not press Home until startForeground() has actually posted the connection
            // notification; racing its startup against stopService() can crash Android itself.
            val notifications = context.getSystemService(NotificationManager::class.java)
            withTimeout(15_000) {
                while (notifications.activeNotifications.none { it.id == 10 }) delay(100)
            }
            assertNotNull(manager.generation)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_HOME)
            withTimeout(10_000) {
                while (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) delay(100)
            }
            // Retention is enabled at the actual background transition.
            assertNotNull(manager.generation)
            hosts.setSetting { it.copy(keepConnectedInBackground = false) }
            withTimeout(15_000) {
                while (manager.generation != null || manager.state.value.host == null) delay(100)
            }
            assertNull(manager.generation)
            assertTrue(manager.state.value.host != null)
        } finally {
            hosts.setSetting { it.copy(keepConnectedInBackground = originalRetention) }
            manager.recoverForForeground(forceCheck = true)
        }
    }
}
