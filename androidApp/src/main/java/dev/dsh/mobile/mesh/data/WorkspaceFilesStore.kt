package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.connection.ConnectionManager
import dev.dsh.mobile.mesh.connection.HarnessProtocol
import dev.dsh.mobile.mesh.core.wire.DshApiClient
import dev.dsh.mobile.mesh.core.wire.RpcError
import dev.dsh.mobile.mesh.core.wire.RpcResult
import dev.dsh.mobile.mesh.core.wire.TransportFailures
import dev.dsh.mobile.mesh.core.wire.dto.WorkspaceDirectoryEntry
import dev.dsh.mobile.mesh.core.wire.dto.WorkspaceDirectoryListing
import dev.dsh.mobile.mesh.core.wire.dto.WorkspaceFileBytes
import dev.dsh.mobile.mesh.core.wire.dto.WorkspaceFileText
import dev.dsh.mobile.mesh.core.wire.dto.SessionReferenceCandidate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Every workspace-files directory request must carry a non-empty path; `.` is the API root. */
internal fun normalizeWorkspaceFilesRequestPath(path: String): String =
    when {
        path.isEmpty() -> "."
        path.startsWith("/") -> path.trimStart('/').ifEmpty { "." }
        else -> path
    }

internal fun validWorkspaceFilePath(path: String): String? = path.takeIf { it.isNotEmpty() }

internal data class DirectoryListRequest(val workspaceKey: String, val sessionId: String, val path: String)

/** An explicit refresh must replace even an identical in-flight list request. */
internal fun shouldReuseDirectoryList(
    existing: DirectoryListRequest?,
    requested: DirectoryListRequest,
    reload: Boolean,
): Boolean = !reload && existing == requested

