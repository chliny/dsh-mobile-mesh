package dev.dsh.mobile.mesh.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.dsh.mobile.mesh.R
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.dsh.mobile.mesh.connection.ConnectionManager
import dev.dsh.mobile.mesh.connection.HostConfig
import dev.dsh.mobile.mesh.connection.harnessEndpointKey
import dev.dsh.mobile.mesh.core.wire.HostGeneration
import dev.dsh.mobile.mesh.core.wire.RpcResult
import dev.dsh.mobile.mesh.core.wire.dto.PluginManagerBundleInfo
import dev.dsh.mobile.mesh.core.wire.dto.PluginManagerChangeResult
import dev.dsh.mobile.mesh.core.wire.dto.PluginManagerPluginInfo
import dev.dsh.mobile.mesh.core.wire.dto.PluginManagerInstallOptions
import dev.dsh.mobile.mesh.core.wire.dto.PluginManagerSpecInspection
import dev.dsh.mobile.mesh.core.wire.dto.PluginManagerVersionList
import dev.dsh.mobile.mesh.core.wire.dto.PluginManagerVersionExemptions
import dev.dsh.mobile.mesh.core.wire.dto.SettingsDescribeValue
import dev.dsh.mobile.mesh.core.wire.dto.SettingsNamespaceView
import dev.dsh.mobile.mesh.core.wire.dto.SettingsPathOpView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject
import java.util.UUID

/** Every read and write uses the live DSH API, which already carries SSH/Mesh relay and identity. */
data class PluginManagerUiState(
    val bundles: List<PluginManagerBundleInfo> = emptyList(),
    val plugins: List<PluginManagerPluginInfo> = emptyList(),
    val settings: SettingsDescribeValue? = null,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val inspection: PluginManagerSpecInspection? = null,
    val inspectionSpec: String? = null,
    val pendingBuilds: List<String> = emptyList(),
    val pendingInstallSpec: String? = null,
    val activeInstallRequestId: String? = null,
    val versionList: PluginManagerVersionList? = null,
    val versionListBundleName: String? = null,
    val versionExemptions: PluginManagerVersionExemptions? = null,
    internal val target: Pair<String, String>? = null,
)

