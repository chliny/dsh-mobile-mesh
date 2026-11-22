package dev.dsh.mobile.mesh.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.core.os.ConfigurationCompat
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.core.wire.dto.PluginManagerBundleInfo
import dev.dsh.mobile.mesh.core.wire.dto.PluginManagerPluginInfo
import dev.dsh.mobile.mesh.core.wire.dto.SettingsNamespaceView
import dev.dsh.mobile.mesh.ui.components.DsIconButton
import dev.dsh.mobile.mesh.ui.theme.DsTheme
import dev.dsh.mobile.mesh.ui.theme.DsSpacing
import dev.dsh.mobile.mesh.ui.theme.DsType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.util.Locale

// Stock templates and standalone tools are also excluded from the Web experimental directory.
private val WEB_HIDDEN_BUNDLES = setOf(
    "@deepseek-ai/dsh-base", "@deepseek-ai/dsh-web-app", "@deepseek-ai/dsh-headless",
    "@deepseek-ai/dsh-sdk-app", "@deepseek-ai/dsh-acp-app", "@deepseek-ai/dsh-sdk-minimal",
    "@deepseek-ai/dsh-experimental-terminal-bundle", "@deepseek-ai/dsh-experimental-tool-worktree",
)

/** Entry target shared with the settings page, which now hosts the directory links. */
internal enum class PluginDirectory { BuiltIn, Experimental }

/** The Web resolves package and plugin text through the active language's declared fallback chain. */
internal fun JsonElement?.localizedMetaText(field: String, locale: Locale): String? {
    fun JsonElement?.text() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    return when (val value = (this as? JsonObject)?.get(field)) {
        is JsonObject -> value[locale.toLanguageTag().lowercase(Locale.ROOT)].text()
            ?: value[locale.language.lowercase(Locale.ROOT)].text()
            ?: value["en"].text()
        else -> value.text()
    }
}

private fun PluginManagerBundleInfo.localizedMeta(field: String, locale: Locale): String? =
    meta.localizedMetaText(field, locale)

