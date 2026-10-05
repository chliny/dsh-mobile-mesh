package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.connection.ConnectionManager
import dev.dsh.mobile.mesh.connection.HarnessProtocol
import dev.dsh.mobile.mesh.core.wire.DshApiClient
import dev.dsh.mobile.mesh.core.wire.RpcResult
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Every workspace-files directory request must carry a non-empty path; `.` is the API root. */
internal fun normalizeWorkspaceFilesRequestPath(path: String): String =
    path.trim().let { clean ->
        when {
            clean.isBlank() -> "."
            clean == "." -> "."
            clean.startsWith("/") -> clean.trimStart('/').ifBlank { "." }
            else -> clean
        }
    }

internal fun validWorkspaceFilePath(path: String): String? =
    path.trim().takeIf { it.isNotEmpty() }

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
    val previewLoadingMore: Boolean = false,
    val referenceSessionId: String? = null,
    val referenceQuery: String? = null,
    val fileReferences: List<WorkspaceDirectoryEntry> = emptyList(),
    val sessionReferences: List<SessionReferenceCandidate> = emptyList(),
)

/** Bound each rendered code item so append-only server pages never retokenize already visible items. */
internal fun previewCodeChunks(text: String, maxChars: Int = 8_192): List<String> {
    require(maxChars > 0)
    if (text.isEmpty()) return listOf("")
    val chunks = ArrayList<String>()
    var start = 0
    while (start < text.length) {
        var end = (start + maxChars).coerceAtMost(text.length)
        if (end < text.length) {
            val lineEnd = text.lastIndexOf('\n', end - 1)
            if (lineEnd >= start) end = lineEnd + 1
        }
        chunks.add(text.substring(start, end))
        start = end
    }
    return chunks
}

/** Publish one server result without losing the other concurrent reference response. */
internal fun WorkspaceFilesState.withReferenceResult(
    workspaceKey: String,
    sessionId: String,
    query: String,
    files: List<WorkspaceDirectoryEntry>? = null,
    sessions: List<SessionReferenceCandidate>? = null,
): WorkspaceFilesState = if (this.workspaceKey == workspaceKey && referenceSessionId == sessionId && referenceQuery == query) {
    copy(fileReferences = files ?: fileReferences, sessionReferences = sessions ?: sessionReferences)
} else this

sealed interface PreviewState {
    data object Loading : PreviewState
    data class Text(val value: WorkspaceFileText, val chunks: List<String> = previewCodeChunks(value.text)) : PreviewState
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
    private var referenceSearchJob: Job? = null
    private var requestSerial = 0L

