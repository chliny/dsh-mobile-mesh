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

sealed interface DirectoryLevel {
    data object Loading : DirectoryLevel
    data class Ready(val listing: WorkspaceDirectoryListing) : DirectoryLevel
    data class Failed(val code: String, val message: String) : DirectoryLevel
}

data class WorkspaceFilesState(
    val sessionId: String? = null,
    val levels: Map<String, DirectoryLevel> = emptyMap(),
    val preview: PreviewState? = null,
)

sealed interface PreviewState {
    data object Loading : PreviewState
    data class Text(val value: WorkspaceFileText) : PreviewState
    data class Bytes(val value: WorkspaceFileBytes) : PreviewState
    data class Failed(val code: String, val message: String) : PreviewState
}

/** Session-scoped workspace file access with latest-request-wins state updates. */
@Singleton
class WorkspaceFilesStore @Inject constructor(
    private val connectionManager: ConnectionManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(WorkspaceFilesState())
    val state: StateFlow<WorkspaceFilesState> = _state.asStateFlow()
    private var treeJob: Job? = null
    private var previewJob: Job? = null

    fun reset(sessionId: String?) {
        treeJob?.cancel()
        previewJob?.cancel()
        _state.value = WorkspaceFilesState(sessionId = sessionId)
    }

    fun list(sessionId: String, path: String, reload: Boolean = false) {
        if (_state.value.sessionId != sessionId) reset(sessionId)
        if (!reload && _state.value.levels[path] is DirectoryLevel.Ready) return
        val api = connectionManager.connectedApi ?: return
        _state.value = _state.value.copy(levels = _state.value.levels + (path to DirectoryLevel.Loading))
        treeJob?.cancel()
        treeJob = scope.launch {
            when (val result = api.workspaceFilesList(sessionId, path)) {
                is RpcResult.Ok -> updateIfCurrent(sessionId) {
                    copy(levels = levels + (path to DirectoryLevel.Ready(result.value)))
                }
                is RpcResult.Err -> updateIfCurrent(sessionId) {
                    copy(levels = levels + (path to DirectoryLevel.Failed(result.error.code, result.error.message)))
                }
            }
        }
    }

    fun readText(sessionId: String, path: String) {
        if (_state.value.sessionId != sessionId) reset(sessionId)
        val api = connectionManager.connectedApi ?: return
        previewJob?.cancel()
        _state.value = _state.value.copy(preview = PreviewState.Loading)
        previewJob = scope.launch {
            when (val result = api.workspaceFilesRead(sessionId, path)) {
                is RpcResult.Ok -> updateIfCurrent(sessionId) { copy(preview = PreviewState.Text(result.value)) }
                is RpcResult.Err -> updateIfCurrent(sessionId) {
                    copy(preview = PreviewState.Failed(result.error.code, result.error.message))
                }
            }
        }
    }

    fun readBytes(sessionId: String, path: String) {
        if (_state.value.sessionId != sessionId) reset(sessionId)
        val api = connectionManager.connectedApi ?: return
        previewJob?.cancel()
        _state.value = _state.value.copy(preview = PreviewState.Loading)
        previewJob = scope.launch {
            when (val result = api.workspaceFilesReadAll(sessionId, path)) {
                is RpcResult.Ok -> updateIfCurrent(sessionId) { copy(preview = PreviewState.Bytes(result.value)) }
                is RpcResult.Err -> updateIfCurrent(sessionId) {
                    copy(preview = PreviewState.Failed(result.error.code, result.error.message))
                }
            }
        }
    }

    private fun updateIfCurrent(sessionId: String, transform: WorkspaceFilesState.() -> WorkspaceFilesState) {
        if (_state.value.sessionId == sessionId) _state.value = transform(_state.value)
    }
}