/** Decode the server's candidate rows without letting one malformed row cancel the companion search. */
internal fun decodeFileReferenceRows(value: JsonElement): List<WorkspaceDirectoryEntry> {
    val entries = ((value as? JsonArray) ?: (value as? JsonObject)?.get("items") as? JsonArray).orEmpty()
    return entries.mapNotNull { item ->
        val row = item as? JsonObject ?: return@mapNotNull null
        val path = (row["path"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        val kind = (row["kind"] as? JsonPrimitive)?.contentOrNull ?: "file"
        WorkspaceDirectoryEntry(name = path, type = kind)
    }
}

private const val MAX_MARKDOWN_IMAGE_BYTES = 2 * 1024 * 1024

private fun isMarkdownPreviewPath(path: String): Boolean =
    path.substringAfterLast('.', "").lowercase() in setOf("md", "markdown", "mdown", "mkd", "rmd")

sealed interface DirectoryLevel {
    data object Loading : DirectoryLevel
    data class Ready(val listing: WorkspaceDirectoryListing) : DirectoryLevel
    data class Failed(val code: String, val message: String) : DirectoryLevel
}

data class WorkspaceFilesState(
    val workspaceKey: String? = null,
    val levels: Map<String, DirectoryLevel> = emptyMap(),
    val preview: PreviewState? = null,
    val previewPath: String? = null,
    val previewSessionId: String? = null,
    val previewLoadingMore: Boolean = false,
    val previewPageError: String? = null,
    val referenceSessionId: String? = null,
    val referenceQuery: String? = null,
    val referenceSerial: Long = 0,
    val fileReferences: List<WorkspaceDirectoryEntry> = emptyList(),
    val sessionReferences: List<SessionReferenceCandidate> = emptyList(),
)

/**
 * Split one server page into displayed lines.
 *
 * The Host joins a page's lines with `\n` and never adds a trailing separator, so an empty
 * final element is a real blank line. A page holding one blank line has empty text but a
 * non-zero declared line count; [declaredLines] keeps that line visible. Page boundaries are
 * handled by [appendWorkspacePageText], not by this function.
 */
internal fun previewPageLines(text: String, declaredLines: Int = 0): List<String> {
    if (text.isEmpty()) return if (declaredLines > 0) List(declaredLines) { "" } else emptyList()
    return text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
}

/** Split a whole-file text into logical lines; a trailing newline does not start a new line. */
internal fun workspaceFileLines(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
    val lines = normalized.split('\n').toMutableList()
    if (normalized.endsWith('\n')) lines.removeAt(lines.lastIndex)
    return lines
}

/**
 * Join two server pages without merging their boundary lines.
 *
 * Each page carries only internal separators, so a boundary always needs exactly one `\n` even
 * when the previous page's last line was blank.
 */
internal fun appendWorkspacePageText(first: String, second: String): String = when {
    first.isEmpty() -> second
    second.isEmpty() -> first
    else -> "$first\n$second"
}

/** Publish one server result without losing the other concurrent reference response. */
internal fun WorkspaceFilesState.withReferenceResult(
    workspaceKey: String,
    sessionId: String,
    query: String,
    serial: Long,
    files: List<WorkspaceDirectoryEntry>? = null,
    sessions: List<SessionReferenceCandidate>? = null,
): WorkspaceFilesState = if (this.workspaceKey == workspaceKey && referenceSessionId == sessionId &&
    referenceQuery == query && referenceSerial == serial) {
    copy(fileReferences = files ?: fileReferences, sessionReferences = sessions ?: sessionReferences)
} else this

internal fun WorkspaceFilesState.canLoadNextPreviewPage(sessionId: String, path: String, hasApi: Boolean): Boolean =
    hasApi && previewSessionId == sessionId && previewPath == path && previewPageError == null &&
        preview is PreviewState.Text && !preview.value.eof && !previewLoadingMore

/** A later page failure does not revoke the earlier text the server already supplied. */
internal fun WorkspaceFilesState.withPreviewError(error: RpcError, append: Boolean): WorkspaceFilesState =
    if (append && preview is PreviewState.Text) copy(previewPageError = error.message)
    else copy(preview = PreviewState.Failed(error.code, error.message), previewPageError = null)

/** Serializes publication with replacement requests, including non-cooperative replies. */
internal class RequestGenerationFence {
    private var generation = 0L

    @Synchronized fun next(): Long = ++generation

    @Synchronized fun runIfCurrent(serial: Long, action: () -> Unit): Boolean {
        if (serial != generation) return false
        action()
        return true
    }
}

sealed interface PreviewState {
    data object Loading : PreviewState
    data class Text(
        val value: WorkspaceFileText,
        val displayLines: List<String> = previewPageLines(value.text, value.lines),
    ) : PreviewState
    data class Bytes(val value: WorkspaceFileBytes) : PreviewState
    data class Failed(val code: String, val message: String) : PreviewState
}

/** Workspace-scoped file access. Directory and reference caches are shared by all sessions in a workspace. */
@Singleton
class WorkspaceFilesStore @Inject constructor(
    private val connectionManager: ConnectionManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(WorkspaceFilesState())
    val state: StateFlow<WorkspaceFilesState> = _state.asStateFlow()
    private val listingCache = mutableMapOf<String, MutableMap<String, DirectoryLevel.Ready>>()
    private val refreshedAt = mutableMapOf<String, Long>()
    private val cacheLock = Any()
    private var treeJob: Job? = null
    private var treeRequest: TreeRequest? = null
    private var previewJob: Job? = null
    private val previewFence = RequestGenerationFence()
    private val directoryFence = RequestGenerationFence()
    private var referenceSearchJob: Job? = null
    private var requestSerial = 0L

    fun reset(workspaceKey: String?) {
        directoryFence.next()
        treeJob?.cancel()
        treeJob = null
        treeRequest = null
        previewFence.next()
        previewJob?.cancel()
        referenceSearchJob?.cancel()
        requestSerial++
        val cached = workspaceKey?.let { key -> synchronized(cacheLock) { listingCache[key].orEmpty().toMap() } }.orEmpty()
        _state.value = WorkspaceFilesState(workspaceKey = workspaceKey, levels = cached)
    }

    fun isStale(workspaceKey: String, now: Long = System.currentTimeMillis()): Boolean = synchronized(cacheLock) {
        now - (refreshedAt[workspaceKey] ?: 0L) >= CACHE_TTL_MS
    }

    fun searchReferences(workspaceKey: String, sessionId: String, query: String) {
        if (_state.value.workspaceKey != workspaceKey) reset(workspaceKey)
        referenceSearchJob?.cancel()
        val serial = ++requestSerial
        _state.value = _state.value.copy(
            referenceSessionId = sessionId, referenceQuery = query, referenceSerial = serial,
            fileReferences = emptyList(), sessionReferences = emptyList(),
        )
        val api = connectionManager.connectedApi ?: return
        referenceSearchJob = scope.launch {
            // Both lists come from their owning server Remotes. A missing optional session
            // resolver does not prevent file references from being offered on older Hosts.
            val files = launch {
                val result = api.fileReferencesList(sessionId, query)
                if (result is RpcResult.Ok) {
                    val entries = decodeFileReferenceRows(result.value)
                    _state.update { current ->
                        current.withReferenceResult(workspaceKey, sessionId, query, serial, files = entries)
                    }
                }
            }
            val sessions = launch {
                val result = api.sessionReferenceCandidates(sessionId, query)
                if (result is RpcResult.Ok) _state.update { current ->
                    current.withReferenceResult(workspaceKey, sessionId, query, serial, sessions = result.value)
                }
            }
            files.join()
            sessions.join()
        }
    }

    fun list(workspaceKey: String, sessionId: String, path: String, reload: Boolean = false) {
        val safePath = normalizeWorkspaceFilesRequestPath(path)
        if (_state.value.workspaceKey != workspaceKey) reset(workspaceKey)
        if (!reload && _state.value.levels[safePath] is DirectoryLevel.Ready) return
        val api = connectionManager.connectedApi ?: return
        val requestKey = DirectoryListRequest(workspaceKey, sessionId, safePath)
        if (shouldReuseDirectoryList(treeRequest?.key, requestKey, reload)) return
        treeJob?.cancel()
        val request = TreeRequest(requestKey, directoryFence.next())
        treeRequest = request
        _state.value = _state.value.copy(levels = _state.value.levels + (safePath to DirectoryLevel.Loading))
        treeJob = scope.launch {
            try {
                when (val result = api.workspaceFilesList(sessionId, safePath)) {
                    is RpcResult.Ok -> directoryFence.runIfCurrent(request.serial) {
                        val ready = DirectoryLevel.Ready(result.value)
                        synchronized(cacheLock) {
                            listingCache.getOrPut(workspaceKey) { mutableMapOf() }[safePath] = ready
                            refreshedAt[workspaceKey] = System.currentTimeMillis()
                        }
                        updateIfCurrent(request) { copy(levels = levels + (safePath to ready)) }
                    }
                    is RpcResult.Err -> directoryFence.runIfCurrent(request.serial) {
                        updateIfCurrent(request) {
                            copy(levels = levels + (safePath to DirectoryLevel.Failed(result.error.code, result.error.message)))
                        }
                    }
                }
            } finally {
                if (treeRequest == request) treeRequest = null
            }
        }
    }

    /** Bound image reads before encoding their bytes into a WebView data URL. */
    suspend fun readImageBytes(sessionId: String, path: String): ByteArray? {
        val safePath = validWorkspaceFilePath(path) ?: return null
        val api = connectionManager.connectedApi ?: return null
        // The legacy readAll endpoint has no byte-range cap; never fetch an unbounded image.
        if (connectionManager.harnessProtocol == HarnessProtocol.LEGACY_SUBAGENTS) return null
        return when (val result = api.workspaceFilesReadBytes(sessionId, safePath, length = MAX_MARKDOWN_IMAGE_BYTES)) {
            is RpcResult.Ok -> result.value.data.takeIf {
                result.value.offset == 0 && result.value.eof && it.size <= MAX_MARKDOWN_IMAGE_BYTES
            }
            is RpcResult.Err -> null
        }
    }

    fun readText(workspaceKey: String, sessionId: String, path: String, offset: Int = 1, limit: Int = PREVIEW_PAGE_LINES) {
        val safePath = validWorkspaceFilePath(path) ?: return
        // An explicit source-line offset opens a new preview, not an append to the prior file.
        readPreview(workspaceKey, sessionId, safePath, keepWholeText = isMarkdownPreviewPath(safePath)) { api ->
            when (val result = api.workspaceFilesRead(sessionId, safePath, offset = offset, limit = limit)) {
                is RpcResult.Ok -> result
                is RpcResult.Err -> {
                    // The text endpoint rejects legacy-encoded source; fetch authoritative bytes and
                    // decode locally only when the failure is the Host's answer rather than a broken
                    // carrier. Retrying a timed-out request with another route cannot help.
                    if (TransportFailures.of(result.error) != null) result
                    else when (val raw = api.workspaceFilesReadAll(sessionId, safePath)) {
                        is RpcResult.Err -> result
                        is RpcResult.Ok -> RpcResult.Ok(legacyTextPage(raw.value, offset))
                    }
                }
            }
        }
    }

    /**
     * Rebuild a text page from the complete bytes of a file the UTF-8 text route refused.
     *
     * The whole decoded file is in hand, so the page runs to the end and reports EOF. This also
     * keeps paging away from the UTF-8-only text route, which would reject every next page.
     */
    private fun legacyTextPage(raw: WorkspaceFileBytes, offset: Int): WorkspaceFileText {
        val allLines = workspaceFileLines(decodeWorkspaceText(raw.bytesData()))
        val start = (offset - 1).coerceIn(0, allLines.size)
        val pageLines = allLines.subList(start, allLines.size)
        return WorkspaceFileText(
            absolutePath = raw.absolutePath,
            version = raw.version,
            bytes = raw.bytes,
            offset = if (pageLines.isEmpty()) 1 else start + 1,
            text = pageLines.joinToString("\n"),
            lines = pageLines.size,
            eof = true,
        )
    }

    fun loadNextPreviewPage(workspaceKey: String, sessionId: String, path: String) {
        val safePath = validWorkspaceFilePath(path) ?: return
        val api = connectionManager.connectedApi
        val state = _state.value
        if (!state.canLoadNextPreviewPage(sessionId, safePath, hasApi = api != null)) return
        val current = state.preview as PreviewState.Text
        _state.value = state.copy(previewLoadingMore = true)
        readPreview(workspaceKey, sessionId, safePath, append = true, keepWholeText = isMarkdownPreviewPath(safePath), api = api, onFinished = {
            _state.value = _state.value.copy(previewLoadingMore = false)
        }) { api ->
            api.workspaceFilesRead(
                sessionId,
                safePath,
                offset = current.value.offset + current.value.lines,
                limit = PREVIEW_PAGE_LINES,
            )
        }
    }

    fun readBytes(workspaceKey: String, sessionId: String, path: String) {
        val safePath = validWorkspaceFilePath(path) ?: return
        readPreview(workspaceKey, sessionId, safePath) { api -> api.workspaceFilesReadAll(sessionId, safePath) }
    }

    private fun <T> readPreview(
        workspaceKey: String,
        sessionId: String,
        path: String,
        append: Boolean = false,
        keepWholeText: Boolean = true,
        api: DshApiClient? = connectionManager.connectedApi,
        onFinished: () -> Unit = {},
        request: suspend (DshApiClient) -> RpcResult<T>,
    ) {
        val serial = previewFence.next()
        previewJob?.cancel()
        val activeApi = api ?: return
        if (!append) _state.value = _state.value.copy(
            preview = PreviewState.Loading,
            previewPath = path,
            previewSessionId = sessionId,
            previewLoadingMore = false,
            previewPageError = null,
        )
        previewJob = scope.launch {
            try {
                when (val result = request(activeApi)) {
                    is RpcResult.Ok -> previewFence.runIfCurrent(serial) {
                        updateIfCurrent(workspaceKey) {
                            copy(
                                preview = if (append) appendPreview(preview, previewValue(result.value), keepWholeText) else previewValue(result.value),
                                previewPageError = null,
                            )
                        }
                    }
                    is RpcResult.Err -> previewFence.runIfCurrent(serial) {
                        updateIfCurrent(workspaceKey) { withPreviewError(result.error, append) }
                    }
                }
            } finally {
                previewFence.runIfCurrent(serial, onFinished)
            }
        }
    }

    private fun appendPreview(existing: PreviewState?, next: PreviewState, keepWholeText: Boolean): PreviewState = when {
        existing is PreviewState.Text && next is PreviewState.Text -> PreviewState.Text(
            value = next.value.copy(
                version = existing.value.version,
                offset = existing.value.offset,
                // Markdown renders the whole document; code keeps only its line list so a long
                // file is not stored twice under both forms.
                text = if (keepWholeText) appendWorkspacePageText(existing.value.text, next.value.text) else "",
                lines = existing.value.lines + next.value.lines,
                eof = next.value.eof,
            ),
            displayLines = existing.displayLines + next.displayLines,
        )
        else -> next
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> previewValue(value: T): PreviewState = when (value) {
        is WorkspaceFileText -> PreviewState.Text(value)
        is WorkspaceFileBytes -> PreviewState.Bytes(value)
        else -> error("Unsupported workspace preview type: ${value!!::class}")
    }

    private data class TreeRequest(val key: DirectoryListRequest, val serial: Long)

    private fun updateIfCurrent(request: TreeRequest, transform: WorkspaceFilesState.() -> WorkspaceFilesState) {
        if (_state.value.workspaceKey == request.key.workspaceKey && treeRequest?.serial == request.serial) {
            _state.value = transform(_state.value)
        }
    }

    private fun updateIfCurrent(workspaceKey: String, transform: WorkspaceFilesState.() -> WorkspaceFilesState) {
        if (_state.value.workspaceKey == workspaceKey) _state.value = transform(_state.value)
    }

    private companion object {
        const val CACHE_TTL_MS = 30_000L
        const val PREVIEW_PAGE_LINES = 400
    }
}
