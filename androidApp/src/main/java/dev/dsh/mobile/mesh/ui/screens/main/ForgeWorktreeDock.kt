package dev.dsh.mobile.mesh.ui.screens.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.core.wire.RpcResult
import dev.dsh.mobile.mesh.core.wire.dto.ForgeBranches
import dev.dsh.mobile.mesh.core.wire.dto.ForgePanelWorktree
import dev.dsh.mobile.mesh.ui.components.DsButton
import dev.dsh.mobile.mesh.ui.components.DsButtonSize
import dev.dsh.mobile.mesh.ui.components.DsButtonVariant
import dev.dsh.mobile.mesh.ui.components.DsCard
import dev.dsh.mobile.mesh.ui.components.DsPill
import dev.dsh.mobile.mesh.ui.components.FeatherIcons
import dev.dsh.mobile.mesh.ui.rememberForgeStore
import dev.dsh.mobile.mesh.ui.theme.DsTheme
import dev.dsh.mobile.mesh.ui.theme.DsType
import kotlinx.coroutines.launch

/**
 * The forge worktree card above the composer.
 *
 * A blank Session can request a worktree before its first turn; the Host then holds the first
 * message while it creates the checkout. After the turn starts the option disappears, but a
 * worktree that later went missing keeps its recovery actions visible for every Session state.
 */
@Composable
internal fun ForgeWorktreeDock(
    sessionId: String,
    showToast: (String) -> Unit,
) {
    val store = rememberForgeStore()
    val state by store.state.collectAsStateWithLifecycle()
    val panel = state.panel?.takeIf { state.sessionId == sessionId } ?: return
    val worktree = panel.worktree
    val checkout = panel.checkout.checkout
    val colors = DsTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var branchSheetOpen by remember { mutableStateOf(false) }

    // Detached HEAD has no branch to base a worktree on; the Host's null base means HEAD.
    val currentBranch = checkout?.branch
    val currentLabel = currentBranch ?: checkout?.commit
    val showMissing = worktree.missing != null || worktree.restoration != null
    // `editable` is the Host's own "first turn has not started" fact. The transcript's local
    // `blank` bit turns false as soon as any journal event exists, including events that are not
    // visible rows, so it cannot decide whether the worktree option is still available.
    val showOption = worktree.editable && panel.checkout.state == "ready" &&
        worktree.blocker != "not-git"
    val showCreated = !showMissing && worktree.created != null &&
        panel.checkout.checkout?.root == worktree.created?.path
    if (!showMissing && !showOption && !showCreated) return

    fun report(result: RpcResult<Unit>, success: String) {
        when (result) {
            is RpcResult.Ok -> showToast(success)
            is RpcResult.Err -> showToast(forgeErrorTextValue(context, result.error))
        }
        busy = false
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (showMissing) {
            ForgeMissingWorktreeCard(
                worktree = worktree,
                busy = busy,
                onRestore = {
                    busy = true
                    scope.launch { report(store.restoreWorktree(sessionId), context.getString(R.string.forge_worktree_restoring)) }
                },
                onContinue = {
                    busy = true
                    scope.launch {
                        report(store.continueInProject(sessionId), context.getString(R.string.forge_worktree_continue_done))
                    }
                },
            )
            if (worktree.waiting == "restoration") {
                Text(
                    stringResource(R.string.forge_worktree_waiting_restore),
                    style = DsType.caption11,
                    color = colors.warnLabel,
                )
            }
        }
        if (showCreated && !showOption) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(FeatherIcons.GitBranch, contentDescription = null, tint = colors.labelTertiary)
                Text(
                    stringResource(R.string.forge_worktree_branch, worktree.created?.branch.orEmpty()),
                    style = DsType.caption11,
                    color = colors.labelTertiary,
                    maxLines = 1,
                )
            }
        }
        if (showOption) {
            worktree.creation?.let { creation ->
                DsCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.accent)
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.forge_worktree_creating, creation.branch), style = DsType.small13, color = colors.labelPrimary)
                            Text(
                                stringResource(
                                    if (creation.phase == "checkout") R.string.forge_worktree_checkout
                                    else R.string.forge_worktree_prepare,
                                ),
                                style = DsType.caption11,
                                color = colors.labelTertiary,
                            )
                        }
                        if (creation.held) {
                            DsButton(
                                text = stringResource(R.string.forge_worktree_cancel),
                                onClick = {
                                    busy = true
                                    scope.launch { report(store.stopCreation(sessionId), context.getString(R.string.forge_worktree_cancelled)) }
                                },
                                enabled = !busy,
                                variant = DsButtonVariant.Ghost,
                                size = DsButtonSize.Small,
                            )
                        }
                    }
                    if (worktree.waiting == "creation") {
                        Text(stringResource(R.string.forge_worktree_waiting), style = DsType.caption11, color = colors.labelTertiary)
                    }
                }
            } ?: worktree.failure?.let { failure ->
                DsCard {
                    Text(stringResource(R.string.forge_worktree_failed, failure.message), style = DsType.small13, color = colors.warnLabel)
                    Text(
                        stringResource(
                            if (failure.phase == "checkout") R.string.forge_worktree_checkout
                            else R.string.forge_worktree_prepare,
                        ),
                        style = DsType.caption11,
                        color = colors.labelTertiary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        DsButton(
                            text = stringResource(R.string.forge_worktree_retry),
                            onClick = {
                                busy = true
                                scope.launch { report(store.retryWorktree(sessionId), context.getString(R.string.forge_worktree_retrying)) }
                            },
                            enabled = !busy,
                            variant = DsButtonVariant.Outline,
                            size = DsButtonSize.Small,
                        )
                        DsButton(
                            text = stringResource(R.string.forge_worktree_skip),
                            onClick = {
                                busy = true
                                scope.launch { report(store.cancelWorktree(sessionId), context.getString(R.string.forge_worktree_skipped)) }
                            },
                            enabled = !busy,
                            variant = DsButtonVariant.Ghost,
                            size = DsButtonSize.Small,
                        )
                    }
                }
            } ?: run {
                val requested = worktree.request != null
                val blocked = worktree.blocker != null
                val base = if (requested) worktree.request?.from ?: currentBranch else currentLabel
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(FeatherIcons.GitBranch, contentDescription = null, tint = colors.labelSecondary)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.forge_worktree_option), style = DsType.small13, color = colors.labelSecondary)
                        val hint = when (worktree.blocker) {
                            "read-only" -> stringResource(R.string.forge_worktree_blocked_readonly)
                            "outside-writable-root" -> stringResource(R.string.forge_worktree_blocked_outside)
                            else -> stringResource(R.string.forge_worktree_hint)
                        }
                        Text(hint, style = DsType.caption11, color = if (blocked) colors.warnLabel else colors.labelTertiary)
                        if (requested && !blocked) {
                            DsPill(
                                text = base?.let { stringResource(R.string.forge_worktree_base, it) }
                                    ?: stringResource(R.string.forge_worktree_base_head),
                                onClick = { branchSheetOpen = true },
                                modifier = Modifier.padding(top = 4.dp).widthIn(max = 280.dp),
                            )
                        }
                    }
                    Switch(
                        checked = requested,
                        enabled = !busy && (!blocked || requested),
                        onCheckedChange = { checked ->
                            if (checked) {
                                busy = true
                                scope.launch {
                                    report(
                                        store.requestWorktree(sessionId, currentBranch),
                                        context.getString(R.string.forge_worktree_requested),
                                    )
                                }
                            } else {
                                busy = true
                                scope.launch { report(store.cancelWorktree(sessionId), context.getString(R.string.forge_worktree_cancelled)) }
                            }
                        },
                    )
                }
            }
        }
    }

    if (branchSheetOpen) {
        ForgeWorktreeBranchSheet(
            sessionId = sessionId,
            current = currentLabel,
            onSelect = { name ->
                branchSheetOpen = false
                busy = true
                scope.launch { report(store.requestWorktree(sessionId, name), context.getString(R.string.forge_worktree_requested)) }
            },
            onDismiss = { branchSheetOpen = false },
        )
    }
}