@HiltViewModel
class PluginManagerViewModel @Inject constructor(
    private val connectionManager: ConnectionManager,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _state = MutableStateFlow(PluginManagerUiState())
    val state: StateFlow<PluginManagerUiState> = combine(_state, connectionManager.state) { snapshot, connection ->
        if (snapshot.target == connection.host?.targetKey()) snapshot else PluginManagerUiState()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PluginManagerUiState())
    private val operations = Mutex()
    private var hostLoaded = false
    private var settingsGeneration: HostGeneration? = null

    private fun HostConfig.targetKey() = id to harnessEndpointKey()
    private fun currentTarget() = connectionManager.state.value.host?.targetKey()
    private fun sameHost(): Boolean = hostLoaded && _state.value.target == currentTarget() &&
        connectionManager.connectedApi != null

    fun refresh() {
        viewModelScope.launch {
            operations.withLock {
                hostLoaded = false
                _state.value = PluginManagerUiState(loading = true, target = currentTarget())
                loadFromHost()
                _state.update { it.copy(loading = false) }
            }
        }
    }

    fun toggleBundle(bundle: PluginManagerBundleInfo) {
        if (bundle.readOnlyReason != null) return
        change { connectionManager.connectedApi?.setPluginManagerBundleEnabled(bundle.name, !bundle.enabled) }
    }

    fun togglePlugin(plugin: PluginManagerPluginInfo) {
        if (plugin.patchId == null || plugin.readOnlyReason != null) return
        change { connectionManager.connectedApi?.setPluginManagerPluginEnabled(plugin.entryId, !plugin.enabled) }
    }

    fun removeBundle(bundle: PluginManagerBundleInfo) {
        if (!bundle.removable || bundle.readOnlyReason != null) return
        change { connectionManager.connectedApi?.removePluginManagerBundle(bundle.name) }
    }

    fun loadVersions(name: String) {
        viewModelScope.launch {
            operations.withLock {
                if (!sameHost()) {
                    _state.update { it.copy(error = context.getString(R.string.plugins_native_connection_unavailable)) }
                    return@withLock
                }
                _state.update { it.copy(busy = true, error = null, versionList = null, versionListBundleName = name) }
                when (val result = connectionManager.connectedApi?.listPluginManagerVersions(name)) {
                    is RpcResult.Ok -> _state.update { it.copy(versionList = result.value) }
                    is RpcResult.Err -> _state.update { it.copy(error = "${result.error.code}: ${result.error.message}") }
                    null -> _state.update { it.copy(error = context.getString(R.string.plugins_native_connection_unavailable)) }
                }
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun updateBundle(bundle: PluginManagerBundleInfo, version: String, registry: String? = null) {
        if (!bundle.updatable || bundle.readOnlyReason != null) return
        viewModelScope.launch {
            operations.withLock {
                if (!sameHost()) {
                    _state.update { it.copy(error = context.getString(R.string.plugins_native_connection_unavailable)) }
                    return@withLock
                }
                val listing = _state.value.versionList?.takeIf {
                    _state.value.versionListBundleName == bundle.name && it.status == "listed"
                }
                val target = listing?.versions?.firstOrNull { it.version == version && it.bundle && it.incompatible == null }
                if (target == null) {
                    _state.update { it.copy(error = context.getString(R.string.plugins_native_config_changed)) }
                    return@withLock
                }
                val requestId = UUID.randomUUID().toString()
                _state.update { it.copy(busy = true, error = null, message = null,
                    activeInstallRequestId = requestId) }
                val selectedRegistry = registry ?: listing.registry
                when (val result = connectionManager.connectedApi?.updatePluginManagerBundle(
                    bundle.name, version, selectedRegistry, requestId,
                    usePnpmRegistry = selectedRegistry == null,
                )) {
                    is RpcResult.Ok -> {
                        val update = result.value
                        val restore = update.restore
                        _state.update { it.copy(message = listOfNotNull(
                            describeChange(update.result),
                            restore?.let { outcome ->
                                when (outcome.application) {
                                    "applied", "restart-required" -> context.getString(R.string.plugins_native_update_restored)
                                    else -> context.getString(R.string.plugins_native_update_restore_failed)
                                }
                            },
                        ).joinToString(" · "), versionList = null, versionListBundleName = null) }
                        loadFromHost()
                    }
                    is RpcResult.Err -> {
                        _state.update { it.copy(error = "${result.error.code}: ${result.error.message} · " +
                            context.getString(R.string.plugins_native_update_unknown)) }
                        loadFromHost()
                    }
                    null -> _state.update { it.copy(error = context.getString(R.string.plugins_native_connection_unavailable)) }
                }
                _state.update { it.copy(busy = false, activeInstallRequestId = null) }
            }
        }
    }

    fun setVersionExemption(packageVersion: String, runtimeVersion: String, enabled: Boolean, acceptRisk: Boolean = false) {
        if (packageVersion.isBlank() || runtimeVersion.isBlank() || (enabled && !acceptRisk)) return
        change { connectionManager.connectedApi?.setPluginManagerVersionExemption(
            packageVersion, runtimeVersion, enabled, acceptRisk = enabled && acceptRisk,
        ) }
    }

    fun inspectBundle(spec: String) {
        val target = spec.trim()
        if (target.isEmpty()) return
        viewModelScope.launch {
            operations.withLock {
                if (!sameHost()) {
                    _state.update { it.copy(error = context.getString(R.string.plugins_native_connection_unavailable)) }
                    return@withLock
                }
                _state.update { it.copy(busy = true, error = null, inspection = null, inspectionSpec = null,
                    pendingBuilds = emptyList(), pendingInstallSpec = null) }
                when (val result = connectionManager.connectedApi?.inspectPluginManagerBundle(target)) {
                    is RpcResult.Ok -> {
                        if (sameHost()) {
                            _state.update { it.copy(inspection = result.value, inspectionSpec = target) }
                        }
                    }
                    is RpcResult.Err -> _state.update { it.copy(error = "${result.error.code}: ${result.error.message}") }
                    null -> _state.update { it.copy(error = context.getString(R.string.plugins_native_connection_unavailable)) }
                }
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun installBundle(spec: String, approvedBuilds: List<String> = emptyList()) {
        val target = spec.trim()
        if (target.isEmpty()) return
        viewModelScope.launch {
            operations.withLock {
                val current = _state.value
                val confirmed = current.inspection
                if (!sameHost() ||
                    current.inspectionSpec != target || confirmed?.status != "accepted" ||
                    confirmed.bundle == false) {
                    _state.update { it.copy(error = context.getString(R.string.plugins_native_inspection_changed)) }
                    return@withLock
                }
                if (approvedBuilds.isNotEmpty() && (current.pendingInstallSpec != target ||
                    approvedBuilds.distinct().size != approvedBuilds.size ||
                    approvedBuilds.any { it !in current.pendingBuilds }
                )) {
                    _state.update { it.copy(error = context.getString(R.string.plugins_native_config_changed)) }
                    return@withLock
                }
                _state.update { it.copy(busy = true, error = null, message = null,
                    pendingInstallSpec = null, pendingBuilds = emptyList()) }
                val api = connectionManager.connectedApi
                if (api == null) {
                    _state.update { it.copy(busy = false, error = context.getString(R.string.plugins_native_connection_unavailable)) }
                    return@withLock
                }
                when (val inspection = api.inspectPluginManagerBundle(target)) {
                    is RpcResult.Err -> {
                        _state.update { it.copy(busy = false, error = "${inspection.error.code}: ${inspection.error.message}") }
                        return@withLock
                    }
                    is RpcResult.Ok -> if (inspection.value.status != "accepted" || inspection.value != current.inspection) {
                        _state.update { it.copy(busy = false, inspection = null, inspectionSpec = null,
                            error = context.getString(R.string.plugins_native_inspection_changed)) }
                        return@withLock
                    }
                }
                if (!sameHost()) {
                    _state.update { it.copy(busy = false, error = context.getString(R.string.plugins_native_connection_unavailable)) }
                    return@withLock
                }
                val requestId = UUID.randomUUID().toString()
                _state.update { it.copy(activeInstallRequestId = requestId) }
                val exact = confirmed.kind == "registry" && confirmed.name != null && confirmed.version != null
                val installSpec = if (exact) "${confirmed.name}@${confirmed.version}" else target
                val installed = api.installPluginManagerBundle(installSpec, PluginManagerInstallOptions(
                    enabled = false, saveExact = true.takeIf { exact }, requestId = requestId,
                    approvedBuilds = approvedBuilds.takeIf { it.isNotEmpty() },
                    registry = confirmed.registry, usePnpmRegistry = confirmed.registry == null,
                ))
                val result = if (installed is RpcResult.Err && installed.error.code == "internal") {
                    // A reconnect may replace the SSH/Mesh relay. Look up the request on the
                    // current client for this same target; never submit installBundle again.
                    val recovery = connectionManager.connectedApi.takeIf { currentTarget() == current.target }
                    val recovered = recovery?.waitForPluginManagerInstall(requestId)
                    if (recovered is RpcResult.Ok) recovered else {
                        val reconnected = withTimeoutOrNull(15_000L) {
                            connectionManager.state.first {
                                it.host?.targetKey() != current.target ||
                                    connectionManager.connectedApi?.let { client -> client !== recovery } == true
                            }
                            connectionManager.connectedApi.takeIf { currentTarget() == current.target && it !== recovery }
                        }
                        reconnected?.waitForPluginManagerInstall(requestId) ?: recovered ?: installed
                    }
                } else installed
                when (result) {
                    is RpcResult.Ok -> {
                        val outcome = result.value
                        if (outcome == null) {
                            _state.update { it.copy(error = context.getString(R.string.plugins_native_install_unknown)) }
                        } else {
                            _state.update { it.copy(
                                message = describeChange(outcome),
                                pendingBuilds = outcome.pendingBuilds,
                                pendingInstallSpec = target.takeIf { outcome.pendingBuilds.isNotEmpty() },
                            ) }
                            loadFromHost()
                        }
                    }
                    is RpcResult.Err -> _state.update { it.copy(error =
                        "${result.error.code}: ${result.error.message}" +
                            (if (installed is RpcResult.Err && installed.error.code == "internal")
                                " · ${context.getString(R.string.plugins_native_install_unknown)}" else "")) }
                }
                _state.update { it.copy(activeInstallRequestId = null, busy = false) }
            }
        }
    }

    /** Cancellation must bypass the install mutex: installBundle is itself waiting for the Host. */
    fun cancelInstall() {
        val snapshot = _state.value
        val requestId = snapshot.activeInstallRequestId ?: return
        viewModelScope.launch {
            if (snapshot.target != currentTarget()) return@launch
            val result = connectionManager.connectedApi?.cancelPluginManagerInstall(requestId)
            if (_state.value.activeInstallRequestId != requestId || currentTarget() != snapshot.target) return@launch
            when (result) {
                is RpcResult.Ok -> _state.update { it.copy(message = context.getString(when (result.value.status) {
                    "cancelled" -> R.string.plugins_native_install_cancelled
                    "too-late" -> R.string.plugins_native_install_cancel_too_late
                    else -> R.string.plugins_native_install_cancel_not_running
                })) }
                is RpcResult.Err -> _state.update { it.copy(error = "${result.error.code}: ${result.error.message}") }
                null -> _state.update { it.copy(error = context.getString(R.string.plugins_native_connection_unavailable)) }
            }
        }
    }

    private fun describeChange(change: PluginManagerChangeResult): String = listOfNotNull(
        when (change.application) {
            "applied" -> context.getString(R.string.plugins_native_status_applied)
            "failed" -> context.getString(R.string.plugins_native_status_failed)
            "overridden" -> context.getString(R.string.plugins_native_status_overridden)
            "restart-required" -> context.getString(R.string.plugins_native_status_restart)
            "cancelled" -> context.getString(R.string.plugins_native_install_cancelled)
            else -> change.application
        },
        change.restartReason,
        change.error?.diagnostic ?: change.error?.code,
        change.packageResult?.kind,
        *change.warnings.toTypedArray(),
    ).joinToString(" · ")

    private fun change(request: suspend () -> RpcResult<PluginManagerChangeResult>?) {
        viewModelScope.launch {
            operations.withLock {
                if (!sameHost()) {
                    _state.update { it.copy(error = context.getString(R.string.plugins_native_connection_unavailable)) }
                    return@withLock
                }
                _state.update { it.copy(busy = true, error = null, message = null) }
                when (val result = request()) {
                    is RpcResult.Ok -> {
                        val change = result.value
                        _state.update { it.copy(message = describeChange(change)) }
                        loadFromHost()
                    }
                    is RpcResult.Err -> _state.update { it.copy(error = "${result.error.code}: ${result.error.message}") }
                    null -> _state.update { it.copy(error = context.getString(R.string.plugins_native_connection_unavailable)) }
                }
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /** A single path edit preserves the remaining fields and never resubmits redacted secrets. */
    fun updateSetting(namespace: SettingsNamespaceView, path: List<String>, value: JsonElement) =
        mutateSetting(namespace, SettingsPathOpView.Set(path, value))

    fun resetSetting(namespace: SettingsNamespaceView, path: List<String>) =
        mutateSetting(namespace, SettingsPathOpView.Unset(path))

    private fun mutateSetting(namespace: SettingsNamespaceView, op: SettingsPathOpView) {
        viewModelScope.launch {
            operations.withLock {
                _state.update { it.copy(busy = true, error = null, message = null) }
                val api = connectionManager.connectedApi
                val current = _state.value.settings?.namespaces?.firstOrNull { it.ns == namespace.ns }
                // A restarted Host can reuse a settings revision from its previous process.
                if (!sameHost() || api == null || current !== namespace || settingsGeneration == null ||
                    settingsGeneration !== connectionManager.generation ||
                    _state.value.settings?.writable != true
                ) {
                    _state.update { it.copy(error = context.getString(R.string.plugins_native_config_changed)) }
                } else {
                    when (val result = api.settingsMutate(namespace.ns, listOf(op), namespace.revision)) {
                        is RpcResult.Ok -> {
                            _state.update { state ->
                                state.copy(settings = state.settings?.copy(namespaces = state.settings.namespaces.map {
                                    if (it.ns == namespace.ns) result.value else it
                                }), message = context.getString(R.string.plugins_native_status_applied))
                            }
                            loadFromHost()
                        }
                        is RpcResult.Err -> {
                            _state.update { it.copy(error = "${result.error.code}: ${result.error.message}") }
                            // A conflict requires a fresh snapshot and a new explicit user action.
                            val fresh = api.settingsDescribe()
                            if (fresh is RpcResult.Ok && sameHost() &&
                                settingsGeneration === connectionManager.generation && api === connectionManager.connectedApi
                            ) _state.update { it.copy(settings = fresh.value) }
                        }
                    }
                }
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private suspend fun loadFromHost() {
        hostLoaded = false
        settingsGeneration = null
        val generation = connectionManager.generation
        val target = _state.value.target
        val unavailable = context.getString(R.string.plugins_native_connection_unavailable)
        fun clearSnapshot(error: String) {
            _state.update { it.copy(bundles = emptyList(), plugins = emptyList(), settings = null,
                versionExemptions = null, inspection = null, inspectionSpec = null,
                pendingBuilds = emptyList(), pendingInstallSpec = null,
                versionList = null, versionListBundleName = null, error = error) }
        }
        val api = connectionManager.connectedApi
        if (api == null || target == null || target != currentTarget()) {
            clearSnapshot(unavailable)
            return
        }
        val bundles = api.listPluginManagerBundles()
        if (bundles is RpcResult.Err) {
            clearSnapshot("${bundles.error.code}: ${bundles.error.message}")
            return
        }
        val plugins = api.listPluginManagerPlugins()
        val settings = api.settingsDescribe()
        val exemptions = api.listPluginManagerVersionExemptions()
        if (target != currentTarget() || api !== connectionManager.connectedApi || generation !== connectionManager.generation) {
            clearSnapshot(unavailable)
            return
        }
        hostLoaded = true
        settingsGeneration = generation
        _state.update { it.copy(
            bundles = (bundles as RpcResult.Ok).value,
            plugins = (plugins as? RpcResult.Ok)?.value.orEmpty(),
            settings = (settings as? RpcResult.Ok)?.value,
            versionExemptions = (exemptions as? RpcResult.Ok)?.value,
            error = it.error ?: (plugins as? RpcResult.Err)?.error?.let { error -> "${error.code}: ${error.message}" },
        ) }
    }

    fun clearMessage() { _state.update { it.copy(message = null, error = null) } }
}
