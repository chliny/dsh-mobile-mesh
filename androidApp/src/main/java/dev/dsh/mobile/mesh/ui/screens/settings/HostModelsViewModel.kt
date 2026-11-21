package dev.dsh.mobile.mesh.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.dsh.mobile.mesh.connection.ConnectionManager
import dev.dsh.mobile.mesh.core.wire.RpcResult
import dev.dsh.mobile.mesh.core.wire.dto.CredentialInfo
import dev.dsh.mobile.mesh.core.wire.dto.LlmConfigurableProvider
import dev.dsh.mobile.mesh.core.wire.dto.LlmDiscoveredModel
import dev.dsh.mobile.mesh.core.wire.dto.LlmModelDiscoveryRequest
import dev.dsh.mobile.mesh.core.wire.dto.SettingsDescribeValue
import dev.dsh.mobile.mesh.core.wire.dto.SettingsNamespaceView
import dev.dsh.mobile.mesh.core.wire.dto.SettingsPathOpView
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal const val PI_AI = "llm-pi-ai"
private const val ACCOUNT_PROVIDER = "deepseek-account"
internal val providerRoutePattern = Regex("[a-z][a-z0-9]*(?:-[a-z0-9]+)*")
internal fun JsonElement?.modelSettingAt(path: List<String>): JsonElement? =
    path.fold(this) { value, key -> (value as? JsonObject)?.get(key) }
internal fun JsonObject?.modelString(field: String): String? = (this?.get(field) as? JsonPrimitive)
    ?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
internal fun modelCredentialRef(provider: String): String = provider.uppercase(Locale.ROOT)
    .replace(Regex("[^A-Z0-9]+"), "_") + "_API_KEY"
internal fun modelEndpointValid(value: String): Boolean = value.toHttpUrlOrNull()?.let {
    it.username.isEmpty() && it.password.isEmpty()
} == true

internal fun SettingsNamespaceView.providerPathIsAddressable(path: List<String>): Boolean =
    (0..path.size).all { depth ->
        val prefix = path.take(depth)
        listOfNotNull(user.modelSettingAt(prefix) as? JsonObject, base.modelSettingAt(prefix) as? JsonObject)
            .none { it.containsKey("__jsExpr") }
    }

/** Read data-only catalog choices from the owning adapter's schema. */
internal class ModelSettingsSchema(schema: JsonElement) {
    private val envelope = schema as? JsonObject
    private val refs = envelope?.get("refs") as? JsonObject
    private fun resolve(value: JsonElement?): JsonObject? = when (value) {
        is JsonObject -> value
        is JsonPrimitive -> refs?.get(value.content) as? JsonObject
        else -> null
    }
    private val root = if (refs != null) resolve(envelope?.get("uid")) else envelope
    fun field(path: List<String>): JsonObject? = path.fold(root) { node, key ->
        when (node.modelString("type")) {
            "object" -> resolve((node?.get("dict") as? JsonObject)?.get(key))
            "dict", "array" -> resolve(node?.get("inner"))
            else -> null
        }
    }
    fun protocols(path: List<String>) = choices(field(path + "api"))
    fun reasoning(path: List<String>): List<String> = branches(field(path + listOf("models", "0", "reasoningEfforts")))
        .firstOrNull { it.modelString("type") == "dict" }?.let { choices(resolve(it["sKey"])) }.orEmpty()
    private fun branches(node: JsonObject?) = (node?.get("list") as? JsonArray).orEmpty().mapNotNull(::resolve)
    private fun choices(node: JsonObject?) = branches(node).mapNotNull { it.modelString("value") }
}

internal data class HostModelProvider(
    val entry: LlmConfigurableProvider,
    val namespace: SettingsNamespaceView?,
    val live: Boolean,
    val credential: CredentialInfo?,
) {
    val profile = namespace?.value.modelSettingAt(entry.settingsPath) as? JsonObject
    val raw = namespace?.user.modelSettingAt(entry.settingsPath) as? JsonObject
    val inherited = namespace?.base.modelSettingAt(entry.settingsPath) as? JsonObject
    val keyRef = profile.modelString("apiKeyEnv") ?: modelCredentialRef(entry.provider)
    val editable = namespace != null && entry.provider != ACCOUNT_PROVIDER &&
        entry.settingsNs in setOf("llm-deepseek", PI_AI) && namespace.providerPathIsAddressable(entry.settingsPath)
    val removable = editable && entry.settingsPath.isNotEmpty() && raw != null && inherited == null
    val clearableCredential = editable && credential?.configured == true && credential.writable && credential.source == "file"
}