/** Server-owned management state; toggles and settings edits never optimistically change the UI. */
@Composable
internal fun PluginManagerScreen(onClose: () -> Unit, initialDirectory: PluginDirectory? = null, viewModel: PluginManagerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var directory by remember { mutableStateOf(initialDirectory) }
    val experimentalOpen = directory == PluginDirectory.Experimental
    val builtInOpen = directory == PluginDirectory.BuiltIn
    var searchQuery by remember { mutableStateOf("") }
    var experimentalQuery by remember { mutableStateOf("") }
    var builtInQuery by remember { mutableStateOf("") }
    BackHandler(enabled = directory != null) { directory = null }
    val activeQuery = when (directory) {
        PluginDirectory.Experimental -> experimentalQuery
        PluginDirectory.BuiltIn -> builtInQuery
        null -> searchQuery
    }
    val search = activeQuery.trim().lowercase(Locale.ROOT)
    // The Web experimental directory is the visible official catalog, not its beta badge set.
    // Project it for display only; enablement, eligibility and settings still come from the Host.
    val bundles = remember(state.bundles, state.plugins, experimentalOpen) {
        if (!experimentalOpen) state.bundles else {
            val running = state.plugins.filter { it.enabled }.mapTo(hashSetOf()) { it.entryId }
            state.bundles.filter { bundle ->
                bundle.official && bundle.name !in WEB_HIDDEN_BUNDLES &&
                    (bundle.listedByDefault != false || bundle.enabled || bundle.installed ||
                        bundle.rows.any { it.entryId?.let(running::contains) == true })
            }
        }
    }
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.ENGLISH
    val shownBundles = remember(bundles, search, experimentalOpen, locale) {
        if (search.isEmpty()) bundles else bundles.filter { bundle ->
            val description = if (experimentalOpen) bundle.localizedMeta("description", locale) else bundle.description
            bundle.name.lowercase(Locale.ROOT).contains(search) ||
                description?.lowercase(Locale.ROOT)?.contains(search) == true ||
                (experimentalOpen && bundle.localizedMeta("title", locale)?.lowercase(Locale.ROOT)?.contains(search) == true) ||
                bundle.rows.any { it.moduleName.lowercase(Locale.ROOT).contains(search) }
        }
    }
    val shownPlugins = remember(state.plugins, search) {
        if (search.isEmpty()) state.plugins else state.plugins.filter { plugin ->
            plugin.moduleName.lowercase(Locale.ROOT).contains(search) ||
                plugin.entryId.lowercase(Locale.ROOT).contains(search)
        }
    }
    var suggestedInstall by remember { mutableStateOf<Pair<Int, String>?>(null) }
    val listState = rememberLazyListState()
    val experimentalListState = rememberLazyListState()
    val builtInListState = rememberLazyListState()
    val activeListState = when (directory) {
        PluginDirectory.Experimental -> experimentalListState
        PluginDirectory.BuiltIn -> builtInListState
        null -> listState
    }
    val title = when (directory) {
        PluginDirectory.Experimental -> R.string.plugins_native_experimental
        PluginDirectory.BuiltIn -> R.string.plugins_native_builtin
        null -> R.string.plugins_native_title
    }
    val builtInCards = if (builtInOpen) builtInPluginCards(state.settings?.namespaces.orEmpty(), search) else emptyList()
    LaunchedEffect(suggestedInstall) {
        if (suggestedInstall != null) listState.animateScrollToItem(0)
    }
    val colors = DsTheme.colors
    LaunchedEffect(viewModel) { viewModel.refresh() }
    Surface(Modifier.fillMaxSize(), color = colors.bgBase) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = DsSpacing.comfortable).padding(top = DsSpacing.medium),
                verticalAlignment = Alignment.CenterVertically) {
                DsIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back), onClick = {
                    if (directory != null) directory = null else onClose()
                })
                Text(stringResource(title), Modifier.weight(1f), style = DsType.large20)
                TextButton(onClick = viewModel::refresh, enabled = !state.busy && !state.loading) {
                    Text(stringResource(R.string.plugins_native_refresh))
                }
            }
            if (directory == null) TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.plugins_native_bundles)) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.plugins_native_plugins)) })
            }
            OutlinedTextField(
                value = activeQuery,
                onValueChange = {
                    when (directory) {
                        PluginDirectory.Experimental -> experimentalQuery = it
                        PluginDirectory.BuiltIn -> builtInQuery = it
                        null -> searchQuery = it
                    }
                },
                label = { Text(stringResource(R.string.plugins_native_search), style = DsType.std14) },
                textStyle = DsType.std14,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = DsSpacing.comfortable, vertical = DsSpacing.small),
            )
            if (state.loading) Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator()
                Text(stringResource(R.string.plugins_native_loading), Modifier.padding(start = 12.dp))
            }
            state.message?.let { message -> Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(message, Modifier.weight(1f))
                TextButton(onClick = viewModel::clearMessage) { Text(stringResource(R.string.plugins_native_dismiss)) }
            } }
            state.error?.let { Text(it, Modifier.padding(12.dp), color = colors.error) }
            LazyColumn(Modifier.fillMaxSize(), state = activeListState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = DsSpacing.comfortable, end = DsSpacing.comfortable,
                    top = DsSpacing.small, bottom = DsSpacing.xlarge,
                ),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.comfortable)) {
                if (builtInOpen) {
                    if (builtInCards.isEmpty() && !state.loading) item {
                        Text(stringResource(if (search.isEmpty()) R.string.plugins_native_empty_builtin else R.string.plugins_native_search_empty),
                            color = colors.labelSecondary)
                    }
                    items(builtInCards, key = { it.kind.name }) { card ->
                        BuiltInPluginCard(card, state.busy || state.loading, state.settings?.writable == true,
                            viewModel, builtInCardPlugins(card, state.bundles, state.plugins), locale)
                    }
                } else if (tab == 0 || experimentalOpen) {
                    if (!experimentalOpen) item {
                        SettingsCard(stringResource(R.string.plugins_native_install_section)) {
                            Text(stringResource(R.string.plugins_native_install_section_hint),
                                style = DsType.small13, color = colors.labelSecondary)
                            PluginInstallControls(state.busy || state.loading, state.activeInstallRequestId, suggestedInstall,
                                state.pendingBuilds, state.pendingInstallSpec,
                                state.inspection, state.inspectionSpec, viewModel::inspectBundle,
                                viewModel::installBundle, viewModel::cancelInstall)
                        }
                    }
                    if (shownBundles.isEmpty() && !state.loading) item {
                        Text(stringResource(when {
                            search.isNotEmpty() -> R.string.plugins_native_search_empty
                            experimentalOpen -> R.string.plugins_native_empty_experimental
                            else -> R.string.plugins_native_empty_bundles
                        }), color = colors.labelSecondary)
                    }
                    items(shownBundles, key = { it.name }) { bundle ->
                        val rowEntries = bundle.rows.mapNotNull { it.entryId }.toSet()
                        BundleCard(bundle, state.busy || state.loading, { viewModel.toggleBundle(bundle) },
                            state.plugins.filter { it.entryId in rowEntries }, locale,
                            { viewModel.togglePlugin(it) },
                            state.settings?.namespaces.orEmpty().filter { ns -> bundle.rows.any { it.entryId != null && it.rowId == ns.ns } },
                            state.settings?.writable == true, viewModel::updateSetting, viewModel::resetSetting,
                            title = if (experimentalOpen) bundle.localizedMeta("title", locale) ?: bundle.name else bundle.name,
                            description = if (experimentalOpen) bundle.localizedMeta("description", locale)?.takeIf { it.isNotEmpty() }
                                else bundle.description,
                        ) {
                            bundle.installTarget?.takeIf {
                                bundle.availability == "missing" && !bundle.installed &&
                                    bundle.readOnlyReason == null && bundle.error?.code != "unsupported-platform"
                            }?.let { target ->
                                OutlinedButton(onClick = {
                                    directory = null
                                    suggestedInstall = ((suggestedInstall?.first ?: 0) + 1) to target.spec
                                }, enabled = !state.busy && !state.loading) {
                                    Text(stringResource(R.string.plugins_native_install))
                                }
                            }
                            val versions = state.versionList.takeIf { state.versionListBundleName == bundle.name }
                            PluginBundleOperations(bundle.name, bundle.version,
                                bundle.removable && bundle.readOnlyReason == null,
                                bundle.updatable && bundle.readOnlyReason == null,
                                state.busy || state.loading, state.busy && versions == null,
                                versions?.reason ?: versions?.problem ?: state.error.takeIf {
                                    versions == null && state.versionListBundleName == bundle.name
                                },
                                versions?.versions?.takeIf { versions.status == "listed" }?.map {
                                    PluginVersionChoice(it.version, it.bundle,
                                        it.incompatible?.let { issue -> "${issue.name}@${issue.version}" },
                                        it.incompatible?.runtimeVersion, it.deprecated)
                                }, versions?.registry,
                                { viewModel.loadVersions(bundle.name) }, { viewModel.removeBundle(bundle) },
                                { version, registry -> viewModel.updateBundle(bundle, version, registry) })
                        }
                    }
                    if (!experimentalOpen) item {
                        PluginExemptionControls(state.versionExemptions?.exemptions.orEmpty(),
                            state.versionExemptions?.warnings.orEmpty(), state.versionExemptions != null,
                            state.busy || state.loading,
                            viewModel::setVersionExemption)
                    }
                } else {
                    if (shownPlugins.isEmpty() && !state.loading) item {
                        Text(stringResource(if (search.isEmpty()) R.string.plugins_native_empty_plugins
                            else R.string.plugins_native_search_empty), color = colors.labelSecondary)
                    }
                    items(shownPlugins, key = { it.entryId }) { plugin ->
                        PluginCard(plugin, plugin.meta.localizedMetaText("title", locale) ?: plugin.moduleName,
                            state.settings?.namespaces?.firstOrNull { it.ns == (plugin.patchId ?: plugin.entryId) },
                            state.settings?.writable == true, state.busy || state.loading,
                            { viewModel.togglePlugin(plugin) },
                            viewModel::updateSetting, viewModel::resetSetting)
                    }
                }
            }
        }
    }
}