@Composable
private fun ForgeMissingWorktreeCard(
    worktree: ForgePanelWorktree,
    busy: Boolean,
    onRestore: () -> Unit,
    onContinue: () -> Unit,
) {
    val colors = DsTheme.colors
    val created = worktree.created
    DsCard {
        Text(stringResource(R.string.forge_worktree_missing), style = DsType.small13Strong, color = colors.warnLabel)
        created?.let {
            Text(stringResource(R.string.forge_worktree_branch, it.branch), style = DsType.caption11, color = colors.labelSecondary)
        }
        worktree.restoration?.let {
            Text(
                stringResource(
                    if (it.phase == "checkout") R.string.forge_worktree_restoring_checkout
                    else R.string.forge_worktree_restoring_prepare,
                ),
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            DsButton(
                text = stringResource(R.string.forge_worktree_restore),
                onClick = onRestore,
                enabled = !busy && worktree.restoration == null,
                variant = DsButtonVariant.Outline,
                size = DsButtonSize.Small,
            )
            DsButton(
                text = stringResource(R.string.forge_worktree_continue_project),
                onClick = onContinue,
                enabled = !busy && worktree.restoration == null,
                variant = DsButtonVariant.Ghost,
                size = DsButtonSize.Small,
            )
        }
    }
}

@Composable
private fun ForgeWorktreeBranchSheet(
    sessionId: String,
    current: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val store = rememberForgeStore()
    val context = LocalContext.current
    val colors = DsTheme.colors
    var branches by remember { mutableStateOf<ForgeBranches?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sessionId) {
        when (val result = store.branches(sessionId)) {
            is RpcResult.Ok -> branches = result.value
            is RpcResult.Err -> error = forgeErrorTextValue(context, result.error)
        }
    }
    dev.dsh.mobile.mesh.ui.components.DsBottomSheet(
        title = stringResource(R.string.forge_worktree_base_title),
        onDismiss = onDismiss,
    ) {
        if (error != null) {
            Text(error!!, style = DsType.small13, color = colors.warnLabel)
            return@DsBottomSheet
        }
        val listing = branches
        if (listing == null) {
            Text(stringResource(R.string.common_loading), style = DsType.small13, color = colors.labelSecondary)
            return@DsBottomSheet
        }
        Column(
            modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            listing.branches.forEach { name ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable { onSelect(name) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(name, style = DsType.std14, color = colors.labelPrimary, modifier = Modifier.weight(1f))
                    if (name == current) DsPill(stringResource(R.string.forge_worktree_current))
                }
            }
        }
    }
}
