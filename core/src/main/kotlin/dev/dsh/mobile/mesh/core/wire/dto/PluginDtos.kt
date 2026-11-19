package dev.dsh.mobile.mesh.core.wire.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Plugin-inventory DTOs, ported from `packages/host/plugin-inventory/src/types.ts`.
 *
 * The inventory describes the host's composition. A managed profile also exposes the separate
 * `pluginManager` API and its browser UI, subject to the connected peer's access permissions.
 */

/** Where a plugin has got to in the Cordis loader's lifecycle. Absent means it never mounted. */
@Serializable
enum class PluginFiberPhase {
    @SerialName("pending")
    PENDING,

    @SerialName("loading")
    LOADING,

    @SerialName("active")
    ACTIVE,

    @SerialName("failed")
    FAILED,

    @SerialName("unloading")
    UNLOADING,
}

/** One row of `pluginInventory/list`. */
@Serializable
data class PluginInventoryEntry(
    /** The loader-tree entry id — stable, and what a `cordis.patch.yml` override targets. */
    @SerialName("entryId") val entryId: String,
    /** The exact module specifier, e.g. `@deepseek-ai/dsh-client-ui-plan`. */
    @SerialName("moduleName") val moduleName: String,
    /** Effective enablement, already folded through any disabled ancestor group. */
    @SerialName("enabled") val enabled: Boolean,
    @SerialName("fiberPhase") val fiberPhase: PluginFiberPhase? = null,
    /**
     * Host-reported startup failure for this entry's root fiber, when
     * [fiberPhase] is [PluginFiberPhase.FAILED].
     *
     * The text is the Host's own error message, shown verbatim: the app cannot
     * reproduce a startup failure it never ran. Across the wire the field is
     * absent whenever the entry has no failed fiber, and old Hosts omit it
     * entirely, so `null` means "nothing reported" rather than "no failure".
     */
    @SerialName("diagnostic") val diagnostic: String? = null,
)

/** Value of `pluginInventory/list`. */
@Serializable
data class PluginInventorySnapshot(
    @SerialName("entries") val entries: List<PluginInventoryEntry> = emptyList(),
    /** Whether the host has a managed profile; permission to use it is checked separately. */
    @SerialName("managementAvailable") val managementAvailable: Boolean = false,
)

/** Server-owned managed-profile bundle record; retain unknown metadata as opaque JSON. */
@Serializable
data class PluginManagerBundleInfo(
    val name: String,
    val official: Boolean = false,
    val availability: String = "missing",
    val version: String? = null,
    /** Only the Host's exact catalog target is installable; never derive a spec from the name. */
    val installTarget: PluginManagerInstallTarget? = null,
    val source: String? = null,
    val listedByDefault: Boolean? = null,
    val meta: JsonElement? = null,
    val description: String? = null,
    val enabled: Boolean,
    val installed: Boolean = false,
    val optional: Boolean = false,
    val removable: Boolean = false,
    val updatable: Boolean = false,
    val readOnlyReason: String? = null,
    val error: PluginManagerManagementError? = null,
    val rows: List<PluginManagerBundleRow> = emptyList(),
    val overrides: List<String> = emptyList(),
)

@Serializable
data class PluginManagerBundleRow(
    val rowId: String,
    val moduleName: String,
    val meta: JsonElement? = null,
    val entryId: String? = null,
)

/** A managed-profile plugin entry; entryId, not patchId, addresses the toggle RPC. */
@Serializable
data class PluginManagerPluginInfo(
    val entryId: String,
    val moduleName: String,
    val enabled: Boolean,
    val fiberPhase: PluginFiberPhase? = null,
    val diagnostic: String? = null,
    val patchId: String? = null,
    val readOnlyReason: String? = null,
    val meta: JsonElement? = null,
)

@Serializable
data class PluginManagerManagementError(
    val code: String,
    val diagnostic: String? = null,
    val incompatible: List<PluginManagerIncompatiblePlugin> = emptyList(),
)