/**
 * A built-in group owns fixed settings slots; its plugins are the rows that publish those slots.
 * Older Hosts may omit bundle rows, so a plugin whose patch id names a slot also belongs.
 */
private fun builtInCardPlugins(card: BuiltInPluginSettings, bundles: List<PluginManagerBundleInfo>,
    plugins: List<PluginManagerPluginInfo>,
): List<PluginManagerPluginInfo> {
    val slots = card.sections.map { it.ns }.toSet()
    val rowEntries = bundles.flatMap { it.rows }
        .mapNotNull { row -> row.entryId?.takeIf { row.rowId in slots } }
        .toSet()
    return plugins.filter { plugin -> plugin.entryId in rowEntries || (plugin.patchId ?: plugin.entryId) in slots }
}

@Composable
private fun BundleCard(bundle: PluginManagerBundleInfo, busy: Boolean, onToggle: () -> Unit,
    plugins: List<PluginManagerPluginInfo>, locale: Locale,
    onTogglePlugin: (PluginManagerPluginInfo) -> Unit,
    namespaces: List<SettingsNamespaceView>, writable: Boolean,
    onUpdate: (SettingsNamespaceView, List<String>, JsonElement) -> Unit,
    onReset: (SettingsNamespaceView, List<String>) -> Unit,
    title: String,
    description: String?,
    operations: @Composable () -> Unit,
) {
    val colors = DsTheme.colors
    var open by remember(bundle.name) { mutableStateOf(false) }
    SettingsBlock {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            Text(title, Modifier.weight(1f), style = DsType.std14Strong)
            // The Host's authoritative action for the package stays next to its name; opening it
            // reveals the contained plugins, each with its own switch, then the package settings.
            OutlinedButton(onClick = { open = !open }) { Text(stringResource(R.string.plugins_native_settings)) }
            Switch(checked = bundle.enabled, onCheckedChange = { onToggle() },
                modifier = Modifier.semantics { contentDescription = title },
                enabled = !busy && bundle.readOnlyReason == null &&
                    (bundle.enabled || (bundle.availability != "missing" && bundle.error == null)))
        }
        description?.let { Text(it, color = colors.labelSecondary) }
        Text(stringResource(if (bundle.installed || bundle.availability == "installation") R.string.plugins_native_installed
            else R.string.plugins_native_not_installed), color = colors.labelSecondary)
        bundle.version?.let { Text(it, color = colors.labelSecondary) }
        bundle.readOnlyReason?.let { Text(it, color = colors.labelSecondary) }
        bundle.error?.let { Text(it.diagnostic ?: it.code, color = colors.error) }
        operations()
        if (bundle.overrides.isNotEmpty()) {
            Text(stringResource(R.string.plugins_native_status_overridden), color = colors.warnLabel)
            bundle.overrides.forEach { Text(it, color = colors.labelSecondary) }
        }
        if (open) {
            plugins.forEach { plugin ->
                val pluginTitle = plugin.meta.localizedMetaText("title", locale) ?: plugin.moduleName
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(pluginTitle, style = DsType.std14)
                        plugin.readOnlyReason?.let { Text(it, style = DsType.caption11, color = colors.labelSecondary) }
                        plugin.diagnostic?.let { Text(it, style = DsType.caption11, color = colors.error) }
                    }
                    Switch(checked = plugin.enabled, onCheckedChange = { onTogglePlugin(plugin) },
                        modifier = Modifier.semantics { contentDescription = pluginTitle },
                        enabled = !busy && plugin.patchId != null && plugin.readOnlyReason == null)
                }
            }
            namespaces.forEach { namespace ->
                Text(namespace.ns, color = colors.labelSecondary)
                SchemaFields(namespace, busy || !writable, onUpdate, onReset)
            }
        }
    }
}

