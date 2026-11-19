package dev.dsh.mobile.mesh.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.core.wire.dto.PluginManagerSpecInspection
import dev.dsh.mobile.mesh.ui.theme.DsTheme

/** Only a confirmed dialog invokes a mutation; no installation implicitly approves scripts. */
@Composable
internal fun PluginInstallControls(busy: Boolean, activeInstallRequestId: String?,
    suggestedSpec: Pair<Int, String>?, pendingBuilds: List<String>, pendingInstallSpec: String?,
    inspection: PluginManagerSpecInspection?, inspectionSpec: String?,
    onInspect: (String) -> Unit, onInstall: (String, List<String>) -> Unit, onCancel: () -> Unit,
) {
    var spec by remember { mutableStateOf("") }
    LaunchedEffect(suggestedSpec) { suggestedSpec?.let { spec = it.second } }
    var confirmInstall by remember { mutableStateOf(false) }
    val accepted = inspection?.takeIf { inspectionSpec == spec.trim() && it.status == "accepted" && it.bundle != false }
    val notBundle = inspection?.takeIf { inspectionSpec == spec.trim() && it.status == "accepted" && it.bundle == false }
    val refused = inspection?.takeIf { inspectionSpec == spec.trim() && it.status == "refused" }
    val pending = pendingBuilds.takeIf { pendingInstallSpec != null && it.isNotEmpty() }.orEmpty()
    var selected by remember(pendingInstallSpec, pending) { mutableStateOf(emptySet<String>()) }
    var confirmScripts by remember(pendingInstallSpec, pending) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = spec, onValueChange = { spec = it; confirmInstall = false },
            label = { Text(stringResource(R.string.plugins_native_install_spec)) },
            singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
        OutlinedButton(onClick = { onInspect(spec.trim()) }, enabled = !busy && spec.trim().isNotEmpty()) {
            Text(stringResource(R.string.plugins_native_inspect))
        }
        refused?.let { Text(stringResource(R.string.plugins_native_inspect_refused, it.reason ?: it.problem.orEmpty()),
            color = DsTheme.colors.error) }
        if (notBundle != null) Text(stringResource(R.string.plugins_native_versions_not_bundle),
            color = DsTheme.colors.error)
        accepted?.let { result ->
            result.name?.let { Text(stringResource(R.string.plugins_native_inspect_name, it)) }
            result.version?.let { Text(stringResource(R.string.plugins_native_inspect_version, it)) }
            Text(stringResource(R.string.plugins_native_inspect_source, result.registry ?: result.kind.orEmpty()))
            result.host?.let { Text(stringResource(R.string.plugins_native_inspect_host, it)) }
            if (result.name == null || result.version == null) {
                Text(stringResource(R.string.plugins_native_inspect_unknown), color = DsTheme.colors.warnLabel)
            }
            OutlinedButton(onClick = { confirmInstall = true }, enabled = !busy) {
                Text(stringResource(R.string.plugins_native_install))
            }
        }
        if (activeInstallRequestId != null) {
            Text(stringResource(R.string.plugins_native_install_busy))
            OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.plugins_native_install_cancel)) }
        }
        if (pending.isNotEmpty()) {
            Text(stringResource(R.string.plugins_native_builds_title))
            Text(stringResource(R.string.plugins_native_builds_body))
            pending.forEach { name ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = name in selected, enabled = !busy,
                        onCheckedChange = { checked -> selected = if (checked) selected + name else selected - name })
                    Text(name, Modifier.weight(1f))
                }
            }
            Text(stringResource(R.string.plugins_native_builds_caution), color = DsTheme.colors.warnLabel)
            OutlinedButton(onClick = { confirmScripts = true }, enabled = !busy && selected.isNotEmpty()) {
                Text(stringResource(R.string.plugins_native_builds_approve_retry))
            }
        }
    }
    if (confirmInstall && accepted != null) {
        val confirmedSpec = spec.trim()
        val name = accepted.name ?: confirmedSpec
        val version = accepted.version ?: "—"
        val confirmation = if (accepted.kind == "registry") {
            stringResource(R.string.plugins_native_inspect_registry_confirm_body, name, version,
                accepted.registry ?: stringResource(R.string.plugins_native_registry_pnpm))
        } else {
            stringResource(R.string.plugins_native_inspect_confirm_body, name, version,
                accepted.host ?: confirmedSpec)
        }
        ConfirmOperation(stringResource(R.string.plugins_native_install_confirm_title), confirmation,
            stringResource(R.string.plugins_native_install), { confirmInstall = false },
            { confirmInstall = false; if (!busy && inspectionSpec == confirmedSpec) onInstall(confirmedSpec, emptyList()) })
    }
    if (confirmScripts && pendingInstallSpec != null) {
        val approved = pending.filter { it in selected }
        ConfirmOperation(stringResource(R.string.plugins_native_builds_confirm_title),
            stringResource(R.string.plugins_native_builds_confirm_body, approved.joinToString(", "), pendingInstallSpec),
            stringResource(R.string.plugins_native_builds_approve_retry), { confirmScripts = false },
            { confirmScripts = false; if (approved.isNotEmpty()) onInstall(pendingInstallSpec, approved) })
    }
}

