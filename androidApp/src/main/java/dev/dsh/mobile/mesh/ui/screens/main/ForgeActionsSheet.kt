package dev.dsh.mobile.mesh.ui.screens.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.core.wire.RpcResult
import dev.dsh.mobile.mesh.core.wire.dto.ForgeChangeRequestSnapshot
import dev.dsh.mobile.mesh.core.wire.dto.ForgeReviewerCandidate
import dev.dsh.mobile.mesh.core.wire.dto.ForgeReviewerRef
import dev.dsh.mobile.mesh.ui.components.DsBottomSheet
import dev.dsh.mobile.mesh.ui.components.DsButton
import dev.dsh.mobile.mesh.ui.components.DsButtonSize
import dev.dsh.mobile.mesh.ui.components.DsButtonVariant
import dev.dsh.mobile.mesh.ui.components.DsPill
import dev.dsh.mobile.mesh.ui.rememberForgeStore
import dev.dsh.mobile.mesh.ui.theme.DsTheme
import dev.dsh.mobile.mesh.ui.theme.DsType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class ForgeActionMode { Menu, MarkReady, Merge, UpdateBranch, Reviewers }

/** Host-backed change-request actions: mark ready, update branch, request reviews, merge. */
@Composable
internal fun ForgeActionsSheet(
    sessionId: String,
    url: String,
    snapshot: ForgeChangeRequestSnapshot,
    showToast: (String) -> Unit,
    onChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val store = rememberForgeStore()
    val colors = DsTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(ForgeActionMode.Menu) }
    var busy by remember { mutableStateOf(false) }
    var mergeMethod by remember { mutableStateOf(snapshot.mergeMethods.firstOrNull() ?: "merge") }
    var updateMethod by remember { mutableStateOf(snapshot.branchUpdates.firstOrNull() ?: "merge") }

    fun settle(result: RpcResult<Unit>, success: String) {
        when (result) {
            is RpcResult.Ok -> {
                showToast(success)
                onChanged()
                onDismiss()
            }
            is RpcResult.Err -> showToast(forgeErrorTextValue(context, result.error))
        }
        busy = false
    }

    val title = when (mode) {
        ForgeActionMode.Menu -> stringResource(R.string.forge_actions_title)
        ForgeActionMode.MarkReady -> stringResource(R.string.forge_mark_ready)
        ForgeActionMode.Merge -> stringResource(R.string.forge_merge_title)
        ForgeActionMode.UpdateBranch -> stringResource(R.string.forge_update_branch_title)
        ForgeActionMode.Reviewers -> stringResource(R.string.forge_request_reviews_title)
    }
    DsBottomSheet(title = title, onDismiss = onDismiss) {
        when (mode) {
            ForgeActionMode.Menu -> {
                val hasMarkReady = snapshot.state == "open" && snapshot.draft
                val hasUpdate = snapshot.branchUpdates.isNotEmpty()
                val hasRequest = snapshot.canRequestReviews
                val hasMerge = snapshot.mergeReady && snapshot.state == "open"
                if (!hasMarkReady && !hasUpdate && !hasRequest && !hasMerge) {
                    Text(stringResource(R.string.common_none), style = DsType.small13, color = colors.labelSecondary)
                }
                if (hasMarkReady) {
                    ForgeSheetRow(stringResource(R.string.forge_mark_ready), false) { mode = ForgeActionMode.MarkReady }
                }
                if (hasUpdate) {
                    ForgeSheetRow(stringResource(R.string.forge_update_branch), false) { mode = ForgeActionMode.UpdateBranch }
                }
                if (hasRequest) {
                    ForgeSheetRow(stringResource(R.string.forge_request_reviews), false) { mode = ForgeActionMode.Reviewers }
                }
                if (hasMerge) {
                    ForgeSheetRow(stringResource(R.string.forge_merge), false) { mode = ForgeActionMode.Merge }
                }
            }
            ForgeActionMode.MarkReady -> {
                Text(
                    stringResource(R.string.forge_mark_ready_confirm),
                    style = DsType.small13,
                    color = colors.labelSecondary,
                )
                DsButton(
                    text = stringResource(R.string.forge_mark_ready),
                    onClick = {
                        busy = true
                        scope.launch { settle(store.markReady(sessionId, url), context.getString(R.string.forge_action_done)) }
                    },
                    enabled = !busy,
                    variant = DsButtonVariant.Primary,
                    size = DsButtonSize.Small,
                )
            }
            ForgeActionMode.Merge -> {
                snapshot.mergeMethods.forEach { method ->
                    ForgeSheetRow(mergeMethodLabel(method), mergeMethod == method) { mergeMethod = method }
                }
                DsButton(
                    text = stringResource(R.string.forge_merge),
                    onClick = {
                        busy = true
                        scope.launch {
                            settle(
                                store.merge(sessionId, url, mergeMethod, snapshot.headSha),
                                context.getString(R.string.forge_action_done),
                            )
                        }
                    },
                    enabled = !busy && snapshot.mergeMethods.isNotEmpty(),
                    variant = DsButtonVariant.Primary,
                    size = DsButtonSize.Small,
                )
            }
            ForgeActionMode.UpdateBranch -> {
                snapshot.branchUpdates.forEach { method ->
                    ForgeSheetRow(
                        stringResource(
                            if (method == "rebase") R.string.forge_update_method_rebase
                            else R.string.forge_update_method_merge,
                        ),
                        updateMethod == method,
                    ) { updateMethod = method }
                }
                DsButton(
                    text = stringResource(R.string.forge_update_branch),
                    onClick = {
                        busy = true
                        scope.launch {
                            settle(
                                store.updateBranch(sessionId, url, updateMethod, snapshot.headSha),
                                context.getString(R.string.forge_action_done),
                            )
                        }
                    },
                    enabled = !busy,
                    variant = DsButtonVariant.Primary,
                    size = DsButtonSize.Small,
                )
            }
            ForgeActionMode.Reviewers -> ForgeReviewerPicker(
                sessionId = sessionId,
                url = url,
                busy = busy,
                onRequest = { reviewers ->
                    busy = true
                    scope.launch {
                        settle(
                            store.requestReviews(sessionId, url, reviewers),
                            context.getString(R.string.forge_action_done),
                        )
                    }
                },
                showToast = showToast,
            )
        }
    }
}