@Composable
private fun PluginCard(plugin: PluginManagerPluginInfo, title: String, namespace: SettingsNamespaceView?, writable: Boolean,
    busy: Boolean, onToggle: () -> Unit,
    onUpdate: (SettingsNamespaceView, List<String>, JsonElement) -> Unit,
    onReset: (SettingsNamespaceView, List<String>) -> Unit,
) {
    val colors = DsTheme.colors
    var open by remember(plugin.entryId) { mutableStateOf(false) }
    SettingsBlock {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = DsType.std14Strong)
                Text(plugin.entryId, style = DsType.caption11, color = colors.labelSecondary)
            }
            Switch(checked = plugin.enabled, onCheckedChange = { onToggle() },
                modifier = Modifier.semantics { contentDescription = title },
                enabled = !busy && plugin.patchId != null && plugin.readOnlyReason == null)
        }
        plugin.readOnlyReason?.let { Text(it, color = colors.labelSecondary) }
        plugin.fiberPhase?.let { Text(it.name, color = colors.labelSecondary) }
        plugin.diagnostic?.let { Text(it, color = colors.error) }
        if (namespace != null) {
            OutlinedButton(onClick = { open = !open }) { Text(stringResource(R.string.plugins_native_settings)) }
            if (open) SchemaFields(namespace, busy || !writable, onUpdate, onReset)
        }
    }
}

