package dev.dsh.mobile.mesh.ui.screens.settings

import java.io.File
import java.util.Locale

/** App-private, size-bounded logcat archive. The files survive process death, not app-data removal. */
internal class AppLogArchive(
    private val directory: File,
    private val segmentBytes: Long = 4L * 1024 * 1024,
    private val maxSegments: Int = 16,
) {
    init {
        require(segmentBytes > 0 && maxSegments > 0)
    }

    private fun segment(index: Int) = File(directory, "app-log-$index.txt")

    @Synchronized fun lastLine(): String? = segments().asReversed().firstNotNullOfOrNull { file ->
        file.useLines { lines -> lines.lastOrNull { logEpochMillis(it) != null } }
    }

    @Synchronized fun append(line: String) {
        if (logEpochMillis(line) == null) return
        directory.mkdirs()
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        if (segment(0).length() + bytes.size > segmentBytes) rotate()
        // A single unbuffered write keeps completed records available if Android kills the process.
        segment(0).outputStreamAppend().use { it.write(bytes) }
    }

    @Synchronized fun snapshot(range: AppLogTimeRange): Pair<String, String?> = read(range) to lastLine()

    @Synchronized fun read(range: AppLogTimeRange): String = buildString {
        if (!range.isValid()) return@buildString
        for (file in segments()) file.forEachLine { line ->
            val time = logEpochMillis(line)
            if (time != null && time >= range.startEpochMs && time < range.endEpochMs) {
                append(line).append('\n')
            }
        }
    }

    private fun segments(): List<File> = (maxSegments - 1 downTo 0).map(::segment).filter { it.isFile }

    private fun rotate() {
        for (index in maxSegments - 1 downTo 1) {
            val older = segment(index - 1)
            if (older.isFile) {
                val destination = segment(index)
                if (destination.exists() && !destination.delete()) error("Cannot remove old log segment")
                if (!older.renameTo(destination)) error("Cannot rotate log segment")
            }
        }
    }

    private fun File.outputStreamAppend() = java.io.FileOutputStream(this, true)
}

internal fun logEpochMillis(line: String): Long? {
    val token = line.trimStart().substringBefore(' ')
    val seconds = token.substringBefore('.').toLongOrNull() ?: return null
    val fraction = token.substringAfter('.', "")
    if (!fraction.all(Char::isDigit)) return null
    val millis = fraction.take(3).padEnd(3, '0').toLongOrNull() ?: return null
    return seconds * 1_000 + millis
}

/** Resume inclusively and discard the already-persisted prefix, including equal-time duplicates. */
internal class LogcatResume(private val lastLine: String?) {
    private val lastTime = lastLine?.let(::logEpochMillis)
    private var reachedLastLine = false

    fun shouldAppend(line: String): Boolean {
        val time = logEpochMillis(line) ?: return false
        if (lastTime == null) return true
        if (time < lastTime) return false
        if (time > lastTime) return true
        if (!reachedLastLine) {
            if (line == lastLine) reachedLastLine = true
            return false
        }
        return true
    }

    fun startTime(): String? = lastTime?.let { String.format(Locale.US, "%.3f", it / 1_000.0) }
}
