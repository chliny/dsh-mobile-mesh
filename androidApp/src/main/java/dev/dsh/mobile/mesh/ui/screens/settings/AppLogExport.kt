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

/** Export the selected logcat range, including durable history only when the user opted in. */
internal fun readAppLogs(context: Context, range: AppLogTimeRange): String {
    require(range.isValid()) { "The log time range must have a start before its end" }
    val archived = AppLogCapture.isEnabled(context)
    val archive = AppLogCapture.archive(context)
    val (history, lastLine) = if (archived) archive.snapshot(range) else "" to null
    val process = ProcessBuilder("logcat", "-d", "--uid=${context.applicationInfo.uid}", "-v", "epoch,uid")
        .redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    val exitCode = process.waitFor()
    check(exitCode == 0) { "logcat exited with status $exitCode" }
    if (!shouldExportArchivedLogs(archived)) return filterAppLogsByTimeRange(output, range)

    val resume = LogcatResume(lastLine)
    val recent = output.lineSequence().mapNotNull { line ->
        val time = logEpochMillis(line)
        if (resume.shouldAppend(line) && time != null && time >= range.startEpochMs && time < range.endEpochMs) line
        else null
    }.toList()
    return history + recent.joinToString("\n", postfix = if (recent.isNotEmpty()) "\n" else "")
}

/** Begin a persistent UID-scoped logcat stream as soon as the app process starts. */
internal object AppLogCapture {
    private const val FIRST_START_LINES = "100000"
    private const val PREFS = "app_log_settings"
    private const val KEY_PERSIST = "persist_logs_to_storage"
    @Volatile private var enabled = false
    @Volatile private var worker: Thread? = null
    @Volatile private var process: Process? = null
    @Volatile private var generation = 0L

    fun archive(context: Context): AppLogArchive = AppLogArchive(File(context.noBackupFilesDir, "app-log-history"))

    fun isEnabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_PERSIST, false)

    @Synchronized fun start(context: Context) {
        setEnabled(context, isEnabled(context))
    }

    @Synchronized fun setEnabled(context: Context, value: Boolean) {
        if (enabled == value && (!value || worker?.isAlive == true)) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PERSIST, value).apply()
        enabled = value
        val runGeneration = ++generation
        if (!value) {
            process?.destroy()
            process = null
            worker?.interrupt()
            worker = null
            return
        }
        if (worker?.isAlive == true) return
        val appContext = context.applicationContext
        val archive = archive(appContext)
        val uid = appContext.applicationInfo.uid
        worker = Thread({
            while (enabled && generation == runGeneration) {
                var stream: Process? = null
                try {
                    val resume = LogcatResume(archive.lastLine())
                    stream = ProcessBuilder(
                        "logcat", "--uid=$uid", "-v", "epoch,uid", "-T", resume.startTime() ?: FIRST_START_LINES,
                    ).redirectErrorStream(true).start()
                    if (generation == runGeneration) process = stream
                    stream.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line -> if (enabled && generation == runGeneration && resume.shouldAppend(line)) archive.append(line) }
                    }
                    stream.waitFor()
                } catch (_: Exception) {
                    // A logcat process may exit on buffer reset, disable, or device policy; retry only while enabled.
                } finally {
                    if (process === stream) process = null
                    stream?.destroy()
                }
                if (enabled && generation == runGeneration) try { Thread.sleep(1_000) } catch (_: InterruptedException) { break }
            }
        }, "app-log-archive").apply { isDaemon = true; start() }
    }
}

internal fun shouldExportArchivedLogs(persistLogsEnabled: Boolean): Boolean = persistLogsEnabled