internal data class HostModelsState(
    val settings: SettingsDescribeValue? = null,
    val providers: List<HostModelProvider> = emptyList(),
    val loading: Boolean = false,
    val busy: Boolean = false,
    val failure: String? = null,
    val discovered: List<LlmDiscoveredModel>? = null,
) {
    val customNamespace get() = settings.namespace(PI_AI)
}

@HiltViewModel
internal class HostModelsViewModel @Inject constructor(private val manager: ConnectionManager) : ViewModel() {
    private val mutable = MutableStateFlow(HostModelsState())
    val state = mutable.asStateFlow()
    private val operations = Mutex()
    private var loaded: HostSettingsConnection? = null

    init {
        viewModelScope.launch { manager.connectedGenerations.collect { refresh() } }
        viewModelScope.launch { manager.state.collect { if (loaded?.isCurrent(manager) == false) clear() } }
    }

    private fun clear() { loaded = null; mutable.value = HostModelsState() }
    fun refresh() { viewModelScope.launch { operations.withLock { read() } } }

    private suspend fun read(failure: String? = null) {
        val connected = manager.hostSettingsConnection() ?: run { clear(); return }
        mutable.update { it.copy(loading = true, failure = failure) }
        val (settings, configurable, running) = coroutineScope {
            val settings = async { connected.api.settingsDescribe() }
            val configurable = async { connected.api.llmListConfigurableProviders() }
            val running = async { connected.api.llmListProviders() }
            Triple(settings.await(), configurable.await(), running.await())
        }
        if (!connected.isCurrent(manager)) { clear(); return }
        val catalogFailure = (settings as? RpcResult.Err) ?: (configurable as? RpcResult.Err)
        if (catalogFailure != null) {
            loaded = null
            mutable.value = HostModelsState(failure = failure ?: catalogFailure.describeFailure())
            return
        }
        val remote = (settings as RpcResult.Ok).value
        val descriptors = (configurable as RpcResult.Ok).value
        val live = (running as? RpcResult.Ok)?.value.orEmpty().mapTo(hashSetOf()) { it.id }
        val refs = descriptors.filter { it.provider != ACCOUNT_PROVIDER }.map { entry ->
            (remote.namespace(entry.settingsNs)?.value.modelSettingAt(entry.settingsPath) as? JsonObject)
                .modelString("apiKeyEnv") ?: modelCredentialRef(entry.provider)
        }.distinct()
        val credentials = buildMap {
            for (batch in refs.chunked(64)) {
                if (!connected.isCurrent(manager)) break
                when (val result = connected.api.credentialsDescribe(batch)) {
                    is RpcResult.Ok -> putAll(result.value)
                    is RpcResult.Err -> break
                }
            }
        }
        if (!connected.isCurrent(manager)) { clear(); return }
        loaded = connected
        mutable.value = HostModelsState(settings = remote, providers = descriptors.map { entry ->
            val namespace = remote.namespace(entry.settingsNs)
            val row = HostModelProvider(entry, namespace, entry.provider in live, null)
            row.copy(credential = credentials[row.keyRef])
        }, failure = failure ?: (running as? RpcResult.Err)?.describeFailure())
    }

    fun clearDiscovery() { mutable.update { it.copy(discovered = null, failure = null) } }

    fun discover(namespace: SettingsNamespaceView, provider: HostModelProvider?, baseURL: String?, protocol: String?, key: String?) {
        perform(namespace, provider, write = false) { connection ->
            val original = provider?.profile.modelString("baseURL") ?: provider?.entry?.defaultBaseURL
            if (baseURL != null && !modelEndpointValid(baseURL)) return@perform
            // Do not attach an existing route's credential or headers to a newly typed endpoint.
            val knownRoute = provider?.entry?.provider?.takeIf { baseURL == null || baseURL == original }
            val result = connection.api.llmDiscoverModels(namespace.ns,
                LlmModelDiscoveryRequest(knownRoute, baseURL, protocol, key))
            if (!connection.isCurrent(manager)) return@perform
            when (result) {
                is RpcResult.Ok -> mutable.update { it.copy(discovered = result.value) }
                is RpcResult.Err -> mutable.update { it.copy(failure = result.error.code) }
            }
        }
    }

