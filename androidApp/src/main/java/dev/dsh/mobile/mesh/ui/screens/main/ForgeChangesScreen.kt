package dev.dsh.mobile.mesh.ui.screens.main

import android.content.Context
import androidx.activity.compose.BackHandler

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.core.wire.RpcError
import dev.dsh.mobile.mesh.core.wire.RpcResult
import dev.dsh.mobile.mesh.core.wire.dto.ForgeBaseBranch
import dev.dsh.mobile.mesh.core.wire.dto.ForgeBaseBranches
import dev.dsh.mobile.mesh.core.wire.dto.ForgeChangeRequestSnapshot
import dev.dsh.mobile.mesh.core.wire.dto.ForgeCommitRange
import dev.dsh.mobile.mesh.core.wire.dto.ForgeDiffFile
import dev.dsh.mobile.mesh.core.wire.dto.ForgeLocalDiff
import dev.dsh.mobile.mesh.core.wire.dto.ForgeLocalScope
import dev.dsh.mobile.mesh.core.wire.dto.ForgePanelChangeRequest
import dev.dsh.mobile.mesh.core.wire.dto.ForgePanelState
import dev.dsh.mobile.mesh.core.wire.dto.latestSnapshot
import dev.dsh.mobile.mesh.ui.components.DsBottomSheet
import dev.dsh.mobile.mesh.ui.components.FeatherIcons
import dev.dsh.mobile.mesh.ui.components.rememberDsToast
import dev.dsh.mobile.mesh.ui.components.DsToastHost
import dev.dsh.mobile.mesh.ui.components.StatusBadge
import dev.dsh.mobile.mesh.ui.rememberForgeStore
import dev.dsh.mobile.mesh.ui.theme.DsType
import kotlinx.coroutines.launch

/** Where a diff page's file came from; the page re-reads the Host diff and finds the same path. */
internal sealed interface ForgeDiffOrigin {
    data class Local(
        val scope: ForgeLocalScope,
        val baseLabel: String?,
        val commits: ForgeCommitRange?,
    ) : ForgeDiffOrigin

    data class Change(
        val url: String,
        val headSha: String,
        val commits: ForgeCommitRange?,
    ) : ForgeDiffOrigin
}

/** One file the user chose from a forge changes view. */
internal data class ForgeDiffTarget(
    val origin: ForgeDiffOrigin,
    val path: String,
    val title: String,
    val previousPath: String? = null,
    val status: String = "modified",
    val additions: Int = 0,
    val deletions: Int = 0,
    val binary: Boolean = false,
    val oversized: Boolean = false,
    val untracked: Boolean = false,
)

internal fun forgeScopeLabelKey(kind: String): Int = when (kind) {
    ForgeLocalScope.UNCOMMITTED -> R.string.forge_scope_uncommitted
    ForgeLocalScope.UNSTAGED -> R.string.forge_scope_unstaged
    ForgeLocalScope.STAGED -> R.string.forge_scope_staged
    else -> R.string.forge_scope_branch
}

internal fun forgeFileStatusLabelKey(status: String): Int = when (status) {
    "added" -> R.string.forge_file_added
    "deleted" -> R.string.forge_file_deleted
    "renamed" -> R.string.forge_file_renamed
    else -> R.string.forge_file_modified
}

internal fun shortSha(sha: String?): String = sha.orEmpty().take(7)

internal fun commitsLabel(commits: ForgeCommitRange): String =
    if (commits.from == commits.to) shortSha(commits.from)
    else "${shortSha(commits.from)}…${shortSha(commits.to)}"