/** Resolve the data-only subset of Schemastery's schema; never evaluate Host callbacks. */
private class SettingsSchema(schema: JsonElement) {
    private val envelope = schema as? JsonObject
    private val refs = envelope?.get("refs") as? JsonObject
    val root = resolve(if (refs != null) envelope?.get("uid") else envelope)

    fun resolve(node: JsonElement?): JsonObject? = when (node) {
        is JsonPrimitive -> refs?.get(node.content) as? JsonObject
        is JsonObject -> node
        else -> null
    }

    /** These wrappers can mark the same path secret without marking their own node. */
    fun mayRedactValue(node: JsonObject?): Boolean {
        val visited = mutableSetOf<JsonObject>()
        fun walk(field: JsonObject?): Boolean {
            if (field == null || !visited.add(field)) return false
            val meta = field["meta"] as? JsonObject
            if ((meta?.get("role") as? JsonPrimitive)?.contentOrNull == "secret") return true
            return when ((field["type"] as? JsonPrimitive)?.contentOrNull) {
                "union", "intersect" -> (field["list"] as? JsonArray).orEmpty().any { walk(resolve(it)) }
                "transform" -> walk(resolve(field["inner"]))
                else -> false
            }
        }
        return walk(node)
    }
}

/** Schemastery uses `dict` for object children and `inner` for array/dictionary elements. */
@Composable
internal fun SchemaFields(namespace: SettingsNamespaceView, busy: Boolean,
    onUpdate: (SettingsNamespaceView, List<String>, JsonElement) -> Unit,
    onReset: (SettingsNamespaceView, List<String>) -> Unit,
    hiddenFields: Set<String> = emptySet(),
    allowedFields: Set<String>? = null,
    labels: Map<String, Int> = emptyMap(),
) {
    val schema = remember(namespace.schema) { SettingsSchema(namespace.schema) }
    val fields = schema.root?.get("dict") as? JsonObject
    if ((schema.root?.get("type") as? JsonPrimitive)?.contentOrNull == "object" && !fields.isNullOrEmpty()) {
        fields.forEach { (key, field) ->
            if (key !in hiddenFields && (allowedFields == null || key in allowedFields)) {
                SettingField(namespace, listOf(key), schema, field,
                    (namespace.value as? JsonObject)?.get(key), busy, onUpdate, onReset,
                    atomicCollection = namespace.ns == SUBAGENT_MODEL_SETTINGS && key == "allowedModels",
                    titleOverride = labels[key]?.let { stringResource(it) })
            }
        }
    } else Text(stringResource(R.string.plugins_native_no_settings), color = DsTheme.colors.labelSecondary)
}

