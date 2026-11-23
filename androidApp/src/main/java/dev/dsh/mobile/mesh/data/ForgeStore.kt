package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.connection.ConnectionManager
import dev.dsh.mobile.mesh.connection.missingRemoteEndpoint
import dev.dsh.mobile.mesh.core.forge.ForgeReviewComment
import dev.dsh.mobile.mesh.core.forge.ForgeReviewSelection
import dev.dsh.mobile.mesh.core.forge.ForgeReviewSource
import dev.dsh.mobile.mesh.core.forge.formatForgeReviewComment
import dev.dsh.mobile.mesh.core.forge.formatForgeThreadQuote
import dev.dsh.mobile.mesh.core.wire.HostGeneration
import dev.dsh.mobile.mesh.core.wire.RemoteStreamException
import dev.dsh.mobile.mesh.core.wire.RpcError
import dev.dsh.mobile.mesh.core.wire.RpcResult
import dev.dsh.mobile.mesh.core.wire.decodeFromJsonElement
import dev.dsh.mobile.mesh.core.wire.dto.ForgeBaseBranches
import dev.dsh.mobile.mesh.core.wire.dto.ForgeBranches
import dev.dsh.mobile.mesh.core.wire.dto.ForgeChangeRequestDiff
import dev.dsh.mobile.mesh.core.wire.dto.ForgeChangeRequestListState
import dev.dsh.mobile.mesh.core.wire.dto.ForgeChangeRequestSnapshot
import dev.dsh.mobile.mesh.core.wire.dto.ForgeChangeRequestStatesFrame
import dev.dsh.mobile.mesh.core.wire.dto.ForgeCommitList
import dev.dsh.mobile.mesh.core.wire.dto.ForgeCommitRange
import dev.dsh.mobile.mesh.core.wire.dto.ForgeLocalCommits
import dev.dsh.mobile.mesh.core.wire.dto.ForgeLocalDiff
import dev.dsh.mobile.mesh.core.wire.dto.ForgeLocalScope
import dev.dsh.mobile.mesh.core.wire.dto.ForgeReviewData
import dev.dsh.mobile.mesh.core.wire.dto.ForgeReviewThread
import dev.dsh.mobile.mesh.core.wire.dto.ForgeReviewResult
import dev.dsh.mobile.mesh.core.wire.dto.ForgeReviewWriteResult
import dev.dsh.mobile.mesh.core.wire.dto.ForgeReviewerCandidate
import dev.dsh.mobile.mesh.core.wire.dto.ForgeReviewerRef
import dev.dsh.mobile.mesh.core.wire.dto.ForgeViewedFiles
import dev.dsh.mobile.mesh.core.wire.dto.ForgePanelState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Everything one Session's forge panel surface renders. */
data class ForgeStoreState(
    val sessionId: String? = null,
    /** Null before the first stream frame; false when the Host does not compose the plugin. */
    val available: Boolean? = null,
    val loading: Boolean = false,
    val panel: ForgePanelState? = null,
    /** A stream/decode failure; unary failures stay with their call sites. */
    val streamError: RpcError? = null,
    val disconnected: Boolean = false,
)

/**
 * Process-scoped access to the optional `forgePanel` Remote ("Change requests & worktrees").
 *
 * The Host remains authoritative for every Git and code-host fact: this store opens the same
 * per-Session state stream the Web panel reads, forwards unary calls verbatim, and never derives
 * changes or worktree state locally.
 *
 * The stream opens only while a surface observes a Session. A connection generation change
 * replaces it; a Host without the plugin answers `gateway/invocation-unavailable`, which sets
 * [ForgeStoreState.available] to false so entry points can hide instead of reporting a fault.
 */