@Composable
private fun mergeMethodLabel(method: String): String = stringResource(
    when (method) {
        "squash" -> R.string.forge_merge_method_squash
        "rebase" -> R.string.forge_merge_method_rebase
        else -> R.string.forge_merge_method_merge
    },
)

@Composable
private fun ForgeReviewerPicker(
    sessionId: String,
    url: String,
    busy: Boolean,
    onRequest: (List<ForgeReviewerRef>) -> Unit,
    showToast: (String) -> Unit,
) {
    val store = rememberForgeStore()
    val colors = DsTheme.colors
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var candidates by remember { mutableStateOf<List<ForgeReviewerCandidate>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(sessionId, url, query) {
        if (query.isNotEmpty()) delay(250)
        loading = true
        when (val result = store.reviewerCandidates(sessionId, url, query)) {
            is RpcResult.Ok -> {
                candidates = result.value
                error = null
            }
            is RpcResult.Err -> error = forgeErrorTextValue(context, result.error)
        }
        loading = false
    }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        placeholder = { Text(stringResource(R.string.forge_reviewers_search)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    when {
        loading -> Text(stringResource(R.string.forge_reviewers_loading), style = DsType.small13, color = colors.labelSecondary)
        error != null -> Text(error!!, style = DsType.small13, color = colors.warnLabel)
        candidates.isEmpty() -> Text(stringResource(R.string.forge_reviewers_empty), style = DsType.small13, color = colors.labelSecondary)
        else -> LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(candidates, key = { it.login }) { candidate ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    androidx.compose.material3.Checkbox(
                        checked = candidate.login in selected,
                        onCheckedChange = { checked ->
                            selected = if (checked) selected + candidate.login else selected - candidate.login
                        },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(candidate.login, style = DsType.small13, color = colors.labelPrimary)
                        candidate.name?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = DsType.caption11, color = colors.labelTertiary)
                        }
                    }
                    if (candidate.suggested) DsPill("★")
                }
            }
        }
    }
    DsButton(
        text = stringResource(R.string.forge_request_reviews_action),
        onClick = { onRequest(selected.sorted().map { ForgeReviewerRef(kind = "user", login = it) }) },
        enabled = !busy && selected.isNotEmpty(),
        variant = DsButtonVariant.Primary,
        size = DsButtonSize.Small,
    )
}
