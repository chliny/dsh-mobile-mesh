package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.connection.ConnectionManager
import dev.dsh.mobile.mesh.core.wire.RpcResult
import dev.dsh.mobile.mesh.core.wire.dto.WebTerminalInfo
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * The `terminal/list` result for one Session, shared by every view of that Session.
 *
 * The Host remains the only source of terminal metadata: this class stores the authoritative list
 * it returned, remembers one failure until an explicit refresh, and never invents, filters or
 * reorders rows. A connection generation is carried with each settled result so a list read
 * before a reconnect cannot pass for current data; [read] then loads once more.
 */
internal class TerminalInventory(
    private val currentGeneration: () -> Any?,
    private val load: suspend () -> RpcResult<List<WebTerminalInfo>>,
) {
    private sealed interface State {
        data object Unloaded : State
        data class Ready(val generation: Any?) : State
        data class Failed(val failure: Throwable, val generation: Any?) : State
        class Loading(val deferred: CompletableDeferred<List<WebTerminalInfo>>) : State {
            /** Confirmed mutations received while the list request is in flight. */
            val updates = mutableMapOf<String, WebTerminalInfo?>()
        }
    }

    /** One `read` call's decision: an answer already available, or a request it must drive. */
    private sealed interface Acquired {
        data class Answer(val items: List<WebTerminalInfo>) : Acquired
        data class Refused(val failure: Throwable) : Acquired
        data class Loading(val deferred: CompletableDeferred<List<WebTerminalInfo>>, val ownsRequest: Boolean) : Acquired
    }

    private val lock = Any()
    private var state: State = State.Unloaded
    private var items: List<WebTerminalInfo> = emptyList()

    /**
     * Read the cached list, join its single in-flight request, or replace a settled result.
     *
     * @param refresh explicitly replace a settled success or failure, including one observed
     *   before a reconnect; overlapping refreshes share one request.
     * @returns the Host-provided terminal rows in server order.
     * @throws TerminalListFailure when the Host refuses the list request.
     * @throws TerminalListOffline when no connection can answer it.
     */
    suspend fun read(refresh: Boolean = false): List<WebTerminalInfo> =
        when (val acquired = acquire(refresh)) {
            is Acquired.Answer -> acquired.items
            is Acquired.Refused -> throw acquired.failure
            is Acquired.Loading -> {
                if (acquired.ownsRequest) settle(acquired.deferred)
                acquired.deferred.await()
            }
        }

    /** Record confirmed Host metadata for one terminal without dropping the rest of the list. */
    fun update(info: WebTerminalInfo) = mutate(info.id, info)

    /** Drop a terminal the Host confirmed closed or missing. */
    fun remove(id: String) = mutate(id, null)

    private fun acquire(refresh: Boolean): Acquired = synchronized(lock) {
        val generation = currentGeneration()
        when (val current = state) {
            is State.Loading -> if (refresh) renew() else Acquired.Loading(current.deferred, ownsRequest = false)
            is State.Ready ->
                if (!refresh && current.generation === generation) Acquired.Answer(items) else renew()
            is State.Failed ->
                if (!refresh && current.generation === generation) Acquired.Refused(current.failure) else renew()
            State.Unloaded -> renew()
        }
    }

    /** Start a list request, replacing whatever settled state preceded it. */
    private fun renew(): Acquired.Loading {
        val deferred = CompletableDeferred<List<WebTerminalInfo>>()
        state = State.Loading(deferred)
        return Acquired.Loading(deferred, ownsRequest = true)
    }

    private fun mutate(id: String, info: WebTerminalInfo?) {
        synchronized(lock) {
            items = items.applying(mapOf(id to info))
            (state as? State.Loading)?.updates?.set(id, info)
        }
    }

    private suspend fun settle(deferred: CompletableDeferred<List<WebTerminalInfo>>) {
        val generation = currentGeneration()
        val outcome = try {
            when (val result = load()) {
                is RpcResult.Ok -> Result.success(result.value)
                is RpcResult.Err -> Result.failure(TerminalListFailure(result.error.code, result.error.message))
            }
        } catch (cancelled: CancellationException) {
            synchronized(lock) { if ((state as? State.Loading)?.deferred === deferred) state = State.Unloaded }
            deferred.completeExceptionally(cancelled)
            throw cancelled
        } catch (failure: Throwable) {
            Result.failure(failure)
        }

        var answer: List<WebTerminalInfo>? = null
        var refusal: Throwable? = null
        val published = synchronized(lock) {
            val loading = state as? State.Loading
            if (loading?.deferred !== deferred) return@synchronized false
            outcome.fold(
                onSuccess = { loaded ->
                    // Answer with the same rows the cache published, including mutations that
                    // arrived while the list request was in flight.
                    items = loaded.applying(loading.updates)
                    answer = items
                    state = State.Ready(generation)
                },
                onFailure = { failure ->
                    refusal = failure
                    state = State.Failed(failure, generation)
                },
            )
            true
        }
        if (!published) {
            // A newer read replaced this one (a reconnect or an explicit retry): answer with that
            // read instead of publishing the superseded generation's rows.
            try {
                deferred.complete(read())
            } catch (cancelled: CancellationException) {
                deferred.completeExceptionally(cancelled)
                throw cancelled
            } catch (failure: Throwable) {
                deferred.completeExceptionally(failure)
            }
            return
        }
        val itemsAnswer = answer
        if (itemsAnswer != null) deferred.complete(itemsAnswer) else deferred.completeExceptionally(refusal!!)
    }
}

/** Apply confirmed mutations to a list ordered by the Host. */
private fun List<WebTerminalInfo>.applying(updates: Map<String, WebTerminalInfo?>): List<WebTerminalInfo> {
    if (updates.isEmpty()) return this
    val merged = toMutableList()
    for ((id, info) in updates) {
        val index = merged.indexOfFirst { it.id == id }
        when {
            info == null -> if (index >= 0) merged.removeAt(index)
            index >= 0 -> merged[index] = info
            else -> merged.add(info)
        }
    }
    return merged
}

/** One Session's [TerminalInventory], retaining `terminal/list` results for its views. */
@Singleton
class TerminalInventoryStore @Inject constructor(
    private val connectionManager: ConnectionManager,
) {
    private val inventories = mutableMapOf<String, TerminalInventory>()

    /** Resolve the Session's shared inventory, creating it on first use. */
    internal fun inventory(sessionId: String): TerminalInventory = synchronized(inventories) {
        inventories.getOrPut(sessionId) {
            TerminalInventory(
                currentGeneration = { connectionManager.generation },
                load = {
                    val api = connectionManager.connectedApi ?: throw TerminalListOffline()
                    api.terminalList(sessionId)
                },
            )
        }
    }
}

/** The Host refused `terminal/list`; [message] is the Host-provided explanation. */
internal class TerminalListFailure(val code: String, message: String) : Exception(message)

/** No connected Host can answer `terminal/list`. */
internal class TerminalListOffline : Exception("terminal list unavailable while disconnected")