internal data class PluginVersionChoice(val version: String, val bundle: Boolean,
    val incompatiblePackageVersion: String?, val incompatibleRuntimeVersion: String?,
    val deprecated: String?) {
    val incompatible: Boolean get() = incompatiblePackageVersion != null
}

@Composable
internal fun PluginBundleOperations(name: String, installedVersion: String?, removable: Boolean,
    updatable: Boolean, busy: Boolean, versionsLoading: Boolean, versionsProblem: String?,
    versions: List<PluginVersionChoice>?, registry: String?,
    onLoadVersions: () -> Unit, onRemove: () -> Unit, onUpdate: (String, String?) -> Unit,
) {
    var removeConfirm by remember(name) { mutableStateOf(false) }
    var updateOpen by remember(name) { mutableStateOf(false) }
    var updateTarget by remember(name) { mutableStateOf<String?>(null) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (updatable) OutlinedButton(onClick = { updateTarget = null; updateOpen = true; onLoadVersions() }, enabled = !busy) {
            Text(stringResource(R.string.plugins_native_update))
        }
        if (removable) OutlinedButton(onClick = { removeConfirm = true }, enabled = !busy) {
            Text(stringResource(R.string.plugins_native_remove))
        }
    }
    if (removeConfirm) ConfirmOperation(stringResource(R.string.plugins_native_remove_confirm_title),
        stringResource(R.string.plugins_native_remove_confirm_body, name),
        stringResource(R.string.plugins_native_remove), { removeConfirm = false },
        { removeConfirm = false; onRemove() })
    if (updateOpen) AlertDialog(onDismissRequest = { updateOpen = false; updateTarget = null },
        title = { Text(stringResource(R.string.plugins_native_versions_title)) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    versionsLoading || versions == null && versionsProblem == null -> Text(stringResource(R.string.plugins_native_versions_loading))
                    versionsProblem != null -> Text(stringResource(R.string.plugins_native_versions_refused, versionsProblem), color = DsTheme.colors.error)
                    versions.isNullOrEmpty() -> Text(stringResource(R.string.plugins_native_versions_empty))
                    else -> versions.forEach { choice ->
                        Column {
                            OutlinedButton(onClick = { updateTarget = choice.version },
                                enabled = !busy && choice.bundle && !choice.incompatible) {
                                Text(if (updateTarget == choice.version) "✓ ${choice.version}" else choice.version)
                            }
                            if (!choice.bundle) Text(stringResource(R.string.plugins_native_versions_not_bundle))
                            if (choice.incompatible) {
                                Text(stringResource(R.string.plugins_native_versions_incompatible), color = DsTheme.colors.warnLabel)
                                Text("${choice.incompatiblePackageVersion} · DSH ${choice.incompatibleRuntimeVersion}",
                                    color = DsTheme.colors.labelSecondary)
                            }
                            choice.deprecated?.let { Text(stringResource(R.string.plugins_native_versions_deprecated, it), color = DsTheme.colors.warnLabel) }
                        }
                    }
                }
            }
        }, confirmButton = {
            Button(onClick = { updateOpen = false }, enabled = !busy && versions?.any {
                it.version == updateTarget && it.bundle && !it.incompatible
            } == true) {
                Text(stringResource(R.string.plugins_native_update))
            }
        }, dismissButton = { TextButton(onClick = { updateOpen = false; updateTarget = null }) {
            Text(stringResource(R.string.plugins_native_cancel))
        } })
    if (!updateOpen && updateTarget != null) {
        val target = updateTarget!!
        ConfirmOperation(stringResource(R.string.plugins_native_update_confirm_title),
            stringResource(R.string.plugins_native_update_confirm_versions, name, installedVersion.orEmpty(), target),
            stringResource(R.string.plugins_native_update), { updateTarget = null },
            { updateTarget = null; onUpdate(target, registry) })
    }
}

