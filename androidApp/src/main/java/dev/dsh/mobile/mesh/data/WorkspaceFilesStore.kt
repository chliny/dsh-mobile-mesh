package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.connection.ConnectionManager
import dev.dsh.mobile.mesh.core.wire.DshApiClient
import dev.dsh.mobile.mesh.core.wire.RpcResult
import dev.dsh.mobile.mesh.core.wire.dto.WorkspaceDirectoryEntry
import dev.dsh.mobile.mesh.core.wire.dto.WorkspaceDirectoryListing
import dev.dsh.mobile.mesh.core.wire.dto.WorkspaceFileBytes
import dev.dsh.mobile.mesh.core.wire.dto.WorkspaceFileText
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

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
)

sealed interface PreviewState {
    data object Loading : PreviewState
    data class Text(val value: WorkspaceFileText) : PreviewState
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
        requestSerial++
        val cached = workspaceKey?.let { key -> synchronized(cacheLock) { listingCache[key].orEmpty().toMap() } }.orEmpty()
        _state.value = WorkspaceFilesState(workspaceKey = workspaceKey, levels = cached)
    }

    fun isStale(workspaceKey: String, now: Long = System.currentTimeMillis()): Boolean = synchronized(cacheLock) {
        now - (refreshedAt[workspaceKey] ?: 0L) >= CACHE_TTL_MS
    }

    fun searchReferences(workspaceKey: String, sessionId: String, query: String) {
        val api = connectionManager.connectedApi ?: return
        referenceSearchJob?.cancel()
        referenceSearchJob = scope.launch {
            // Ask the host for path-aware references. Unlike the root directory cache this can
            // resolve `tmp/deepseek-harness` directly without requiring every subdirectory to be
            // opened in the file browser first.
            when (val result = api.fileReferencesList(sessionId, query)) {
                is RpcResult.Ok -> {
                    val entries = ((result.value as? JsonArray) ?: (result.value as? JsonObject)?.get("items") as? JsonArray)
                        .orEmpty().mapNotNull { item ->
                            val row = item as? JsonObject ?: return@mapNotNull null
                            val path = row["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                            WorkspaceDirectoryEntry(name = path, type = row["kind"]?.jsonPrimitive?.contentOrNull ?: "file")
                        }
                    val ready = DirectoryLevel.Ready(WorkspaceDirectoryListing(path = "@", entries = entries))
                    synchronized(cacheLock) {
                        listingCache.getOrPut(workspaceKey) { mutableMapOf() }["@"] = ready
                        refreshedAt[workspaceKey] = System.currentTimeMillis()
                    }
                    if (_state.value.workspaceKey == workspaceKey) _state.value = _state.value.copy(levels = _state.value.levels + ("@" to ready))
                }
                is RpcResult.Err -> Unit
            }
        }
    }

    fun list(workspaceKey: String, sessionId: String, path: String, reload: Boolean = false) {
        if (_state.value.workspaceKey != workspaceKey) reset(workspaceKey)
        if (!reload && _state.value.levels[path] is DirectoryLevel.Ready) return
        val api = connectionManager.connectedApi ?: return
        val existing = treeRequest
        if (existing?.workspaceKey == workspaceKey && existing.sessionId == sessionId && existing.path == path) return
        treeJob?.cancel()
        val request = TreeRequest(workspaceKey, sessionId, path, ++requestSerial)
        treeRequest = request
        _state.value = _state.value.copy(levels = _state.value.levels + (path to DirectoryLevel.Loading))
        treeJob = scope.launch {
            try {
                when (val result = api.workspaceFilesList(sessionId, path)) {
                    is RpcResult.Ok -> {
                        val ready = DirectoryLevel.Ready(result.value)
                        synchronized(cacheLock) {
                            listingCache.getOrPut(workspaceKey) { mutableMapOf() }[path] = ready
                            refreshedAt[workspaceKey] = System.currentTimeMillis()
                        }
                        updateIfCurrent(request) { copy(levels = levels + (path to ready)) }
                    }
                    is RpcResult.Err -> updateIfCurrent(request) {
                        copy(levels = levels + (path to DirectoryLevel.Failed(result.error.code, result.error.message)))
                    }
                }
            } finally {
                if (treeRequest == request) treeRequest = null
            }
        }
    }

    suspend fun readTextContent(sessionId: String, path: String): String? {
        val api = connectionManager.connectedApi ?: return null
        return when (val result = api.workspaceFilesRead(sessionId, path, offset = 1, limit = 20_000)) {
            is RpcResult.Ok -> result.value.text
            is RpcResult.Err -> null
        }
    }

    fun readText(workspaceKey: String, sessionId: String, path: String, offset: Int = 1, limit: Int = PREVIEW_PAGE_LINES) {
        readPreview(workspaceKey, sessionId, path, append = offset > 1) { api ->
            api.workspaceFilesRead(sessionId, path, offset = offset, limit = limit)
        }
    }

    fun loadNextPreviewPage(workspaceKey: String, sessionId: String, path: String) {
        val current = _state.value.preview as? PreviewState.Text ?: return
        if (current.value.eof || _state.value.previewLoadingMore) return
        _state.value = _state.value.copy(previewLoadingMore = true)
        readPreview(workspaceKey, sessionId, path, append = true, onFinished = {
            _state.value = _state.value.copy(previewLoadingMore = false)
        }) { api ->
            api.workspaceFilesRead(
                sessionId,
                path,
                offset = current.value.offset + current.value.lines,
                limit = PREVIEW_PAGE_LINES,
            )
        }
    }

    fun readBytes(workspaceKey: String, sessionId: String, path: String) {
        readPreview(workspaceKey, sessionId, path) { api -> api.workspaceFilesReadAll(sessionId, path) }
    }

    private fun <T> readPreview(
        workspaceKey: String,
        sessionId: String,
        path: String,
        append: Boolean = false,
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
                        copy(preview = if (append) appendPreview(preview, previewValue(result.value)) else previewValue(result.value))
                    }
                    is RpcResult.Err -> updateIfCurrent(workspaceKey) { copy(preview = PreviewState.Failed(result.error.code, result.error.message)) }
                }
            } finally {
                onFinished()
            }
        }
    }

    private fun appendPreview(existing: PreviewState?, next: PreviewState): PreviewState = when {
        existing is PreviewState.Text && next is PreviewState.Text -> PreviewState.Text(
            next.value.copy(
                offset = existing.value.offset,
                text = existing.value.text + next.value.text,
                lines = existing.value.lines + next.value.lines,
                eof = next.value.eof,
            ),
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