@Composable
private fun SettingField(namespace: SettingsNamespaceView, path: List<String>, schema: SettingsSchema,
    field: JsonElement, value: JsonElement?, busy: Boolean,
    onUpdate: (SettingsNamespaceView, List<String>, JsonElement) -> Unit,
    onReset: (SettingsNamespaceView, List<String>) -> Unit,
    collection: String? = null,
    atomicCollection: Boolean = false,
    titleOverride: String? = null,
) {
    val node = schema.resolve(field) ?: return
    // Host schema references may be recursive, including where there is no configured value.
    if (path.size > 32) return
    val colors = DsTheme.colors
    val type = (node["type"] as? JsonPrimitive)?.contentOrNull
    val meta = node["meta"] as? JsonObject
    val title = titleOverride ?: (node["title"] as? JsonPrimitive)?.contentOrNull ?: path.last()
    val description = (meta?.get("description") as? JsonPrimitive)?.contentOrNull
        ?: (node["description"] as? JsonPrimitive)?.contentOrNull
    val secret = namespace.secrets.any { it.path == path } ||
        (meta?.get("role") as? JsonPrimitive)?.contentOrNull == "secret" ||
        (node["writeOnly"] as? JsonPrimitive)?.booleanOrNull == true
    // Composite JSON can contain redacted slots. Never expose or round-trip those values
    // through an editor; independently addressed children remain editable below.
    val hasSecretDescendant = namespace.secrets.any {
        it.path.size > path.size && it.path.take(path.size) == path
    }
    fun atPath(root: JsonElement?) = path.fold(root) { parent, segment ->
        when (parent) {
            is JsonObject -> parent[segment]
            is JsonArray -> segment.toIntOrNull()?.let(parent::getOrNull)
            else -> null
        }
    }
    val rawOverride = atPath(namespace.user)
    // The Host evaluates inherited values before sending them. Without a matching raw override,
    // writing below an inherited container can silently edit a key inside a raw !!js expression.
    val hasExpression = rawOverride is JsonObject && rawOverride.size == 1 &&
        (rawOverride["__jsExpr"] as? JsonPrimitive)?.isString == true
    val canEditChildren = !secret && !hasExpression &&
        (atPath(namespace.base) == null || (rawOverride != null && rawOverride == value))
    val childFields = if (type == "object" && canEditChildren) node["dict"] as? JsonObject else null
    val inner = schema.resolve(node["inner"])
    // In a dictionary of secrets the Host also strips a raw __jsExpr key. Matching redacted
    // objects no longer prove that an existing dictionary can be written by child path.
    val opaqueSecretDictionary = type == "dict" && schema.mayRedactValue(inner) &&
        (rawOverride != null || atPath(namespace.base) != null || hasSecretDescendant)
    val isDictionary = type == "dict" && canEditChildren && inner != null && !opaqueSecretDictionary
    val dictionary = value as? JsonObject
    val array = value as? JsonArray
    // Only send index ops when the raw override and displayed list agree.
    val isArray = !atomicCollection && type == "array" && !secret && inner != null && array != null && array == rawOverride
    // Direct secret dictionary entries can be absent from value, but their keys are still editable.
    val dictionaryKeys = if (isDictionary) dictionary.orEmpty().keys + namespace.secrets.mapNotNull {
        it.path.getOrNull(path.size)?.takeIf { _ -> it.path.take(path.size) == path }
    } else emptySet()
    // Every leaf takes the key: value row, including JSON-shaped ones; containers keep their
    // nested children but their own actions live on the same header row.
    val simpleValue = secret || type == "string" || type == "number" || type == "integer" || type == "boolean"
    if (childFields == null && !isDictionary && !isArray) {
        val sensitiveValue = when {
            secret -> stringResource(if (namespace.secrets.any { it.path == path && it.set })
                R.string.plugins_native_secret_set else R.string.plugins_native_secret_unset)
            opaqueSecretDictionary -> stringResource(R.string.plugins_native_read_only)
            hasSecretDescendant -> stringResource(R.string.plugins_native_inherited_secret_setting)
            else -> null
        }
        CompactSettingField(namespace, path, title, description, type, value, sensitiveValue, secret, busy,
            complex = !simpleValue,
            canEdit = !opaqueSecretDictionary && !hasSecretDescendant,
            canReset = !opaqueSecretDictionary && (collection != null || !hasSecretDescendant),
            resetLabel = R.string.plugins_native_reset,
            onUpdate = { onUpdate(namespace, path, it) }, onReset = { onReset(namespace, path) })
        return
    }
    var adding by remember(namespace.ns, path, namespace.revision) { mutableStateOf(false) }
    val canReset = !opaqueSecretDictionary && (collection != null || (!hasSecretDescendant && childFields == null))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = DsType.small13Strong)
            if (isDictionary || isArray) {
                SettingRowButton(R.string.plugins_native_modify, title, enabled = !busy) { adding = true }
            }
            if (canReset) SettingRowButton(R.string.plugins_native_reset, title, enabled = !busy) {
                onReset(namespace, path)
            }
        }
        description?.let { Text(it, color = colors.labelSecondary) }
        if (childFields != null) {
            childFields.forEach { (key, child) ->
                SettingField(namespace, path + key, schema, child, (value as? JsonObject)?.get(key), busy, onUpdate, onReset)
            }
        } else if (inner != null && isDictionary) {
            dictionaryKeys.forEach { key ->
                SettingField(namespace, path + key, schema, inner, dictionary?.get(key), busy, onUpdate, onReset,
                    collection = "dict")
            }
        } else if (inner != null && isArray) {
            array.orEmpty().forEachIndexed { index, childValue ->
                SettingField(namespace, path + index.toString(), schema, inner, childValue, busy, onUpdate, onReset,
                    collection = "array")
            }
        }
    }
    if (adding && inner != null && (isDictionary || isArray)) {
        val childType = (inner["type"] as? JsonPrimitive)?.contentOrNull
        val childSecret = ((inner["meta"] as? JsonObject)?.get("role") as? JsonPrimitive)?.contentOrNull == "secret"
        SettingEditor(namespace, path, stringResource(R.string.plugins_native_add_entry), childType, null, childSecret,
            dictionaryKeys = dictionaryKeys.takeIf { isDictionary }, onDismiss = { adding = false },
            onSave = { key, added ->
                onUpdate(namespace, path + (key ?: array.orEmpty().size.toString()), added)
                adding = false
            })
    }
}