/** A change can persist while requiring a restart or being overridden; never infer success from changed alone. */
@Serializable
data class PluginManagerChangeResult(
    val changed: Boolean,
    val application: String,
    val stage: String,
    val target: String,
    val enabled: Boolean? = null,
    val restartReason: String? = null,
    val error: PluginManagerManagementError? = null,
    val warnings: List<String> = emptyList(),
    val packageResult: PluginManagerPackageResult? = null,
    val bundle: String? = null,
    val version: String? = null,
    /** Host-reported exact allowBuilds keys; present only when a failed install awaits approval. */
    val pendingBuilds: List<String> = emptyList(),
    /** Script permissions that the Host saved before the installation attempt. */
    val approvedBuilds: List<String> = emptyList(),
    /** The registry attempts are ordered; null represents pnpm's configured registry. */
    val registries: List<String?> = emptyList(),
    val failedAt: String? = null,
)

@Serializable
data class PluginManagerInstallTarget(val spec: String, val version: String)

@Serializable
data class PluginManagerIncompatiblePlugin(
    val name: String,
    val version: String,
    val runtimeVersion: String,
    val requirements: Map<String, String>,
)

@Serializable
data class PluginManagerPackageResult(
    val exitCode: Int,
    val output: String,
    val truncated: Boolean,
    val logPath: String,
    val kind: String? = null,
    val timedOut: Boolean? = null,
    val incompatible: List<PluginManagerIncompatiblePlugin> = emptyList(),
)

/** Null registry selects pnpm's own configuration; do not substitute a client-derived registry. */
@Serializable
data class PluginManagerRegistries(
    val registry: String?,
    val fallbackRegistries: List<String>,
    val resolved: String?,
)

/** The server decides whether the spec is installable; inspect never authorizes an installation. */
@Serializable
data class PluginManagerSpecInspection(
    val status: String,
    val kind: String? = null,
    val name: String? = null,
    val version: String? = null,
    val description: String? = null,
    val bundle: Boolean? = null,
    val registry: String? = null,
    val host: String? = null,
    val problem: String? = null,
    val reason: String? = null,
    val registries: List<String?> = emptyList(),
)

/** Only send approvedBuilds after a user explicitly approves the Host's pendingBuilds. */
data class PluginManagerInstallOptions(
    val enabled: Boolean? = null,
    val saveExact: Boolean? = null,
    val requestId: String? = null,
    val approvedBuilds: List<String>? = null,
    val registry: String? = null,
    /** Explicitly choose the registry from pnpm's own configuration (wire registry = null). */
    val usePnpmRegistry: Boolean = false,
)

/** waitForInstall returns null if the Host cannot associate this request id with a result. */
@Serializable
data class PluginManagerInstallCancellation(val status: String)

@Serializable
data class PluginManagerVersionExemptions(
    val exemptions: Map<String, List<String>>,
    val warnings: List<String>,
)

@Serializable
data class PluginManagerRegistryAttempt(
    val registry: String?,
    val problem: String? = null,
    val reason: String? = null,
)

@Serializable
data class PluginManagerVersion(
    val version: String,
    val publishedAt: String? = null,
    val tags: List<String>,
    val prerelease: Boolean,
    val deprecated: String? = null,
    val bundle: Boolean,
    val incompatible: PluginManagerIncompatiblePlugin? = null,
)

/** Listed/refused is the Host's discriminant; do not infer eligibility from cached bundle flags. */
@Serializable
data class PluginManagerVersionList(
    val status: String,
    val installed: String? = null,
    val registry: String? = null,
    val attempts: List<PluginManagerRegistryAttempt>,
    val versions: List<PluginManagerVersion> = emptyList(),
    val omitted: Int? = null,
    val problem: String? = null,
    val reason: String? = null,
)

@Serializable
data class PluginManagerUpdateResult(
    val from: String,
    val to: String,
    val step: String,
    val result: PluginManagerChangeResult,
    val restore: PluginManagerChangeResult? = null,
)
