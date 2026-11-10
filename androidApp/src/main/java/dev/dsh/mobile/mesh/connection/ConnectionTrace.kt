package dev.dsh.mobile.mesh.connection

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A bounded, app-private recovery journal. Recording never waits for file I/O: callbacks append to
 * an in-memory ring and enqueue a bounded write. Export runs on an IO dispatcher and merges both
 * persisted segments with memory, including records that have not yet reached the writer.
 */
internal object ConnectionTrace {
    private const val TAG = "ConnectionTrace"
    private const val DIRECTORY = "connection-trace"
    private const val MAX_PENDING_WRITES = 4_096
    private const val MAX_DETAIL_CHARS = 1_024

    private val journal = ConnectionTraceJournal()
    private val stateLock = Any()
    private val initialized = CountDownLatch(1)
    private val warned = AtomicBoolean(false)
    private val processId = UUID.randomUUID().toString()
    // Not exported: lets one process correlate a host/generation without storing its real value.
    private val identitySalt = UUID.randomUUID().toString().toByteArray(Charsets.UTF_8)
    private val eventPattern = Regex("[^A-Za-z0-9_.-]")
    private val failureTypePattern = Regex("\\bfailure=([A-Za-z0-9_.]+)")
    private val identifierPattern = Regex("(?i)\\b(host|clientId|generation|lastPublished|nodeId|target)=\\S+")
    private val credentialPattern = Regex("(?i)\\b(authorization|cookie|password|token|secret|apiKey)=\\S+")
    private val urlPattern = Regex("(?i)\\b(?:https?|wss?)://\\S+")
    private var nextSequence = 0L
    // Includes queued and failed writes, so an export never drops a still-queued record merely
    // because it has fallen out of the smaller recent-record ring.
    private val pending = LinkedHashMap<Long, String>()
    private var started = false
    @Volatile private var directory: File? = null