@Composable
private fun CompactSettingField(
    namespace: SettingsNamespaceView, path: List<String>, title: String, description: String?,
    type: String?, value: JsonElement?, sensitiveValue: String?, secret: Boolean, busy: Boolean,
    complex: Boolean, canEdit: Boolean, canReset: Boolean, resetLabel: Int,
    onUpdate: (JsonElement) -> Unit, onReset: () -> Unit,
) {
    var editing by remember(namespace.ns, path, namespace.revision) { mutableStateOf(false) }
    var input by remember(namespace.ns, path, namespace.revision) { mutableStateOf("") }
    val toggle = type == "boolean" && !secret && sensitiveValue == null
    val parsed = if (editing) remember(input, type, secret) { parseSettingInput(input, type, secret) } else null
    val colors = DsTheme.colors
    fun dismiss() { editing = false; input = "" }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
            verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
                verticalAlignment = Alignment.CenterVertically) {
                Text("$title:", Modifier.weight(1f), style = DsType.small13)
                if (!toggle) {
                    val display = sensitiveValue ?: (value as? JsonPrimitive)?.contentOrNull ?: value?.toString() ?: "—"
                    Text(display, Modifier.weight(1.3f), style = DsType.small13, color = colors.labelSecondary)
                }
            }
            if (toggle) Switch(
                checked = (value as? JsonPrimitive)?.booleanOrNull == true,
                modifier = Modifier.semantics { contentDescription = title },
                onCheckedChange = { onUpdate(JsonPrimitive(it)) }, enabled = !busy,
            )
            if (!toggle && canEdit) {
                SettingRowButton(if (editing) R.string.plugins_native_save else R.string.plugins_native_modify, title,
                    enabled = !busy && (!editing || parsed != null)) {
                    if (editing) parsed?.let { edited -> dismiss(); onUpdate(edited) }
                    else {
                        input = settingInputValue(type, value, secret)
                        editing = true
                    }
                }
            }
            if (canReset) SettingRowButton(resetLabel, title, enabled = !busy) { dismiss(); onReset() }
        }
        description?.let { Text(it, style = DsType.caption11, color = colors.labelSecondary) }
        if (editing) AlertDialog(
            onDismissRequest = ::dismiss,
            title = { Text(title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                    OutlinedTextField(value = input, onValueChange = { input = it }, enabled = !busy,
                        label = { Text(if (secret) stringResource(R.string.plugins_native_secret_placeholder) else title) },
                        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                        minLines = if (complex) 4 else 1, maxLines = if (complex) 12 else 1,
                        modifier = Modifier.fillMaxWidth(), textStyle = DsType.std14)
                    if (parsed == null) Text(stringResource(R.string.plugins_native_invalid_json), color = colors.error)
                }
            },
            confirmButton = {
                Button(onClick = { parsed?.let { edited -> dismiss(); onUpdate(edited) } }, enabled = !busy && parsed != null) {
                    Text(stringResource(R.string.plugins_native_save))
                }
            },
            dismissButton = {
                TextButton(onClick = ::dismiss) { Text(stringResource(R.string.plugins_native_cancel)) }
            },
        )
    }
}

