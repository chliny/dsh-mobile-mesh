package dev.dsh.mobile.mesh.ui.screens.settings

import android.os.Bundle
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.dsh.mobile.mesh.connection.ConnectionTrace
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two independent instrumentation runs exercise a real process restart and the real log export.
 * The host supplies the same UUID as `traceMarker` to both runs, `traceStage=record` on the first,
 * and `traceStage=read` plus the first run's `traceSeedPid` and `traceSeedTime` on the second. Without these explicit
 * arguments the test is skipped, so ordinary device suites do not append diagnostic records.
 * It only appends one non-sensitive marker and never clears or removes the device's data.
 */
@RunWith(AndroidJUnit4::class)
class ConnectionTraceExportDeviceTest {
    @Test
    fun recoveryTraceSurvivesRestartAndIsIncludedInLogExport() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val stage = arguments.getString("traceStage")
        val marker = arguments.getString("traceMarker")
        assumeTrue("Requires traceStage=record/read and the same traceMarker UUID in both runs", stage != null && marker != null)
        require(stage == "record" || stage == "read") { "traceStage must be record or read" }
        val safeMarker = UUID.fromString(requireNotNull(marker)).toString()
        val context = instrumentation.targetContext.applicationContext
        assertTrue("Run only against the debug app", context.packageName.endsWith(".debug"))
        ConnectionTrace.start(context)

        if (stage == "record") {
            val recordedAt = System.currentTimeMillis()
            ConnectionTrace.record("device-export-check", "marker=$safeMarker")
            val exported = readAppLogs(context, AppLogTimeRange(recordedAt - 60_000, recordedAt + 60_000))
            assertTraceContains(exported, safeMarker)
            val outsideRange = readAppLogs(context, AppLogTimeRange(recordedAt - 120_000, recordedAt - 60_000))
            assertFalse("An export before this event must not contain the marker", outsideRange.contains("marker=$safeMarker"))

            // Wait for the actual asynchronous journal write before the host ends this process.
            // This is synchronization on device storage, not a substitute for the export assertion.
            val directory = File(context.noBackupFilesDir, "connection-trace")
            withTimeout(10_000) {
                while (directory.listFiles().orEmpty().none { file ->
                        file.isFile && runCatching { file.readText().contains("marker=$safeMarker") }.getOrDefault(false)
                    }) delay(50)
            }
            instrumentation.sendStatus(0, Bundle().apply {
                putString("traceSeedPid", Process.myPid().toString())
                putString("traceSeedTime", recordedAt.toString())
            })
        } else {
            val previousPid = arguments.getString("traceSeedPid")?.toIntOrNull()
            val recordedAt = arguments.getString("traceSeedTime")?.toLongOrNull()
            require(previousPid != null && recordedAt != null) { "traceSeedPid and traceSeedTime from the record run are required for the read run" }
            assertNotEquals("The read run must use a new app process", previousPid, Process.myPid())
            val exported = readAppLogs(context, AppLogTimeRange(recordedAt - 60_000, recordedAt + 60_000))
            // The marker must come from the journal section even if logcat still holds a copy.
            assertTraceContains(exported, safeMarker)
            val outsideRange = readAppLogs(context, AppLogTimeRange(recordedAt - 120_000, recordedAt - 60_000))
            assertFalse("An export before this event must not contain the marker", outsideRange.contains("marker=$safeMarker"))
        }
    }

    private fun assertTraceContains(exported: String, marker: String) {
        val journalSection = exported.substringAfter("# connection trace\n", missingDelimiterValue = "")
        assertTrue("The exported connection trace must contain this run's marker", journalSection.lineSequence().any {
            it.contains("device-export-check") && it.contains("marker=$marker")
        })
    }
}