/** A grant is scoped to the exact pair and requires a fresh, explicit risk confirmation. */
@Composable
internal fun PluginExemptionControls(exemptions: Map<String, List<String>>, warnings: List<String>,
    available: Boolean, busy: Boolean, onChange: (String, String, Boolean, Boolean) -> Unit,
) {
    var packageVersion by remember { mutableStateOf("") }
    var runtimeVersion by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<Triple<String, String, Boolean>?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.plugins_native_exemptions))
        if (available && exemptions.isEmpty()) Text(stringResource(R.string.plugins_native_exemptions_empty))
        warnings.forEach { Text(it, color = DsTheme.colors.warnLabel) }
        exemptions.forEach { (pkg, runtimes) -> runtimes.forEach { runtime ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$pkg · DSH $runtime", Modifier.weight(1f))
                TextButton(onClick = { pending = Triple(pkg, runtime, false) }, enabled = !busy) {
                    Text(stringResource(R.string.plugins_native_exemption_revoke))
                }
            }
        } }
        OutlinedTextField(packageVersion, onValueChange = { packageVersion = it },
            label = { Text(stringResource(R.string.plugins_native_exemption_package_version)) },
            enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(runtimeVersion, onValueChange = { runtimeVersion = it },
            label = { Text(stringResource(R.string.plugins_native_exemption_runtime_version)) },
            enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { pending = Triple(packageVersion.trim(), runtimeVersion.trim(), true) },
            enabled = available && !busy && packageVersion.trim().isNotEmpty() && runtimeVersion.trim().isNotEmpty()) {
            Text(stringResource(R.string.plugins_native_exemption_grant))
        }
    }
    pending?.let { (pkg, runtime, grant) ->
        ConfirmOperation(stringResource(if (grant) R.string.plugins_native_exemption_grant_confirm_title
            else R.string.plugins_native_exemption_revoke_confirm_title),
            stringResource(if (grant) R.string.plugins_native_exemption_grant_confirm_body
                else R.string.plugins_native_exemption_revoke_confirm_body, pkg, runtime),
            stringResource(if (grant) R.string.plugins_native_exemption_grant
                else R.string.plugins_native_exemption_revoke),
            { pending = null }, { pending = null; onChange(pkg, runtime, grant, grant) })
    }
}

@Composable
private fun ConfirmOperation(title: String, body: String, action: String,
    dismiss: () -> Unit, confirm: () -> Unit,
) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(body) },
        confirmButton = { Button(onClick = confirm) { Text(action) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.plugins_native_cancel)) } })
}
