package dev.dsh.mobile.mesh.ui.screens.settings

import android.content.Context
import java.io.File

/** A half-open interval of logcat event times, expressed as Unix epoch milliseconds. */
data class AppLogTimeRange(val startEpochMs: Long, val endEpochMs: Long) {
    fun isValid(): Boolean = startEpochMs < endEpochMs
}

/**
 * Select logcat records by epoch timestamp. UID filtering happens at the logcat source, not here,
 * so records from earlier PIDs of the same app are retained across process death/restart.
 */
internal fun filterAppLogsByTimeRange(
    logcatOutput: String,
    range: AppLogTimeRange,
): String {
    if (!range.isValid()) return ""
    val selected = logcatOutput.lineSequence()
        .filter { line ->
            val timestamp = logEpochMillis(line)
            timestamp != null && timestamp >= range.startEpochMs && timestamp < range.endEpochMs
        }
        .toList()
    if (selected.isEmpty()) return ""
    return selected.joinToString(separator = "\n") + if (logcatOutput.endsWith('\n')) "\n" else ""
}

/** Read durable history, then fill any gap at the tail from the current device buffer. */
internal fun readAppLogs(context: Context, range: AppLogTimeRange): String {
    require(range.isValid()) { "The log time range must have a start before its end" }
    val archive = AppLogCapture.archive(context)
    val (history, lastLine) = archive.snapshot(range)
    val process = ProcessBuilder("logcat", "-d", "--uid=${context.applicationInfo.uid}", "-v", "epoch,uid")
        .redirectErrorStream(true).start()
    val resume = LogcatResume(lastLine)
    val recent = buildString {
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val time = logEpochMillis(line)
                if (resume.shouldAppend(line) && time != null && time >= range.startEpochMs && time < range.endEpochMs) {
                    append(line).append('\n')
                }
            }
        }
    }
    val exitCode = process.waitFor()
    check(exitCode == 0) { "logcat exited with status $exitCode" }
    return history + recent
}

/** Begin a persistent UID-scoped logcat stream as soon as the app process starts. */
internal object AppLogCapture {
    private const val FIRST_START_LINES = "100000"
    @Volatile private var started = false

    fun archive(context: Context): AppLogArchive = AppLogArchive(File(context.noBackupFilesDir, "app-log-history"))

    @Synchronized fun start(context: Context) {
        if (started) return
        started = true
        val archive = archive(context)
        val uid = context.applicationInfo.uid
        Thread({
            while (true) {
                var process: Process? = null
                try {
                    val resume = LogcatResume(archive.lastLine())
                    process = ProcessBuilder(
                        "logcat", "--uid=$uid", "-v", "epoch,uid", "-T", resume.startTime() ?: FIRST_START_LINES,
                    ).redirectErrorStream(true).start()
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line -> if (resume.shouldAppend(line)) archive.append(line) }
                    }
                    process.waitFor()
                } catch (_: Exception) {
                    // A logcat process may exit on buffer reset or device policy; retry from disk.
                } finally {
                    process?.destroy()
                }
                try { Thread.sleep(1_000) } catch (_: InterruptedException) { return@Thread }
            }
        }, "app-log-archive").apply { isDaemon = true; start() }
    }
}
