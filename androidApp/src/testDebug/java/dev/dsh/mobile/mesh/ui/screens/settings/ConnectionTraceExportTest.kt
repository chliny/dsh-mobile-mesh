package dev.dsh.mobile.mesh.ui.screens.settings

import android.app.Application
import dev.dsh.mobile.mesh.connection.ConnectionTrace
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The durable recovery journal must be exported when its record falls inside the requested time
 * range, and must stay out of ranges that do not cover it.
 *
 * The real device test also restarted the process and shelled out to logcat; those parts cannot run
 * in CI, but the journal/range behaviour that made the export useful is observable on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ConnectionTraceExportTest {
    @Test
    fun recordedTraceAppearsInExportOnlyInsideItsTimeRange() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        ConnectionTrace.start(context)
        val marker = "marker=" + UUID.randomUUID()
        val recordedAt = System.currentTimeMillis()
        ConnectionTrace.record("jvm-export-check", marker)

        // Recording writes through a bounded background writer; wait for the journal to see it.
        withTimeout(10_000) {
            while (!ConnectionTrace.snapshot(recordedAt - 60_000, recordedAt + 60_000).contains(marker)) {
                delay(20)
            }
        }

        val exported = withConnectionTrace("", AppLogTimeRange(recordedAt - 60_000, recordedAt + 60_000))
        val traceSection = exported.substringAfter("# connection trace\n", missingDelimiterValue = "")
        assertTrue(
            "The exported connection trace must contain this run's marker",
            traceSection.lineSequence().any { it.contains("jvm-export-check") && it.contains(marker) },
        )

        val outsideRange = withConnectionTrace("", AppLogTimeRange(recordedAt - 120_000, recordedAt - 60_000))
        assertFalse("An export before this event must not contain the marker", outsideRange.contains(marker))
    }
}
