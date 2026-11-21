package dev.dsh.mobile.mesh.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.dsh.mobile.mesh.connection.ConnectionManager
import dev.dsh.mobile.mesh.core.wire.DshApiClient
import dev.dsh.mobile.mesh.core.wire.HostGeneration
import dev.dsh.mobile.mesh.core.wire.RpcResult
import dev.dsh.mobile.mesh.core.wire.dto.SettingsDescribeValue
import dev.dsh.mobile.mesh.core.wire.dto.SettingsNamespaceView
import dev.dsh.mobile.mesh.core.wire.dto.SettingsPathOpView
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A revision identifies a settings snapshot only within its connected Host generation. */
internal data class HostSettingsConnection(val api: DshApiClient, val generation: HostGeneration) {
    fun isCurrent(manager: ConnectionManager): Boolean = manager.connectedApi === api && manager.generation === generation
}

internal fun ConnectionManager.hostSettingsConnection(): HostSettingsConnection? {
    val api = connectedApi ?: return null
    val generation = generation ?: return null
    return HostSettingsConnection(api, generation).takeIf { it.isCurrent(this) }
}

internal fun SettingsDescribeValue?.namespace(id: String): SettingsNamespaceView? = this?.namespaces?.firstOrNull { it.ns == id }
internal fun SettingsNamespaceView.stringValue(key: String): String? =
    (value as? JsonObject)?.get(key)?.let { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }

internal fun RpcResult.Err.describeFailure(): String = "${error.code}: ${error.message}"

internal data class HostPreferencesState(
    val settings: SettingsDescribeValue? = null,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
internal class HostPreferencesViewModel @Inject constructor(
    private val manager: ConnectionManager,
) : ViewModel() {
    private val _state = MutableStateFlow(HostPreferencesState())
    val state = _state.asStateFlow()
    private var loaded: HostSettingsConnection? = null
    private val requests = Mutex()

    init {
        viewModelScope.launch { manager.connectedGenerations.collect { refresh() } }
        viewModelScope.launch {
            manager.state.collect {
                if (loaded?.isCurrent(manager) == false) {
                    loaded = null
                    _state.value = HostPreferencesState()
                }
            }
        }
    }

    fun refresh() = viewModelScope.launch { requests.withLock { load() } }

    private suspend fun load(previousError: String? = null) {
        val connection = manager.hostSettingsConnection()
        if (connection == null) {
            loaded = null
            _state.value = HostPreferencesState(error = previousError)
            return
        }
        _state.update { it.copy(loading = true, error = previousError) }
        val result = connection.api.settingsDescribe()
        if (!connection.isCurrent(manager)) {
            loaded = null
            _state.value = HostPreferencesState()
            return
        }
        loaded = connection
        _state.value = when (result) {
            is RpcResult.Ok -> HostPreferencesState(settings = result.value, error = previousError)
            is RpcResult.Err -> HostPreferencesState(error = previousError ?: result.describeFailure())
        }
    }

    fun set(namespace: SettingsNamespaceView, field: String, value: JsonPrimitive) = viewModelScope.launch {
        requests.withLock {
            val connection = loaded
            val snapshot = _state.value.settings
            if (connection == null || !connection.isCurrent(manager) || snapshot?.writable != true ||
                snapshot.namespace(namespace.ns) !== namespace
            ) {
                load()
                return@withLock
            }
            _state.update { it.copy(busy = true, error = null) }
            val result = connection.api.settingsMutate(
                namespace.ns, listOf(SettingsPathOpView.Set(listOf(field), value)), namespace.revision,
            )
            if (connection.isCurrent(manager)) load((result as? RpcResult.Err)?.describeFailure())
            else { loaded = null; _state.value = HostPreferencesState() }
        }
    }
}