@Composable
internal fun SettingRowButton(label: Int, field: String, enabled: Boolean, onClick: () -> Unit) {
    val action = stringResource(label)
    OutlinedButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.semantics { contentDescription = "$action: $field" },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = DsSpacing.small, vertical = DsSpacing.tiny),
    ) { Text(action, style = DsType.small13) }
}

private fun settingInputValue(type: String?, value: JsonElement?, secret: Boolean): String = when {
    secret -> ""
    type == "string" -> (value as? JsonPrimitive)?.contentOrNull.orEmpty()
    else -> value?.toString().orEmpty()
}

private fun parseSettingInput(input: String, type: String?, secret: Boolean): JsonElement? = when {
    secret || type == "string" -> JsonPrimitive(input)
    else -> runCatching { Json.parseToJsonElement(input) }.getOrNull()?.takeIf { element ->
        if (type == "number" || type == "integer") {
            val primitive = element as? JsonPrimitive
            primitive != null && !primitive.isString && (if (type == "integer") primitive.content.toLongOrNull() != null
                else primitive.content.toDoubleOrNull() != null)
        } else true
    }
}

@Composable
private fun SettingEditor(namespace: SettingsNamespaceView, path: List<String>, title: String,
    type: String?, value: JsonElement?, secret: Boolean, dictionaryKeys: Set<String>? = null,
    onDismiss: () -> Unit, onSave: (String?, JsonElement) -> Unit,
) {
    var keyInput by remember(namespace.ns, path, namespace.revision) { mutableStateOf("") }
    val key = keyInput.takeIf { dictionaryKeys != null }
    val keyValid = dictionaryKeys == null || (keyInput.isNotBlank() && keyInput !in dictionaryKeys)
    val complex = type != "string" && type != "number" && type != "integer"
    var input by remember(namespace.ns, path, namespace.revision) { mutableStateOf(settingInputValue(type, value, secret)) }
    val parsed = remember(input, type, secret) { parseSettingInput(input, type, secret) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (dictionaryKeys != null) OutlinedTextField(
                value = keyInput, onValueChange = { keyInput = it }, singleLine = true,
                label = { Text(stringResource(R.string.plugins_native_entry_key)) },
                isError = keyInput in dictionaryKeys, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(value = input, onValueChange = { input = it },
                label = { Text(if (secret) stringResource(R.string.plugins_native_secret_placeholder) else title) },
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                minLines = if (complex && !secret) 4 else 1,
                maxLines = if (complex && !secret) 12 else 1, modifier = Modifier.fillMaxWidth())
            if (parsed == null) Text(stringResource(R.string.plugins_native_invalid_json), color = DsTheme.colors.error)
        }
    }, confirmButton = {
        Button(onClick = { parsed?.let { onSave(key, it) } }, enabled = parsed != null && keyValid) {
            Text(stringResource(R.string.plugins_native_save))
        }
    }, dismissButton = {
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.plugins_native_cancel)) }
    })
}