    fun saveProvider(namespace: SettingsNamespaceView, provider: HostModelProvider?, route: String,
        updates: Map<String, JsonElement?>, key: String?,
    ) {
        perform(namespace, provider) { connection ->
            val path = provider?.entry?.settingsPath ?: listOf("providers", route)
            val ref = provider?.keyRef ?: modelCredentialRef(route)
            if (provider == null && (!providerRoutePattern.matches(route) || namespace.ns != PI_AI ||
                    !namespace.providerPathIsAddressable(path) || mutable.value.providers.any { it.entry.provider == route } ||
                    namespace.value.modelSettingAt(path) != null)
            ) return@perform
            if (provider != null && !provider.editable) return@perform
            val fields = updates.toMutableMap()
            if (key != null && namespace.ns == PI_AI && provider?.profile.modelString("apiKeyEnv") == null) {
                fields["apiKeyEnv"] = JsonPrimitive(ref)
            }
            if (key != null) {
                val info = connection.api.credentialsDescribe(listOf(ref))
                if ((info as? RpcResult.Ok)?.value?.get(ref)?.writable != true || !connection.isCurrent(manager)) {
                    mutable.update { it.copy(failure = "credentials/denied") }
                    return@perform
                }
            }
            val ops = when {
                provider == null || (provider.profile == null && provider.raw == null) ->
                    listOf(SettingsPathOpView.Set(path, JsonObject(fields.filterValues { it != null }.mapValues { requireNotNull(it.value) })))
                else -> fields.mapNotNull { (field, value) ->
                    if (provider.raw?.get(field) == value) null
                    else if (value == null) SettingsPathOpView.Unset(path + field)
                    else SettingsPathOpView.Set(path + field, value)
                }
            }
            if (ops.isNotEmpty()) {
                val result = connection.api.settingsMutate(namespace.ns, ops, namespace.revision)
                if (result is RpcResult.Err) { read(if (key == null) result.describeFailure() else result.error.code); return@perform }
            }
            if (!connection.isCurrent(manager)) return@perform
            val stored = key?.let { connection.api.credentialsSet(ref, it) }
            if (connection.isCurrent(manager)) read((stored as? RpcResult.Err)?.error?.code)
        }
    }

    fun removeProvider(provider: HostModelProvider) {
        val namespace = provider.namespace ?: return
        perform(namespace, provider) { connection ->
            if (!provider.removable) return@perform
            val result = connection.api.settingsMutate(namespace.ns,
                listOf(SettingsPathOpView.Unset(provider.entry.settingsPath)), namespace.revision)
            if (connection.isCurrent(manager)) read((result as? RpcResult.Err)?.describeFailure())
        }
    }

    fun clearCredential(provider: HostModelProvider) {
        val namespace = provider.namespace ?: return
        perform(namespace, provider) { connection ->
            if (!provider.clearableCredential) return@perform
            val result = connection.api.credentialsUnset(provider.keyRef)
            if (connection.isCurrent(manager)) read((result as? RpcResult.Err)?.error?.code)
        }
    }

    private fun perform(namespace: SettingsNamespaceView, provider: HostModelProvider?, write: Boolean = true,
        block: suspend (HostSettingsConnection) -> Unit,
    ) {
        val expected = loaded ?: return
        viewModelScope.launch { operations.withLock {
            val state = mutable.value
            if (loaded !== expected || !expected.isCurrent(manager) || state.settings.namespace(namespace.ns) !== namespace ||
                (write && state.settings?.writable != true) ||
                (provider != null && state.providers.none { it === provider })) return@withLock
            mutable.update { it.copy(busy = true, failure = null, discovered = null) }
            try { block(expected) } finally {
                if (expected.isCurrent(manager)) mutable.update { it.copy(busy = false) } else clear()
            }
        } }
    }
}
