package dev.dsh.mobile.mesh.ui.screens.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import dev.dsh.mobile.mesh.core.wire.dto.ForgeChangeRequestDiff
import dev.dsh.mobile.mesh.core.wire.dto.ForgeCommitRange
import dev.dsh.mobile.mesh.core.wire.dto.ForgeReviewData
import dev.dsh.mobile.mesh.core.wire.dto.latestSnapshot
import dev.dsh.mobile.mesh.ui.components.AppToastHost
import dev.dsh.mobile.mesh.ui.components.StatusBadge
import dev.dsh.mobile.mesh.ui.components.FeatherIcons
import dev.dsh.mobile.mesh.ui.components.rememberAppToast
import dev.dsh.mobile.mesh.ui.rememberForgeStore
import kotlinx.coroutines.launch

/** One bound change request's changed files. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ForgeChangeRequestScreen(
    sessionId: String,
    url: String,
    commits: ForgeCommitRange?,
    onCommitsChange: (ForgeCommitRange?) -> Unit,
    lens: String,
    hideTests: Boolean,
    onLensChange: (String) -> Unit,
    onHideTestsChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenDiff: (ForgeDiffTarget) -> Unit,
) {
    val store = rememberForgeStore()
    val storeState by store.state.collectAsStateWithLifecycle()
    val panel = storeState.panel?.takeIf {
        storeState.sessionId == sessionId && it.changeRequests.any { request -> request.url == url }
    }
    val request = panel?.changeRequests?.firstOrNull { it.url == url }
    val snapshot = request?.latestSnapshot()
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val coroutine = rememberCoroutineScope()
    val (toastMessage, showToast) = rememberAppToast()
    var diff by remember(sessionId, url) { mutableStateOf<ForgeChangeRequestDiff?>(null) }
    var error by remember(sessionId, url) { mutableStateOf<RpcError?>(null) }
    var loading by remember(sessionId, url) { mutableStateOf(true) }
    var reload by remember { mutableIntStateOf(0) }
    var commitSheetOpen by remember { mutableStateOf(false) }
    var lensSheetOpen by remember { mutableStateOf(false) }
    var checksSheetOpen by remember { mutableStateOf(false) }
    var actionsSheetOpen by remember { mutableStateOf(false) }
    var discussionsOpen by remember { mutableStateOf(false) }
    var submitReviewOpen by remember { mutableStateOf(false) }
    var reviewData by remember(sessionId, url) { mutableStateOf<ForgeReviewData?>(null) }
    var reviewError by remember(sessionId, url) { mutableStateOf<dev.dsh.mobile.mesh.core.wire.RpcError?>(null) }
    var reviewReload by remember { mutableIntStateOf(0) }
    var viewedMarks by remember(sessionId, url) { mutableStateOf<dev.dsh.mobile.mesh.core.wire.dto.ForgeViewedFiles?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    val copiedLabel = stringResource(R.string.common_copied)
    val title = snapshot?.title?.takeIf { it.isNotBlank() } ?: url
    val headVersion = snapshot?.headSha
    val loadedHead = diff?.headSha ?: snapshot?.headSha

    LaunchedEffect(sessionId, url, commits, reload, headVersion) {
        loading = true
        error = null
        when (val result = store.changeRequestDiff(sessionId, url, commits)) {
            is RpcResult.Ok -> diff = result.value
            is RpcResult.Err -> error = result.error
        }
        loading = false
    }
    LaunchedEffect(sessionId, url, loadedHead) {
        val head = loadedHead ?: return@LaunchedEffect
        when (val result = store.viewedFiles(sessionId, url, head)) {
            is RpcResult.Ok -> viewedMarks = result.value
            is RpcResult.Err -> viewedMarks = null
        }
    }
    LaunchedEffect(sessionId, url, loadedHead, reviewReload) {
        val head = loadedHead ?: return@LaunchedEffect
        when (val result = store.review(sessionId, url, head, fresh = reviewReload > 0)) {
            is RpcResult.Ok -> {
                reviewData = result.value
                reviewError = null
            }
            is RpcResult.Err -> {
                reviewData = null
                reviewError = result.error
            }
        }
    }
    val visibleFiles = filterForgeFiles(diff?.files.orEmpty(), lens, hideTests)
    val viewedCount = visibleFiles.count { viewedMarks?.files?.get(it.path) == "viewed" }
    val hasActions = snapshot != null && (
        (snapshot.state == "open" && snapshot.draft) ||
            snapshot.branchUpdates.isNotEmpty() ||
            snapshot.canRequestReviews ||
            (snapshot.mergeReady && snapshot.state == "open")
        )
    fun changed() {
        reload++
        coroutine.launch { store.refresh(sessionId) }
    }
    fun reviewChanged() {
        reviewReload++
        reload++
        coroutine.launch { store.refresh(sessionId) }
    }

    fun refresh() {
        reload++
        reviewReload++
        coroutine.launch { store.refresh(sessionId) }
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
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "#${snapshot?.ref?.number ?: request?.ref?.number ?: 0} · " +
                        ((snapshot?.ref ?: request?.ref)?.repository?.fullName?.takeIf { it.isNotBlank() } ?: url),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = ::refresh) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.forge_refresh),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
                            runCatching { uriHandler.openUri(url) }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.forge_copy_link)) },
                        leadingIcon = { Icon(FeatherIcons.Code, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            clipboard.setText(AnnotatedString(url))
                            showToast(copiedLabel)
                        },
                    )
                }
            }
        }
        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = ::refresh,
            modifier = Modifier.weight(1f),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                item(key = "summary") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
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
                            StatusBadge(
                                text = forgeCrStateLabel(snapshot),
                                containerColor = stateContainer,
                                contentColor = stateContent,
                            )
                            if (snapshot != null) {
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
                                val selectedCommits = commits
                                AssistChip(
                                    onClick = { commitSheetOpen = true },
                                    label = { Text(selectedCommits?.let(::commitsLabel) ?: stringResource(R.string.forge_commits_all_pill)) },
                                    trailingIcon = { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null) },
                                )
                            }
                            AssistChip(
                                onClick = { lensSheetOpen = true },
                                label = { Text(stringResource(forgeLayerLabelKey(lens))) },
                                trailingIcon = { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null) },
                            )
                        }
                        if (viewedMarks != null && visibleFiles.isNotEmpty()) {
                            Text(
                                stringResource(R.string.forge_viewed_progress, viewedCount, visibleFiles.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (snapshot != null) {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                AssistChip(
                                    onClick = { checksSheetOpen = true },
                                    label = { Text(stringResource(R.string.forge_checks_title)) },
                                )
                                if (hasActions) {
                                    AssistChip(
                                        onClick = { actionsSheetOpen = true },
                                        label = { Text(stringResource(R.string.forge_actions)) },
                                    )
                                }
                                val threads = reviewData
                                if (threads != null) {
                                    val unresolved = threads.threads.count { !it.resolved }
                                    AssistChip(
                                        onClick = { discussionsOpen = true },
                                        label = {
                                            Text(
                                                if (unresolved > 0) {
                                                    stringResource(R.string.forge_discussions_count, unresolved)
                                                } else {
                                                    stringResource(R.string.forge_discussions)
                                                },
                                            )
                                        },
                                    )
                                }
                                val pendingReview = threads?.pendingReview
                                if (pendingReview != null) {
                                    FilterChip(
                                        selected = true,
                                        onClick = { submitReviewOpen = true },
                                        label = { Text(stringResource(R.string.forge_review_pending_count, pendingReview.comments)) },
                                    )
                                } else if (threads?.viewerCanReview == true) {
                                    AssistChip(
                                        onClick = { submitReviewOpen = true },
                                        label = { Text(stringResource(R.string.forge_review_submit)) },
                                    )
                                }
                            }
                        }
                        if (snapshot != null) {
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
                        }
                        diff?.localAhead?.takeIf { it > 0 }?.let { ahead ->
                            Text(
                                stringResource(R.string.forge_local_ahead, ahead),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (error != null) {
                    item(key = "error") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(forgeErrorText(error!!), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            if (error?.code == "forgePanel/commits-missing" || error?.code == "forgePanel/commits-not-linear") {
                                OutlinedButton(onClick = { onCommitsChange(null) }) {
                                    Text(stringResource(R.string.forge_view_all_commits))
                                }
                            } else {
                                OutlinedButton(onClick = { reload++ }) {
                                    Text(stringResource(R.string.common_retry))
                                }
                            }
                        }
                    }
                } else if (loading && diff == null) {
                    item(key = "loading") {
                        Text(stringResource(R.string.common_loading), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else if (visibleFiles.isEmpty()) {
                    item(key = "empty") {
                        Text(stringResource(R.string.forge_files_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    items(visibleFiles, key = { "file:${it.path}" }) { file ->
                        val viewed = viewedMarks?.files?.get(file.path)
                        ForgeFileRow(
                            file = file,
                            viewed = viewed,
                            onToggleViewed = if (viewedMarks != null && loadedHead != null) {
                                {
                                    coroutine.launch {
                                        val target = viewed != "viewed"
                                        when (val result = store.setFileViewed(sessionId, url, file.path, target, loadedHead)) {
                                            is RpcResult.Ok -> {
                                                viewedMarks = viewedMarks?.copy(
                                                    files = viewedMarks?.files.orEmpty() + (file.path to if (target) "viewed" else "unviewed"),
                                                )
                                            }
                                            is RpcResult.Err -> showToast(forgeErrorTextValue(context, result.error))
                                        }
                                    }
                                }
                            } else null,
                        ) {
                            onOpenDiff(
                                ForgeDiffTarget(
                                    origin = ForgeDiffOrigin.Change(url, diff?.headSha ?: snapshot?.headSha.orEmpty(), commits),
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
                }
            }
        }
        AppToastHost(toastMessage to showToast, modifier = Modifier.fillMaxWidth())
    }

    if (discussionsOpen && reviewData != null && loadedHead != null) {
        ForgeThreadsSheet(
            sessionId = sessionId,
            url = url,
            headSha = loadedHead,
            data = reviewData,
            showToast = showToast,
            onChanged = ::reviewChanged,
            onDismiss = { discussionsOpen = false },
        )
    }
    if (submitReviewOpen && reviewData != null && loadedHead != null && reviewData!!.viewerCanReview) {
        ForgeSubmitReviewSheet(
            sessionId = sessionId,
            url = url,
            headSha = loadedHead,
            data = reviewData!!,
            showToast = showToast,
            onChanged = ::reviewChanged,
            onDismiss = { submitReviewOpen = false },
        )
    }
    if (checksSheetOpen && snapshot != null) {
        ForgeChecksSheet(
            sessionId = sessionId,
            url = url,
            headSha = snapshot.headSha,
            preview = snapshot,
            showToast = showToast,
            onChanged = ::changed,
            onDismiss = { checksSheetOpen = false },
        )
    }
    if (actionsSheetOpen && snapshot != null) {
        ForgeActionsSheet(
            sessionId = sessionId,
            url = url,
            snapshot = snapshot,
            showToast = showToast,
            onChanged = ::changed,
            onDismiss = { actionsSheetOpen = false },
        )
    }
    if (lensSheetOpen) {
        ForgeLensSheet(
            lens = lens,
            hideTests = hideTests,
            files = diff?.files.orEmpty(),
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
            loadKey = "$sessionId:$url:${snapshot?.headSha.orEmpty()}",
            load = {
                when (val result = store.changeRequestCommits(sessionId, url)) {
                    is RpcResult.Ok -> result.value?.let { list ->
                        RpcResult.Ok(ForgeCommitCatalog(list.commits, list.truncated))
                    } ?: RpcResult.Err(RpcError("forge/no-commit-list", "This code host does not list commits"))
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
}

@Composable
private fun forgeCrStateLabel(snapshot: dev.dsh.mobile.mesh.core.wire.dto.ForgeChangeRequestSnapshot?): String = when {
    snapshot?.state == "merged" -> stringResource(R.string.forge_state_merged)
    snapshot?.state == "closed" -> stringResource(R.string.forge_state_closed)
    snapshot?.draft == true -> stringResource(R.string.forge_state_draft)
    else -> stringResource(R.string.forge_state_open)
}
