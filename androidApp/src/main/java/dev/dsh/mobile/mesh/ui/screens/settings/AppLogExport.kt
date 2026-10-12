package dev.dsh.mobile.mesh.ui.screens.settings

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
    val start = range.startEpochMs / 1_000.0
    val end = range.endEpochMs / 1_000.0
    val selected = logcatOutput.lineSequence()
        .filter { line ->
            val timestamp = line.trimStart().substringBefore(' ').toDoubleOrNull()
            timestamp != null && timestamp >= start && timestamp < end
        }
        .toList()
    if (selected.isEmpty()) return ""
    return selected.joinToString(separator = "\n") + if (logcatOutput.endsWith('\n')) "\n" else ""
}

/** Reads the device log buffers for this app UID, including entries from previous process IDs. */
internal fun readAppLogs(uid: Int, range: AppLogTimeRange): String {
    require(range.isValid()) { "The log time range must have a start before its end" }
    val process = ProcessBuilder(
        "logcat",
        "-d",
        "--uid=$uid",
        "-v",
        "epoch,uid",
    ).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    val exitCode = process.waitFor()
    check(exitCode == 0) { "logcat exited with status $exitCode: ${output.take(1000)}" }
    return filterAppLogsByTimeRange(output, range)
}