    private val writer = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(MAX_PENDING_WRITES),
        { runnable -> Thread(runnable, "connection-trace").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    /** Queue initialization rather than reading or creating files on the application main thread. */
    fun start(context: Context) {
        synchronized(stateLock) {
            if (started) return
            started = true
            // Hilt may create ConnectionManager before Application.onCreate. Preserve and persist
            // records produced during injection rather than clearing them while loading history.
            val beforeStart = pending.entries.map { it.key to it.value }
            try {
                writer.execute {
                    try {
                        val folder = File(context.noBackupFilesDir, DIRECTORY)
                        directory = folder
                        beforeStart.forEach { (sequence, line) -> persistSafely(folder, sequence, line) }
                    } catch (error: Exception) {
                        warnOnce(error)
                    } finally {
                        initialized.countDown()
                    }
                }
            } catch (error: Exception) {
                initialized.countDown()
                warnOnce(error)
            }
        }
    }

    fun record(event: String, detail: String = "") {
        synchronized(stateLock) {
            // A per-process sequence disambiguates identical events in the same millisecond when
            // reconciling the on-disk history with memory that may still be waiting to be written.
            val safeEvent = event.replace(eventPattern, "_").take(96)
            val safeDetail = sanitizeDetail(event, detail)
            val sequence = nextSequence++
            val line = journal.append(safeEvent, "$safeDetail traceSeq=$processId:$sequence".trim())
            pending[sequence] = line
            if (pending.size > MAX_PENDING_WRITES) {
                pending.remove(pending.keys.first())
                warnOnce(IOException("Trace pending buffer full"))
            }
            if (!started) return
            try {
                writer.execute { directory?.let { persistSafely(it, sequence, line) } }
            } catch (error: Exception) {
                // A full or unavailable writer must never block a network/lifecycle callback. The
                // bounded in-memory ring still contains the newest records for an immediate export.
                warnOnce(error)
            }
        }
    }

    /**
     * Export is called from Dispatchers.IO. Wait briefly for startup initialization, then merge the
     * complete readable disk history with current in-memory records, deduplicating queued writes.
     * When storage is unavailable, retain the in-memory diagnostics instead of failing the export.
     */
    fun snapshot(startEpochMs: Long, endEpochMs: Long): String {
        if (startEpochMs >= endEpochMs) return ""
        val shouldWait = synchronized(stateLock) { started }
        if (shouldWait) {
            try {
                if (!initialized.await(5, TimeUnit.SECONDS)) warnOnce(IOException("Trace initialization timed out"))
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                warnOnce(error)
                return journal.snapshot(startEpochMs, endEpochMs)
            }
        }
        val pendingSnapshot = synchronized(stateLock) { pending.values.toList() }
        return try {
            journal.snapshotWithDisk(directory, startEpochMs, endEpochMs, pendingSnapshot)
        } catch (error: Exception) {
            warnOnce(error)
            journal.snapshotWithDisk(null, startEpochMs, endEpochMs, pendingSnapshot)
        }
    }

    private fun persistSafely(folder: File, sequence: Long, line: String) {
        try {
            journal.persist(folder, line)
            synchronized(stateLock) { pending.remove(sequence) }
        } catch (error: Exception) {
            // Keep the bounded pending entry available to export even if storage is unavailable.
            warnOnce(error)
        }
    }

    private fun warnOnce(error: Exception) {
        if (warned.compareAndSet(false, true)) {
            // Do not log exception messages or paths: they can contain host/account information.
            Log.w(TAG, "Recovery journal storage unavailable (${error.javaClass.simpleName}); keeping recent entries in memory")
        }
    }

    /** Do not persist arbitrary server/exception messages, identifiers, or endpoint addresses. */
    private fun sanitizeDetail(event: String, detail: String): String {
        var safe = detail.take(MAX_DETAIL_CHARS).map { if (it.isISOControl()) ' ' else it }.joinToString("")
        if (event == "generation-failed" && " failure=" in safe) {
            val failureType = failureTypePattern.find(safe)?.groupValues?.get(1) ?: "unknown"
            val before = safe.substringBefore(" failure=")
            // The trailing carrierCause is generated locally; the discarded failure message can
            // be supplied by the remote host and must not be able to inject another field.
            val after = safe.substringAfterLast(" carrierCause=", "")
            safe = "$before failureType=$failureType" + if (after.isNotEmpty()) " carrierCause=$after" else ""
        }
        // Exception messages can include URLs, credentials, paths and server-provided content.
        safe = safe.substringBefore(" error=")
        safe = identifierPattern.replace(safe) { match ->
            val value = match.value.substringAfter('=')
            val pseudonym = if (value == "null" || value == "none") value else {
                val digest = MessageDigest.getInstance("SHA-256")
                digest.update(identitySalt)
                digest.digest(value.toByteArray(Charsets.UTF_8)).take(8)
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            }
            "${match.groupValues[1]}=$pseudonym"
        }
        safe = credentialPattern.replace(safe, "$1=<redacted>")
        safe = urlPattern.replace(safe, "<url-redacted>")
        return safe
    }
}

/** Bounded in-memory fallback and two bounded on-disk segments. */
internal class ConnectionTraceJournal(
    private val maxEntries: Int = 2_000,
    private val segmentBytes: Long = 256L * 1024,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(val epochMs: Long, val line: String)
    private val lock = Any()
    private val entries = ArrayDeque<Entry>()

    init {
        require(maxEntries > 0 && segmentBytes > 0)
    }

    fun append(event: String, detail: String): String {
        val epochMs = now()
        val line = formatLine(epochMs, event, detail)
        synchronized(lock) {
            entries.addLast(Entry(epochMs, line))
            while (entries.size > maxEntries) entries.removeFirst()
        }
        return line
    }

    fun snapshot(startEpochMs: Long, endEpochMs: Long): String = synchronized(lock) {
        entries.asSequence()
            .filter { it.epochMs >= startEpochMs && it.epochMs < endEpochMs }
            .joinToString(separator = "", postfix = "") { it.line + "\n" }
    }

    /** Read both segments under the writer lock, then merge entries not yet persisted. */
    @Synchronized
    fun snapshotWithDisk(directory: File?, startEpochMs: Long, endEpochMs: Long, pendingLines: List<String>): String {
        val disk = (directory?.let(::readSegments) ?: emptyList()).filter {
            // Only records written by this sanitized format are exported. A segment from a build
            // that predates it can contain raw host ids and remote error text, and its free text
            // could even forge a record marker, so it is not given the benefit of parsing.
            RECORD_TAIL.containsMatchIn(it) &&
                epochMillisOf(it).let { time -> time != null && time >= startEpochMs && time < endEpochMs }
        }
        val memory = (pendingLines + snapshot(startEpochMs, endEpochMs).lineSequence().toList())
            .filter { line ->
                val time = epochMillisOf(line)
                time != null && time >= startEpochMs && time < endEpochMs
            }.distinct()
        val remaining = disk.groupingBy { it }.eachCount().toMutableMap()
        val pending = memory.filter { line ->
            val count = remaining[line] ?: 0
            if (count > 0) {
                remaining[line] = count - 1
                false
            } else true
        }
        // Record IDs are unique, so the merge preserves repeated identical events without double
        // exporting a line that is already on disk. Sort by timestamp for a single usable timeline.
        return (disk + pending).sortedBy { epochMillisOf(it) ?: Long.MAX_VALUE }
            .joinToString(separator = "", postfix = "") { "$it\n" }
    }

    @Synchronized
    fun persist(directory: File, line: String) {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Cannot create trace directory")
        }
        val current = segment(directory, 0)
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        if (bytes.size.toLong() > segmentBytes) throw IOException("Trace record exceeds segment size")
        if (current.length() + bytes.size > segmentBytes) {
            // Replace the older segment atomically. A failed move leaves the existing files alone
            // and aborts this write; appending after a rotation failure would grow without bound.
            Files.move(
                current.toPath(), segment(directory, 1).toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
        }
        current.appendBytes(bytes)
    }

    private fun readSegments(directory: File): List<String> = (1 downTo 0).flatMap { index ->
        try {
            val file = segment(directory, index)
            if (!file.isFile) emptyList() else {
                // Legacy versions could grow without bound after a failed rotation. Do not allocate
                // unbounded memory reading a damaged or unexpectedly large diagnostic file.
                if (file.length() > segmentBytes * 2) throw IOException("Trace segment exceeds safety limit")
                file.useLines { it.toList() }
            }
        } catch (error: Exception) {
            // One damaged segment must not hide another readable segment or the live memory ring.
            // Do not log paths or exception text: those may contain private host information.
            runCatching { Log.w("ConnectionTrace", "Skipping unreadable trace segment ($index; ${error.javaClass.simpleName})") }
            emptyList()
        }
    }

    /** Every record written by this build ends with its per-process sequence marker. */
    private val RECORD_TAIL = Regex(" traceSeq=[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}:\\d+$")

    private fun segment(directory: File, index: Int) =
        File(directory, if (index == 0) "trace-current.log" else "trace-previous.log")

    private fun formatLine(epochMs: Long, event: String, detail: String): String {
        val seconds = epochMs / 1_000
        val millis = (epochMs % 1_000).toString().padStart(3, '0')
        val suffix = detail.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
        return String.format(Locale.US, "%d.%s ConnectionTrace: %s%s", seconds, millis, event, suffix)
    }

    private fun epochMillisOf(line: String): Long? {
        val token = line.substringBefore(' ')
        val seconds = token.substringBefore('.').toLongOrNull() ?: return null
        val fraction = token.substringAfter('.', "")
        if (!fraction.all(Char::isDigit)) return null
        val millis = fraction.take(3).padEnd(3, '0').toLongOrNull() ?: return null
        return seconds * 1_000 + millis
    }
}