/** User-facing text for one `forgePanel` failure, preserving the Host's message as fallback. */
internal fun forgeErrorTextValue(context: Context, error: RpcError): String = when (error.code) {
    "forge/offline", "capability-unavailable" -> context.getString(R.string.common_offline)
    "gateway/invocation-unavailable" -> context.getString(R.string.forge_plugin_disabled)
    "forgePanel/not-a-checkout" -> context.getString(R.string.forge_not_checkout)
    "forgePanel/no-fork-point" -> context.getString(R.string.forge_no_fork_point)
    "forgePanel/base-missing" -> context.getString(R.string.forge_base_missing)
    "forgePanel/filter-config-failed" -> context.getString(R.string.forge_filter_failed)
    "forgePanel/diff-too-large" -> context.getString(R.string.forge_diff_too_large)
    "forgePanel/stale-head" -> context.getString(R.string.forge_stale_head)
    "forgePanel/commits-missing" -> context.getString(R.string.forge_commits_missing)
    "forgePanel/commits-not-linear" -> context.getString(R.string.forge_commits_not_linear)
    "forgePanel/not-bound" -> context.getString(R.string.forge_not_bound)
    "forgePanel/session-started" -> context.getString(R.string.forge_session_started)
    "forgePanel/session-busy" -> context.getString(R.string.forge_session_busy)
    "forgePanel/no-worktree" -> context.getString(R.string.forge_no_worktree)
    "forgePanel/not-waiting" -> context.getString(R.string.forge_not_waiting)
    "forgePanel/worktrees-unavailable" -> context.getString(R.string.forge_worktrees_unavailable)
    "forgePanel/invalid-branch" -> context.getString(R.string.forge_invalid_branch)
    else -> error.message.ifBlank { context.getString(R.string.common_error) }
}

@Composable
internal fun forgeErrorText(error: RpcError): String = forgeErrorTextValue(LocalContext.current, error)