    fun reset(workspaceKey: String?) {
        treeJob?.cancel()
        treeJob = null
        treeRequest = null
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
        _state.value = _state.value.copy(
            referenceSessionId = sessionId, referenceQuery = query,
            fileReferences = emptyList(), sessionReferences = emptyList(),
        )
        val api = connectionManager.connectedApi ?: return
        referenceSearchJob = scope.launch {
            // Both lists come from their owning server Remotes. A missing optional session
            // resolver does not prevent file references from being offered on older Hosts.
            val files = launch {
                val result = api.fileReferencesList(sessionId, query)
                if (result is RpcResult.Ok) {
                    val entries = ((result.value as? JsonArray) ?: (result.value as? JsonObject)?.get("items") as? JsonArray)
                        .orEmpty().mapNotNull { item ->
                            val row = item as? JsonObject ?: return@mapNotNull null
                            val path = row["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                            WorkspaceDirectoryEntry(name = path, type = row["kind"]?.jsonPrimitive?.contentOrNull ?: "file")
                        }
                    _state.update { current ->
                        current.withReferenceResult(workspaceKey, sessionId, query, files = entries)
                    }
                }
            }
            val sessions = launch {
                val result = api.sessionReferenceCandidates(sessionId, query)
                if (result is RpcResult.Ok) _state.update { current ->
                    current.withReferenceResult(workspaceKey, sessionId, query, sessions = result.value)
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
        val existing = treeRequest
        if (existing?.workspaceKey == workspaceKey && existing.sessionId == sessionId && existing.path == safePath) return
        treeJob?.cancel()
        val request = TreeRequest(workspaceKey, sessionId, safePath, ++requestSerial)
        treeRequest = request
        _state.value = _state.value.copy(levels = _state.value.levels + (safePath to DirectoryLevel.Loading))
        treeJob = scope.launch {
            try {
                when (val result = api.workspaceFilesList(sessionId, safePath)) {
                    is RpcResult.Ok -> {
                        val ready = DirectoryLevel.Ready(result.value)
                        synchronized(cacheLock) {
                            listingCache.getOrPut(workspaceKey) { mutableMapOf() }[safePath] = ready
                            refreshedAt[workspaceKey] = System.currentTimeMillis()
                        }
                        updateIfCurrent(request) { copy(levels = levels + (safePath to ready)) }
                    }
                    is RpcResult.Err -> updateIfCurrent(request) {
                        copy(levels = levels + (safePath to DirectoryLevel.Failed(result.error.code, result.error.message)))
                    }
                }
            } finally {
                if (treeRequest == request) treeRequest = null
            }
        }
    }

    /** SVG images need exact bytes, not an over-limit workspace text page. */
    suspend fun readSvgBytes(sessionId: String, path: String): ByteArray? {
        val safePath = validWorkspaceFilePath(path) ?: return null
        val api = connectionManager.connectedApi ?: return null
        return when (val result = api.workspaceFilesReadAll(
            sessionId, safePath, legacyReadAll = connectionManager.harnessProtocol == HarnessProtocol.LEGACY_SUBAGENTS,
        )) {
            is RpcResult.Ok -> result.value.bytesData()
            is RpcResult.Err -> null
        }
    }

    fun readText(workspaceKey: String, sessionId: String, path: String, offset: Int = 1, limit: Int = PREVIEW_PAGE_LINES) {
        val safePath = validWorkspaceFilePath(path) ?: return
        readPreview(workspaceKey, sessionId, safePath, append = offset > 1, keepWholeText = isMarkdownPreviewPath(safePath)) { api ->
            api.workspaceFilesRead(sessionId, safePath, offset = offset, limit = limit)
        }
    }

    fun loadNextPreviewPage(workspaceKey: String, sessionId: String, path: String) {
        val safePath = validWorkspaceFilePath(path) ?: return
        val current = _state.value.preview as? PreviewState.Text ?: return
        if (current.value.eof || _state.value.previewLoadingMore) return
        _state.value = _state.value.copy(previewLoadingMore = true)
        readPreview(workspaceKey, sessionId, path, append = true, keepWholeText = isMarkdownPreviewPath(safePath), onFinished = {
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
        readPreview(workspaceKey, sessionId, safePath) { api -> api.workspaceFilesReadAll(sessionId, safePath, legacyReadAll = connectionManager.harnessProtocol == HarnessProtocol.LEGACY_SUBAGENTS) }
    }

    private fun <T> readPreview(
        workspaceKey: String,
        sessionId: String,
        path: String,
        append: Boolean = false,
        keepWholeText: Boolean = true,
        onFinished: () -> Unit = {},
        request: suspend (DshApiClient) -> RpcResult<T>,
    ) {
        val api = connectionManager.connectedApi ?: return
        previewJob?.cancel()
        if (!append) _state.value = _state.value.copy(preview = PreviewState.Loading)
        previewJob = scope.launch {
            try {
                when (val result = request(api)) {
                    is RpcResult.Ok -> updateIfCurrent(workspaceKey) {
                        copy(preview = if (append) appendPreview(preview, previewValue(result.value), keepWholeText) else previewValue(result.value))
                    }
                    is RpcResult.Err -> updateIfCurrent(workspaceKey) { copy(preview = PreviewState.Failed(result.error.code, result.error.message)) }
                }
            } finally {
                onFinished()
            }
        }
    }

    private fun appendPreview(existing: PreviewState?, next: PreviewState, keepWholeText: Boolean): PreviewState = when {
        existing is PreviewState.Text && next is PreviewState.Text -> PreviewState.Text(
            value = next.value.copy(
                offset = existing.value.offset,
                text = if (keepWholeText) existing.value.text + next.value.text else "",
                lines = existing.value.lines + next.value.lines,
                eof = next.value.eof,
            ),
            chunks = existing.chunks + next.chunks,
        )
        else -> next
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> previewValue(value: T): PreviewState = when (value) {
        is WorkspaceFileText -> PreviewState.Text(value)
        is WorkspaceFileBytes -> PreviewState.Bytes(value)
        else -> error("Unsupported workspace preview type: ${value!!::class}")
    }

    private data class TreeRequest(
        val workspaceKey: String,
        val sessionId: String,
        val path: String,
        val serial: Long,
    )

    private fun updateIfCurrent(request: TreeRequest, transform: WorkspaceFilesState.() -> WorkspaceFilesState) {
        if (_state.value.workspaceKey == request.workspaceKey && treeRequest?.serial == request.serial) {
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