@Singleton
class ForgeStore @Inject constructor(
    private val connectionManager: ConnectionManager,
    private val chatDraftStore: ChatDraftStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(ForgeStoreState())
    val state: StateFlow<ForgeStoreState> = _state.asStateFlow()

    private var followJob: Job? = null
    @Volatile private var followToken: Long = 0L
    private val _changeRequestStates = MutableStateFlow<Map<String, ForgeChangeRequestListState>>(emptyMap())

    /** Latest host-wide change-request states, keyed by URL, for the session-list icons. */
    val changeRequestStates: StateFlow<Map<String, ForgeChangeRequestListState>> = _changeRequestStates.asStateFlow()
    private var statesJob: Job? = null
    @Volatile private var statesToken: Long = 0L
    private val commentSerial = mutableMapOf<String, Int>()
    private val threadSerial = mutableMapOf<String, Int>()
    /** Local (non-code-host) reviewed marks, keyed by session/scope/path to the reviewed digest. */
    private val localViewed = mutableMapOf<String, String>()

    /** Digest of the change last marked reviewed in one local scope, or null. */
    @Synchronized
    fun localViewedDigest(scopeKey: String, path: String): String? = localViewed["$scopeKey|$path"]

    /** Toggle one local reviewed mark; returns the new viewed state. */
    @Synchronized
    fun toggleLocalViewed(scopeKey: String, path: String, digest: String): Boolean {
        val key = "$scopeKey|$path"
        return if (localViewed[key] == digest) {
            localViewed.remove(key)
            false
        } else {
            localViewed[key] = digest
            true
        }
    }

    /**
     * Subscribe to one Session's panel state and return an ownership token.
     *
     * A second observe for the same Session replaces the stream but keeps the last published
     * snapshot, so navigating between the changes list and one file does not flash an empty page.
     *
     * @return token to pass to [release]
     */
    @Synchronized
    fun observe(sessionId: String): Long {
        val token = ++followToken
        val sameSession = _state.value.sessionId == sessionId
        if (!sameSession) {
            _state.value = ForgeStoreState(sessionId = sessionId, loading = true)
        } else if (_state.value.panel == null) {
            _state.update { it.copy(loading = true) }
        }
        followJob?.cancel()
        followJob = scope.launch { follow(sessionId, token) }
        return token
    }

    /** Stop the stream when its surface leaves, unless a newer observer already owns it. */
    @Synchronized
    fun release(token: Long) {
        if (token != followToken) return
        followJob?.cancel()
        followJob = null
    }

    /**
     * Subscribe to the host-wide change-request states used by session-list icons.
     *
     * This is a second logical stream because its scope is the whole Host, not one Session. It
     * opens only while the session list observes it; the last map stays readable for a quick
     * re-entry and a baseline frame replaces it on the next run.
     */
    @Synchronized
    fun observeChangeRequestStates(): Long {
        val token = ++statesToken
        statesJob?.cancel()
        statesJob = scope.launch { followChangeRequestStates(token) }
        return token
    }

    @Synchronized
    fun releaseChangeRequestStates(token: Long) {
        if (token != statesToken) return
        statesJob?.cancel()
        statesJob = null
    }

    private suspend fun followChangeRequestStates(token: Long): Unit = coroutineScope {
        var generationJob: Job? = null
        fun open(generation: HostGeneration) {
            generationJob?.cancel()
            generationJob = launch { collectChangeRequestStates(generation, token) }
        }
        connectionManager.generation?.let(::open)
        connectionManager.connectedGenerations.collect { generation ->
            if (token == statesToken) open(generation)
        }
    }

    private suspend fun collectChangeRequestStates(generation: HostGeneration, token: Long) {
        if (token != statesToken) return
        try {
            generation.mux.openStream("forgePanel/changeRequestStates").collect { item ->
                if (token != statesToken) return@collect
                val frame = runCatching {
                    decodeFromJsonElement(ForgeChangeRequestStatesFrame.serializer(), item)
                }.getOrNull() ?: return@collect
                _changeRequestStates.update { current ->
                    val next = if (frame.type == "baseline") mutableMapOf() else current.toMutableMap()
                    frame.states.forEach { next[it.url] = it }
                    next
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RemoteStreamException) {
            if (missingRemoteEndpoint(failure.error.code, failure.error.details, "forgePanel/changeRequestStates")) {
                _changeRequestStates.value = emptyMap()
            }
        } catch (_: Throwable) {
            // Session-list decoration is best-effort; the list remains usable without it.
        }
    }

    private fun isCurrent(sessionId: String, token: Long): Boolean =
        token == followToken && _state.value.sessionId == sessionId

    private suspend fun follow(sessionId: String, token: Long): Unit = coroutineScope {
        var generationJob: Job? = null
        fun open(generation: HostGeneration) {
            generationJob?.cancel()
            generationJob = launch { collectPanel(sessionId, generation, token) }
        }
        // A generation published before this surface subscribed is the current carrier; later
        // replacements arrive on connectedGenerations.
        connectionManager.generation?.let(::open)
        connectionManager.connectedGenerations.collect { generation ->
            if (isCurrent(sessionId, token)) open(generation)
        }
    }

    private suspend fun collectPanel(sessionId: String, generation: HostGeneration, token: Long) {
        if (!isCurrent(sessionId, token)) return
        _state.update { if (it.sessionId == sessionId) it.copy(loading = it.panel == null) else it }
        try {
            val args = buildJsonObject { put("sessionId", JsonPrimitive(sessionId)) }
            generation.mux.openStream("forgePanel/follow", args).collect { item ->
                if (!isCurrent(sessionId, token)) return@collect
                val panel = runCatching {
                    decodeFromJsonElement(ForgePanelState.serializer(), item)
                }.getOrNull()
                if (panel == null) {
                    _state.update {
                        if (it.sessionId != sessionId) it else it.copy(
                            available = true,
                            loading = false,
                            streamError = RpcError("forge/decode-failed", "The forge panel state could not be decoded"),
                            disconnected = false,
                        )
                    }
                } else {
                    _state.update {
                        if (it.sessionId != sessionId) it else it.copy(
                            available = true,
                            loading = false,
                            panel = panel,
                            streamError = null,
                            disconnected = false,
                        )
                    }
                }
            }
            if (isCurrent(sessionId, token)) {
                _state.update { if (it.sessionId == sessionId) it.copy(loading = false, disconnected = true) else it }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RemoteStreamException) {
            if (!isCurrent(sessionId, token)) return
            if (missingRemoteEndpoint(failure.error.code, failure.error.details, "forgePanel/follow")) {
                _state.update {
                    if (it.sessionId == sessionId) it.copy(
                        available = false,
                        loading = false,
                        panel = null,
                        streamError = null,
                        disconnected = false,
                    ) else it
                }
            } else {
                _state.update {
                    if (it.sessionId == sessionId) it.copy(
                        loading = false,
                        streamError = failure.error,
                        disconnected = true,
                    ) else it
                }
            }
        } catch (failure: Throwable) {
            if (!isCurrent(sessionId, token)) return
            _state.update {
                if (it.sessionId == sessionId) it.copy(
                    loading = false,
                    streamError = RpcError("forge/stream-failed", failure.message ?: "The forge panel stream failed"),
                    disconnected = true,
                ) else it
            }
        }
    }

    // ------------------------------------------------------------------ unary actions

    private fun offline(): RpcResult.Err = RpcResult.Err(RpcError("forge/offline", "Not connected to the Host"))

    private fun RpcResult<*>.asUnit(): RpcResult<Unit> = when (this) {
        is RpcResult.Ok -> RpcResult.Ok(Unit)
        is RpcResult.Err -> this
    }

    suspend fun refresh(sessionId: String): RpcResult<Unit> {
        val api = connectionManager.connectedApi ?: return offline()
        return api.forgePanelRefresh(sessionId).asUnit()
    }

    suspend fun branches(sessionId: String): RpcResult<ForgeBranches> {
        val api = connectionManager.connectedApi ?: return offline()
        return api.forgePanelBranches(sessionId)
    }

    suspend fun baseBranches(sessionId: String, query: String): RpcResult<ForgeBaseBranches> {
        val api = connectionManager.connectedApi ?: return offline()
        return api.forgePanelDiffBases(sessionId, query)
    }

    suspend fun localDiff(sessionId: String, scope: ForgeLocalScope): RpcResult<ForgeLocalDiff> {
        val api = connectionManager.connectedApi ?: return offline()
        return api.forgePanelLocalDiff(sessionId, scope)
    }

    suspend fun localCommits(sessionId: String, base: String?): RpcResult<ForgeLocalCommits> {
        val api = connectionManager.connectedApi ?: return offline()
        return api.forgePanelLocalCommits(sessionId, base)
    }

    suspend fun changeRequestDiff(
        sessionId: String,
        url: String,
        commits: ForgeCommitRange?,
    ): RpcResult<ForgeChangeRequestDiff> {
        val api = connectionManager.connectedApi ?: return offline()
        return api.forgePanelChangeRequestDiff(sessionId, url, commits)
    }

    suspend fun changeRequestCommits(sessionId: String, url: String): RpcResult<ForgeCommitList?> {
        val api = connectionManager.connectedApi ?: return offline()
        return api.forgePanelChangeRequestCommits(sessionId, url)
    }

    suspend fun requestWorktree(sessionId: String, from: String?): RpcResult<Unit> {
        val api = connectionManager.connectedApi ?: return offline()
        return api.forgePanelRequestWorktree(sessionId, from).asUnit()
    }

    suspend fun cancelWorktree(sessionId: String): RpcResult<Unit> {
        val api = connectionManager.connectedApi ?: return offline()
        return api.forgePanelCancelWorktree(sessionId).asUnit()
    }

    suspend fun retryWorktree(sessionId: String): RpcResult<Unit> {
        val api = connectionManager.connectedApi ?: return offline()
        return api.forgePanelRetryWorktree(sessionId).asUnit()
    }

    suspend fun stopCreation(sessionId: String): RpcResult<Unit> {
        val api = connectionManager.connectedApi ?: return offline()
        return api.forgePanelStopCreation(sessionId).asUnit()
    }

    suspend fun restoreWorktree(sessionId: String): RpcResult<Unit> {
        val api = connectionManager.connectedApi ?: return offline()
        return api.forgePanelRestoreWorktree(sessionId).asUnit()
    }

    suspend fun continueInProject(sessionId: String): RpcResult<Unit> {
        val api = connectionManager.connectedApi ?: return offline()
        return api.forgePanelContinueInProject(sessionId).asUnit()
    }

    suspend fun dismiss(sessionId: String, url: String): RpcResult<Unit> {
        val api = connectionManager.connectedApi ?: return offline()
        return api.forgePanelDismiss(sessionId, url).asUnit()
    }

    suspend fun checks(sessionId: String, url: String, headSha: String): RpcResult<ForgeChangeRequestSnapshot> {
        val api = connectionManager.connectedApi ?: return offline()
        return api.forgePanelChangeRequestChecks(sessionId, url, headSha)
    }

    suspend fun rerunChecks(sessionId: String, url: String, headSha: String): RpcResult<Unit> {
        val api = connectionManager.connectedApi ?: return offline()
        return api.forgePanelRerunChecks(sessionId, url, headSha).asUnit()
    }

    suspend fun viewedFiles(sessionId: String, url: String, headSha: String): RpcResult<ForgeViewedFiles?> {
        val api = connectionManager.connectedApi ?: return offline()
        return api.forgePanelChangeRequestViewed(sessionId, url, headSha)
    }

    suspend fun setFileViewed(
        sessionId: String,
        url: String,
        path: String,
        viewed: Boolean,
        headSha: String,
    ): RpcResult<Unit> {
        val api = connectionManager.connectedApi ?: return offline()
        return api.forgePanelSetFileViewed(sessionId, url, path, viewed, headSha).asUnit()
    }

    suspend fun review(sessionId: String, url: String, headSha: String, fresh: Boolean = false): RpcResult<ForgeReviewData> {
        val api = connectionManager.connectedApi ?: return offline()
        return api.forgePanelChangeRequestReview(sessionId, url, headSha, fresh)
    }

    suspend fun reviewWrite(sessionId: String, url: String, write: JsonObject): RpcResult<ForgeReviewWriteResult> {
        val api = connectionManager.connectedApi ?: return offline()
        return api.forgePanelChangeRequestReviewWrite(sessionId, url, write)
    }

    suspend fun submitReview(sessionId: String, url: String, request: JsonObject): RpcResult<ForgeReviewResult> {
        val api = connectionManager.connectedApi ?: return offline()
        return api.forgePanelSubmitReview(sessionId, url, request)
    }

    suspend fun markReady(sessionId: String, url: String): RpcResult<Unit> {
        val api = connectionManager.connectedApi ?: return offline()
        return api.forgePanelMarkReady(sessionId, url).asUnit()
    }

    suspend fun merge(sessionId: String, url: String, method: String, headSha: String): RpcResult<Unit> {
        val api = connectionManager.connectedApi ?: return offline()
        return api.forgePanelMerge(sessionId, url, method, headSha).asUnit()
    }

    suspend fun updateBranch(sessionId: String, url: String, method: String, headSha: String): RpcResult<Unit> {
        val api = connectionManager.connectedApi ?: return offline()
        return api.forgePanelUpdateBranch(sessionId, url, method, headSha).asUnit()
    }

    suspend fun requestReviews(sessionId: String, url: String, reviewers: List<ForgeReviewerRef>): RpcResult<Unit> {
        val api = connectionManager.connectedApi ?: return offline()
        return api.forgePanelRequestReviews(sessionId, url, reviewers).asUnit()
    }

    suspend fun reviewerCandidates(sessionId: String, url: String, query: String): RpcResult<List<ForgeReviewerCandidate>> {
        val api = connectionManager.connectedApi ?: return offline()
        return api.forgePanelReviewerCandidates(sessionId, url, query)
    }

    suspend fun localText(sessionId: String, scope: ForgeLocalScope, path: String): RpcResult<String> {
        val api = connectionManager.connectedApi ?: return offline()
        return api.forgePanelLocalText(sessionId, scope, path)
    }

    suspend fun changeRequestText(sessionId: String, url: String, commit: String, path: String): RpcResult<String> {
        val api = connectionManager.connectedApi ?: return offline()
        return api.forgePanelChangeRequestText(sessionId, url, commit, path)
    }

    // ------------------------------------------------------------------ composer references

    /**
     * Freeze one line comment into the Session composer.
     *
     * The reference numbering is per Session so several comments added from different files read
     * as one list. ChatScreen merges the waiting text into whichever draft bucket owns the Session
     * when it comes back on screen.
     *
     * @return the one-based reference number assigned
     */
    fun addCommentReference(sessionId: String, source: ForgeReviewSource, comment: ForgeReviewComment): Int {
        val index = synchronized(commentSerial) {
            val next = (commentSerial[sessionId] ?: 0) + 1
            commentSerial[sessionId] = next
            next
        }
        chatDraftStore.appendPending(
            sessionId,
            formatForgeReviewComment(ForgeReviewSelection(source, comment), index),
        )
        return index
    }

    /**
     * Freeze one review thread into the Session composer as a `## Review Thread` reference.
     *
     * @return the one-based thread reference number assigned
     */
    fun addThreadReference(sessionId: String, thread: ForgeReviewThread, url: String): Int {
        val index = synchronized(threadSerial) {
            val next = (threadSerial[sessionId] ?: 0) + 1
            threadSerial[sessionId] = next
            next
        }
        chatDraftStore.appendPending(sessionId, formatForgeThreadQuote(thread, url, index))
        return index
    }
}