/** Branch, worktree and change-request changes view for the current Session. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ForgeChangesScreen(
    sessionId: String,
    scopeKind: String,
    baseRef: String?,
    baseName: String?,
    commits: ForgeCommitRange?,
    lens: String,
    hideTests: Boolean,
    onLensChange: (String) -> Unit,
    onHideTestsChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onScopeChange: (String) -> Unit,
    onBaseChange: (ForgeBaseBranch?) -> Unit,
    onCommitsChange: (ForgeCommitRange?) -> Unit,
    onOpenDiff: (ForgeDiffTarget) -> Unit,
    onOpenChangeRequest: (ForgePanelChangeRequest) -> Unit,
) {
    val store = rememberForgeStore()
    val storeState by store.state.collectAsStateWithLifecycle()
    // The panel store is process-scoped and may still hold the previous Session for one frame
    // after a switch; never render that Session's checkout in this one.
    val panel = storeState.panel?.takeIf { storeState.sessionId == sessionId }
    val available = storeState.available
    val streamLoading = storeState.loading
    val streamError = storeState.streamError
    val scope = remember(scopeKind, baseRef, commits) {
        when (scopeKind) {
            ForgeLocalScope.UNCOMMITTED -> ForgeLocalScope.uncommitted()
            ForgeLocalScope.UNSTAGED -> ForgeLocalScope.unstaged()
            ForgeLocalScope.STAGED -> ForgeLocalScope.staged()
            else -> ForgeLocalScope.branch(baseRef, commits)
        }
    }
    val scopeCoroutine = rememberCoroutineScope()
    val context = LocalContext.current
    val (toastMessage, showToast) = rememberDsToast()
    val clipboard = LocalClipboardManager.current
    val copiedLabel = stringResource(R.string.common_copied)
    val removedLabel = stringResource(R.string.forge_dismissed)
    val uriHandler = LocalUriHandler.current
    var localDiff by remember(sessionId) { mutableStateOf<ForgeLocalDiff?>(null) }
    var diffError by remember(sessionId) { mutableStateOf<RpcError?>(null) }
    var loadingDiff by remember(sessionId) { mutableStateOf(true) }
    var refreshNonce by remember { mutableIntStateOf(0) }
    var baseSheetOpen by remember { mutableStateOf(false) }
    var scopeSheetOpen by remember { mutableStateOf(false) }
    var commitSheetOpen by remember { mutableStateOf(false) }
    var lensSheetOpen by remember { mutableStateOf(false) }
    var viewedRevision by remember { mutableIntStateOf(0) }
    var dismissing by remember { mutableStateOf<ForgePanelChangeRequest?>(null) }
    var loadedScopeKey by remember(sessionId) { mutableStateOf<String?>(null) }
    val scopeKey = "$scopeKind|${baseRef.orEmpty()}|${commits?.from.orEmpty()}|${commits?.to.orEmpty()}"
    val panelKey = listOf(
        panel?.checkout?.checkout?.commit,
        panel?.local?.base,
        panel?.local?.files?.joinToString("|") { "${it.path}:${it.additions}:${it.deletions}" },
    ).joinToString("#")

    LaunchedEffect(sessionId, scopeKey, scope, refreshNonce, panelKey) {
        if (loadedScopeKey != scopeKey) {
            loadedScopeKey = scopeKey
            localDiff = null
        }
        loadingDiff = localDiff == null
        diffError = null
        when (val result = store.localDiff(sessionId, scope)) {
            is RpcResult.Ok -> localDiff = result.value
            is RpcResult.Err -> {
                diffError = result.error
                localDiff = null
            }
        }
        loadingDiff = false
    }

    fun refresh() {
        refreshNonce++
        scopeCoroutine.launch { store.refresh(sessionId) }
    }

    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.forge_changes),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            IconButton(onClick = ::refresh) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.forge_refresh),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        PullToRefreshBox(
            isRefreshing = loadingDiff,
            onRefresh = ::refresh,
            modifier = Modifier.weight(1f),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                if (available == false || streamError?.code == "gateway/invocation-unavailable") {
                    item(key = "unavailable") {
                        Text(stringResource(R.string.forge_plugin_disabled), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    return@LazyColumn
                }
                if (panel == null) {
                    item(key = "stream-status") {
                        Text(
                            if (streamLoading) stringResource(R.string.common_loading)
                            else streamError?.let { forgeErrorText(it) } ?: stringResource(R.string.common_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    return@LazyColumn
                }

                streamError?.let { failure ->
                    item(key = "stream-error") {
                        Text(forgeErrorText(failure), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                item(key = "checkout") { ForgeCheckoutRow(panel) }

                val checkoutState = panel.checkout.state
                if (checkoutState == "pending") {
                    item(key = "checkout-pending") {
                        Text(stringResource(R.string.forge_checkout_pending), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    return@LazyColumn
                }
                if (checkoutState == "none") {
                    item(key = "not-git") {
                        Text(stringResource(R.string.forge_not_checkout), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    return@LazyColumn
                }
                if (checkoutState == "failed") {
                    item(key = "checkout-failed") {
                        Text(
                            panel.checkout.message?.takeIf { it.isNotBlank() } ?: stringResource(R.string.forge_checkout_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    return@LazyColumn
                }

                item(key = "scopes") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            AssistChip(
                                onClick = { scopeSheetOpen = true },
                                label = { Text(stringResource(forgeScopeLabelKey(scopeKind))) },
                                trailingIcon = { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null) },
                            )
                            if (scopeKind == ForgeLocalScope.BRANCH) {
                                val automaticBase = localDiff?.base ?: panel.local?.base
                                val label = baseName ?: baseRef?.substringAfter("refs/heads/")?.substringAfter("refs/remotes/")
                                    ?: automaticBase
                                    ?: stringResource(R.string.forge_base_default)
                                AssistChip(
                                    onClick = { baseSheetOpen = true },
                                    label = { Text(stringResource(R.string.forge_base_pill, label)) },
                                    trailingIcon = { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null) },
                                )
                                AssistChip(
                                    onClick = { commitSheetOpen = true },
                                    label = { Text(commits?.let(::commitsLabel) ?: stringResource(R.string.forge_commits_all_pill)) },
                                    trailingIcon = { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null) },
                                )
                            }
                            AssistChip(
                                onClick = { lensSheetOpen = true },
                                label = { Text(stringResource(R.string.forge_files_filter)) },
                            )
                        }
                        val allFiles = filterForgeFiles(localDiff?.files.orEmpty(), lens, hideTests)
                        val localScopeKey = "$sessionId|$scopeKey"
                        // Reading the revision keeps toggling a checkbox from a callback outside this
                        // composition from leaving the progress text stale.
                        viewedRevision
                        val reviewed = allFiles.count { file ->
                            store.localViewedDigest(localScopeKey, file.path) == file.digest
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(
                                    R.string.forge_summary,
                                    allFiles.size,
                                    allFiles.sumOf { it.additions },
                                    allFiles.sumOf { it.deletions },
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (commits != null) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    stringResource(R.string.forge_commits_selected, commitsLabel(commits)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            if (allFiles.isNotEmpty()) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    stringResource(R.string.forge_viewed_progress, reviewed, allFiles.size),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                if (diffError != null && diffError?.code == "forgePanel/no-fork-point") {
                    item(key = "no-fork") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.forge_no_fork_point), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            OutlinedButton(onClick = { onBaseChange(null) }) {
                                Text(stringResource(R.string.forge_use_default_base))
                            }
                        }
                    }
                } else if (diffError != null) {
                    item(key = "diff-error") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(forgeErrorText(diffError!!), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                            OutlinedButton(onClick = { refreshNonce++ }) {
                                Text(stringResource(R.string.common_retry))
                            }
                        }
                    }
                } else if (loadingDiff && localDiff == null) {
                    item(key = "diff-loading") {
                        Text(stringResource(R.string.common_loading), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else if (visibleLocalFiles(localDiff?.files, lens, hideTests).isEmpty()) {
                    item(key = "diff-empty") {
                        Text(stringResource(R.string.forge_files_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    items(visibleLocalFiles(localDiff?.files, lens, hideTests), key = { "file:${it.path}" }) { file ->
                        val savedDigest = store.localViewedDigest("$sessionId|$scopeKey", file.path)
                        val viewed = when {
                            savedDigest == null -> "unviewed"
                            savedDigest == file.digest -> "viewed"
                            else -> "dismissed"
                        }
                        ForgeFileRow(
                            file = file,
                            viewed = viewed,
                            onToggleViewed = {
                                store.toggleLocalViewed("$sessionId|$scopeKey", file.path, file.digest)
                                viewedRevision++
                            },
                        ) {
                            onOpenDiff(
                                ForgeDiffTarget(
                                    origin = ForgeDiffOrigin.Local(scope, localDiff?.base, commits),
                                    path = file.path,
                                    title = file.path.substringAfterLast('/'),
                                    previousPath = file.previousPath,
                                    status = file.status,
                                    additions = file.additions,
                                    deletions = file.deletions,
                                    binary = file.binary,
                                    oversized = file.oversized,
                                    untracked = file.untracked,
                                ),
                            )
                        }
                    }
                    localDiff?.omitted?.takeIf { it > 0 }?.let { omitted ->
                        item(key = "omitted") {
                            Text(
                                stringResource(R.string.forge_files_omitted, omitted),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                val changeRequests = panel.changeRequests
                val readiness = panel.readiness
                if (changeRequests.isNotEmpty()) {
                    item(key = "cr-header") {
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.forge_change_requests), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.weight(1f))
                            Text(changeRequests.size.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(changeRequests, key = { "cr:${it.url}" }) { request ->
                        ForgeChangeRequestCard(
                            request = request,
                            onOpen = { if (request.latestSnapshot() != null) onOpenChangeRequest(request) },
                            onOpenInBrowser = {
                                runCatching { uriHandler.openUri(request.url) }
                            },
                            onCopyLink = {
                                clipboard.setText(AnnotatedString(request.url))
                                showToast(copiedLabel)
                            },
                            onDismiss = { dismissing = request },
                        )
                    }
                } else if (panel.repository != null && readiness?.state != null && readiness.state != "ready") {
                    item(key = "readiness") {
                        Text(
                            readiness?.message?.takeIf { it.isNotBlank() } ?: stringResource(R.string.forge_github_login),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
        DsToastHost(toastMessage to showToast, modifier = Modifier.fillMaxWidth())
    }

    if (scopeSheetOpen) {
        ForgeScopeSheet(
            selected = scopeKind,
            onSelect = {
                scopeSheetOpen = false
                if (it != scopeKind) onScopeChange(it)
            },
            onDismiss = { scopeSheetOpen = false },
        )
    }
    if (baseSheetOpen) {
        ForgeBaseSheet(
            sessionId = sessionId,
            selectedRef = baseRef,
            onSelect = {
                baseSheetOpen = false
                onBaseChange(it)
            },
            onDismiss = { baseSheetOpen = false },
        )
    }
    if (lensSheetOpen) {
        ForgeLensSheet(
            lens = lens,
            hideTests = hideTests,
            files = localDiff?.files.orEmpty(),
            onSelect = {
                lensSheetOpen = false
                onLensChange(it)
            },
            onHideTestsChange = onHideTestsChange,
            onDismiss = { lensSheetOpen = false },
        )
    }
    if (commitSheetOpen) {
        ForgeCommitSheet(
            loadKey = "$sessionId:${baseRef.orEmpty()}",
            load = {
                when (val result = store.localCommits(sessionId, baseRef)) {
                    is RpcResult.Ok -> RpcResult.Ok(ForgeCommitCatalog(result.value.commits, result.value.truncated))
                    is RpcResult.Err -> result
                }
            },
            selected = commits,
            onSelect = {
                commitSheetOpen = false
                onCommitsChange(it)
            },
            onDismiss = { commitSheetOpen = false },
        )
    }
    dismissing?.let { request ->
        ConfirmDialog(
            title = stringResource(R.string.forge_dismiss),
            body = stringResource(R.string.forge_dismiss_confirm),
            confirmLabel = stringResource(R.string.common_remove),
            onDismiss = { dismissing = null },
            onConfirm = {
                dismissing = null
                scopeCoroutine.launch {
                    when (val result = store.dismiss(sessionId, request.url)) {
                        is RpcResult.Ok -> showToast(removedLabel)
                        is RpcResult.Err -> showToast(forgeErrorTextValue(context, result.error))
                    }
                }
            },
        )
    }
}

@Composable
private fun ForgeCheckoutRow(panel: ForgePanelState) {
    val checkout = panel.checkout.checkout
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(FeatherIcons.GitBranch, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                checkout?.branch ?: checkout?.commit ?: stringResource(R.string.forge_checkout_pending),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val kind = when {
                panel.worktree.created != null && panel.worktree.created?.path == checkout?.root -> {
                    panel.worktree.created?.from?.let { stringResource(R.string.forge_worktree_from, it) }
                        ?: stringResource(R.string.forge_worktree_created_short)
                }
                checkout?.linked == true -> stringResource(R.string.forge_checkout_worktree)
                else -> stringResource(R.string.forge_checkout_project)
            }
            Text(kind, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun ForgeFileRow(
    file: ForgeDiffFile,
    viewed: String? = null,
    onToggleViewed: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Icon(FeatherIcons.FileText, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        headlineContent = {
            Text(
                file.path.substringAfterLast('/'),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = file.path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { directory ->
            {
                Text(
                    directory,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (file.binary) {
                    StatusBadge(stringResource(R.string.forge_binary))
                } else {
                    Text("+${file.additions}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text("-${file.deletions}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                if (file.status != "modified") {
                    StatusBadge(stringResource(forgeFileStatusLabelKey(file.status)))
                }
                if (viewed == "dismissed") {
                    StatusBadge(
                        text = stringResource(R.string.forge_viewed_dismissed),
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                if (onToggleViewed != null) {
                    Checkbox(
                        checked = viewed == "viewed",
                        onCheckedChange = { onToggleViewed() },
                    )
                }
            }
        },
    )
}

@Composable
private fun ForgeChangeRequestCard(
    request: ForgePanelChangeRequest,
    onOpen: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onCopyLink: () -> Unit,
    onDismiss: () -> Unit,
) {
    val snapshot = request.latestSnapshot()
    var menuOpen by remember { mutableStateOf(false) }
    val stateContainer = when {
        snapshot?.draft == true -> MaterialTheme.colorScheme.secondaryContainer
        snapshot?.state == "merged" -> MaterialTheme.colorScheme.tertiaryContainer
        snapshot?.state == "closed" -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val stateContent = when {
        snapshot?.draft == true -> MaterialTheme.colorScheme.onSecondaryContainer
        snapshot?.state == "merged" -> MaterialTheme.colorScheme.onTertiaryContainer
        snapshot?.state == "closed" -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (snapshot != null) Modifier.clickable(onClick = onOpen) else Modifier),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "#${snapshot?.ref?.number ?: request.ref?.number ?: 0}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                StatusBadge(
                    text = forgeChangeStateLabel(snapshot),
                    containerColor = stateContainer,
                    contentColor = stateContent,
                )
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.forge_more),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.forge_open_in_browser)) },
                            leadingIcon = { Icon(FeatherIcons.Globe, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onOpenInBrowser()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.forge_copy_link)) },
                            leadingIcon = { Icon(FeatherIcons.Code, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onCopyLink()
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.forge_dismiss),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onDismiss()
                            },
                        )
                    }
                }
            }
            Text(
                snapshot?.title ?: stringResource(R.string.forge_change_request_pending),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (snapshot != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (snapshot.checksRollup != "none") {
                        StatusBadge(
                            text = stringResource(
                                when (snapshot.checksRollup) {
                                    "success" -> R.string.forge_checks_success
                                    "failure" -> R.string.forge_checks_failure
                                    else -> R.string.forge_checks_pending
                                },
                            ),
                            containerColor = if (snapshot.checksRollup == "failure") {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            },
                            contentColor = if (snapshot.checksRollup == "failure") {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            },
                        )
                    }
                    if (snapshot.reviewDecision != "none") {
                        StatusBadge(
                            text = stringResource(
                                when (snapshot.reviewDecision) {
                                    "approved" -> R.string.forge_review_approved
                                    "changes-requested" -> R.string.forge_review_changes
                                    else -> R.string.forge_review_required
                                },
                            ),
                        )
                    }
                    if (snapshot.mergeability == "conflicting") {
                        StatusBadge(
                            text = stringResource(R.string.forge_conflict),
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                    if (snapshot.unresolvedThreads > 0) {
                        StatusBadge(stringResource(R.string.forge_unresolved, snapshot.unresolvedThreads))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("+${snapshot.additions}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text("-${snapshot.deletions}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    Text(
                        "${snapshot.headBranch} → ${snapshot.baseBranch}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                Text(
                    stringResource(R.string.forge_change_request_pending),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun forgeChangeStateLabel(snapshot: ForgeChangeRequestSnapshot?): String = when {
    snapshot?.state == "merged" -> stringResource(R.string.forge_state_merged)
    snapshot?.state == "closed" -> stringResource(R.string.forge_state_closed)
    snapshot?.draft == true -> stringResource(R.string.forge_state_draft)
    else -> stringResource(R.string.forge_state_open)
}

@Composable
private fun ForgeScopeSheet(selected: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    DsBottomSheet(title = stringResource(R.string.forge_scope), onDismiss = onDismiss) {
        ForgeSheetRow(stringResource(R.string.forge_scope_branch), selected == ForgeLocalScope.BRANCH) { onSelect(ForgeLocalScope.BRANCH) }
        ForgeSheetRow(stringResource(R.string.forge_scope_uncommitted), selected == ForgeLocalScope.UNCOMMITTED) { onSelect(ForgeLocalScope.UNCOMMITTED) }
        ForgeSheetRow(stringResource(R.string.forge_scope_unstaged), selected == ForgeLocalScope.UNSTAGED) { onSelect(ForgeLocalScope.UNSTAGED) }
        ForgeSheetRow(stringResource(R.string.forge_scope_staged), selected == ForgeLocalScope.STAGED) { onSelect(ForgeLocalScope.STAGED) }
    }
}

@Composable
private fun ForgeBaseSheet(
    sessionId: String,
    selectedRef: String?,
    onSelect: (ForgeBaseBranch?) -> Unit,
    onDismiss: () -> Unit,
) {
    val store = rememberForgeStore()
    var query by remember { mutableStateOf("") }
    var listing by remember { mutableStateOf<ForgeBaseBranches?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<RpcError?>(null) }
    LaunchedEffect(sessionId, query) {
        if (query.isNotEmpty()) kotlinx.coroutines.delay(250)
        loading = true
        when (val result = store.baseBranches(sessionId, query)) {
            is RpcResult.Ok -> {
                listing = result.value
                error = null
            }
            is RpcResult.Err -> error = result.error
        }
        loading = false
    }
    DsBottomSheet(title = stringResource(R.string.forge_base_title), onDismiss = onDismiss) {
        androidx.compose.material3.OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.forge_base_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (loading && listing == null) {
            Text(stringResource(R.string.common_loading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        error?.let {
            Text(forgeErrorText(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        listing?.let { bases ->
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val automatic = bases.automatic
                if (automatic != null) {
                    item(key = "automatic") {
                        ForgeSheetRow(
                            stringResource(R.string.forge_base_automatic, automatic.name),
                            selectedRef == null,
                        ) { onSelect(null) }
                    }
                } else {
                    item(key = "no-automatic") {
                        Text(
                            stringResource(R.string.forge_no_fork_point),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
                // The automatic fork point already is this remote branch; the Web menu omits the
                // duplicate unless the automatic base is a created worktree's base commit.
                val automaticRemote = bases.automatic?.takeIf { !it.worktree }?.name
                val remoteRows = bases.remote.filterNot { it.name == automaticRemote }
                if (remoteRows.isNotEmpty()) {
                    item(key = "remote-heading") {
                        Text(
                            stringResource(R.string.forge_base_remote),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                        )
                    }
                    items(remoteRows, key = { "r:${it.ref}" }) { branch ->
                        ForgeSheetRow(branch.name, selectedRef == branch.ref) { onSelect(branch) }
                    }
                }
                if (bases.local.isNotEmpty()) {
                    item(key = "local-heading") {
                        Text(
                            stringResource(R.string.forge_base_local),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                        )
                    }
                    items(bases.local, key = { "l:${it.ref}" }) { branch ->
                        ForgeSheetRow(branch.name, selectedRef == branch.ref) { onSelect(branch) }
                    }
                }
            }
        }
    }
}

/** The subset of a commit listing the picker needs, from either a local scope or a change request. */
internal data class ForgeCommitCatalog(
    val commits: List<dev.dsh.mobile.mesh.core.wire.dto.ForgeCommit>,
    val truncated: Boolean,
)

