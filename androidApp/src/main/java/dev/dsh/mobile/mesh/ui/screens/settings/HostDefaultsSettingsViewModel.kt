package dev.dsh.mobile.mesh.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.dsh.mobile.mesh.connection.ConnectionManager
import dev.dsh.mobile.mesh.core.wire.RpcResult
import dev.dsh.mobile.mesh.core.wire.dto.AgentPresetDocument
import dev.dsh.mobile.mesh.core.wire.dto.AgentPresetEntry
import dev.dsh.mobile.mesh.core.wire.dto.SettingsDescribeValue
import dev.dsh.mobile.mesh.core.wire.dto.SettingsPathOpView
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private const val PERMISSION_NS = "permission"
private const val PRESET_NS = "agent-preset-registry"

data class HostPermissionOption(val value: String, val name: String, val description: String?)

data class HostDefaultsSettingsUiState(
    val permissionOptions: List<HostPermissionOption> = emptyList(),
    val defaultPermission: String? = null,
    val agentPresets: List<AgentPresetEntry> = emptyList(),
    val selectedAgentPreset: String? = null,
    val agentPresetDocument: AgentPresetDocument? = null,
    val permissionWritable: Boolean = false,
    internal val permissionConfirmationKey: String? = null,
    val agentPresetWritable: Boolean = false,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

@HiltViewModel
class HostDefaultsSettingsViewModel @Inject constructor(private val manager: ConnectionManager) : ViewModel() {
    private val _state = MutableStateFlow(HostDefaultsSettingsUiState())
    val state = _state.asStateFlow()
    private var loaded: HostSettingsConnection? = null
    private var describe: SettingsDescribeValue? = null
    private val requests = Mutex()

    init {
        viewModelScope.launch { manager.connectedGenerations.collect { refresh() } }
        viewModelScope.launch { manager.state.collect { if (loaded?.isCurrent(manager) == false) clear() } }
    }

    private fun clear() {
        loaded = null
        describe = null
        _state.value = HostDefaultsSettingsUiState()
    }

    fun refresh() { viewModelScope.launch { requests.withLock { load() } } }

    private suspend fun load(previousError: String? = null) {
        val connection = manager.hostSettingsConnection() ?: run { clear(); return }
        _state.update { it.copy(loading = true, error = previousError, agentPresetDocument = null) }
        val api = connection.api
        val settings = api.settingsDescribe()
        val catalog = api.permissionPresetsCatalog()
        val roster = api.agentPresetList()
        if (!connection.isCurrent(manager)) { clear(); return }
        loaded = connection
        val remoteSettings = (settings as? RpcResult.Ok)?.value
        describe = remoteSettings
        val choices = (catalog as? RpcResult.Ok)?.value as? JsonObject
        val permissionNamespace = remoteSettings.namespace(PERMISSION_NS)
        val registry = remoteSettings.namespace(PRESET_NS)
        val presets = (roster as? RpcResult.Ok)?.value?.presets.orEmpty()
        val options = (choices?.get("defaultOptions") as? JsonArray).orEmpty().mapNotNull { json ->
            val option = json as? JsonObject ?: return@mapNotNull null
            val value = option.string("value") ?: return@mapNotNull null
            HostPermissionOption(value, option.string("name") ?: value, option.string("description"))
        }
        _state.value = HostDefaultsSettingsUiState(
            permissionOptions = options,
            defaultPermission = permissionNamespace?.stringValue("defaultPreset") ?: choices?.string("defaultPreset"),
            agentPresets = presets,
            selectedAgentPreset = presets.firstOrNull { it.isDefault }?.id ?: registry?.stringValue("selectedDefault"),
            permissionWritable = remoteSettings?.writable == true && permissionNamespace != null && options.isNotEmpty(),
            permissionConfirmationKey = permissionNamespace?.let { "${connection.generation.clientId}:${it.revision}" },
            agentPresetWritable = remoteSettings?.writable == true && registry != null && roster is RpcResult.Ok,
            error = previousError ?: (settings as? RpcResult.Err)?.describeFailure(),
        )
    }

    fun setDefaultPermission(value: String, acceptFullAccess: Boolean = false, confirmationKey: String? = null) {
        if (value == "danger-full-access" && (!acceptFullAccess || confirmationKey == null ||
                confirmationKey != _state.value.permissionConfirmationKey)
        ) return
        change(PERMISSION_NS, "defaultPreset", value) { it.permissionWritable && it.permissionOptions.any { option -> option.value == value } }
    }

    fun selectDefaultAgentPreset(id: String) {
        change(PRESET_NS, "selectedDefault", id) { it.agentPresetWritable && it.agentPresets.any { preset -> preset.id == id && preset.broken == null } }
    }

    private fun change(ns: String, field: String, value: String, allowed: (HostDefaultsSettingsUiState) -> Boolean) {
        val expected = loaded ?: return
        val namespace = describe.namespace(ns) ?: return
        viewModelScope.launch {
            requests.withLock {
                if (loaded !== expected || !expected.isCurrent(manager) || describe.namespace(ns) !== namespace ||
                    !allowed(_state.value)
                ) return@withLock
                _state.update { it.copy(busy = true, error = null) }
                val result = expected.api.settingsMutate(ns, listOf(SettingsPathOpView.Set(listOf(field), JsonPrimitive(value))), namespace.revision)
                if (expected.isCurrent(manager)) load((result as? RpcResult.Err)?.describeFailure()) else clear()
            }
        }
    }

    fun readAgentPreset(id: String) {
        val connection = loaded ?: return
        viewModelScope.launch {
            requests.withLock {
                if (!connection.isCurrent(manager) || _state.value.agentPresets.none { it.id == id }) return@withLock
                _state.update { it.copy(busy = true, error = null, agentPresetDocument = null) }
                val result = connection.api.agentPresetRead(id)
                if (!connection.isCurrent(manager)) { clear(); return@withLock }
                _state.update { it.copy(busy = false, agentPresetDocument = (result as? RpcResult.Ok)?.value,
                    error = (result as? RpcResult.Err)?.describeFailure()) }
            }
        }
    }

    fun clearAgentPresetDocument() { _state.update { it.copy(agentPresetDocument = null) } }
    fun clearMessage() { _state.update { it.copy(error = null, message = null) } }

    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
}