@Composable
internal fun ForgeCommitSheet(
    loadKey: String,
    load: suspend () -> RpcResult<ForgeCommitCatalog>,
    selected: ForgeCommitRange?,
    onSelect: (ForgeCommitRange?) -> Unit,
    onDismiss: () -> Unit,
) {
    var commits by remember { mutableStateOf<List<dev.dsh.mobile.mesh.core.wire.dto.ForgeCommit>>(emptyList()) }
    var truncated by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<RpcError?>(null) }
    var rangeMode by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf<String?>(null) }
    var rangeError by remember { mutableStateOf(false) }
    LaunchedEffect(loadKey) {
        when (val result = load()) {
            is RpcResult.Ok -> {
                commits = result.value.commits
                truncated = result.value.truncated
                error = null
            }
            is RpcResult.Err -> error = result.error
        }
        loading = false
    }
    fun pick(sha: String) {
        if (!rangeMode) {
            onSelect(ForgeCommitRange(sha, sha))
            return
        }
        val start = anchor
        if (start == null) {
            anchor = sha
            rangeError = false
            return
        }
        val startIndex = commits.indexOfFirst { it.sha == start }
        val shaIndex = commits.indexOfFirst { it.sha == sha }
        val (from, to) = if (startIndex <= shaIndex) start to sha else sha to start
        when (val check = dev.dsh.mobile.mesh.core.forge.checkForgeCommitRange(commits, from, to)) {
            is dev.dsh.mobile.mesh.core.forge.ForgeCommitRangeCheck.Accepted -> {
                onSelect(ForgeCommitRange(from, to))
            }
            dev.dsh.mobile.mesh.core.forge.ForgeCommitRangeCheck.NotLinear,
            dev.dsh.mobile.mesh.core.forge.ForgeCommitRangeCheck.Missing,
            -> rangeError = true
        }
    }
    DsBottomSheet(title = stringResource(R.string.forge_commits_title), onDismiss = onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.forge_commits_range_mode), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(checked = rangeMode, onCheckedChange = { rangeMode = it; anchor = null; rangeError = false })
        }
        if (loading) {
            Text(stringResource(R.string.forge_commits_loading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@DsBottomSheet
        }
        error?.let {
            Text(forgeErrorText(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            return@DsBottomSheet
        }
        if (rangeError) {
            Text(stringResource(R.string.forge_commits_not_linear), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item(key = "all") {
                ForgeSheetRow(stringResource(R.string.forge_commits_all_pill), selected == null) { onSelect(null) }
            }
            itemsIndexed(commits, key = { _, commit -> commit.sha }) { index, commit ->
                val fromIndex = commits.indexOfFirst { it.sha == selected?.from }
                val toIndex = commits.indexOfFirst { it.sha == selected?.to }
                val inRange = selected != null && fromIndex >= 0 && toIndex >= 0 && index in fromIndex..toIndex
                val isAnchor = anchor == commit.sha
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { pick(commit.sha) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(shortSha(commit.sha), style = DsType.mdCode, color = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(commit.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        commit.author?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    if (isAnchor) {
                        StatusBadge(
                            text = stringResource(R.string.forge_commits_anchor),
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    } else if (inRange) {
                        StatusBadge(
                            text = "✓",
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
            if (truncated) {
                item(key = "truncated") {
                    Text(stringResource(R.string.forge_commits_truncated), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Visible files for the current lens, shared by the empty state and the list. */
private fun visibleLocalFiles(
    files: List<ForgeDiffFile>?,
    lens: String,
    hideTests: Boolean,
): List<ForgeDiffFile> = filterForgeFiles(files.orEmpty(), lens, hideTests)
