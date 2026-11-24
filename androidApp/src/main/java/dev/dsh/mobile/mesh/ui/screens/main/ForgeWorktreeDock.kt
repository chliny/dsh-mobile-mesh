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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.TextButton
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
import dev.dsh.mobile.mesh.ui.components.FeatherIcons
import dev.dsh.mobile.mesh.ui.components.StatusBadge
import dev.dsh.mobile.mesh.ui.rememberForgeStore
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
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (showCreated && !showOption) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(FeatherIcons.GitBranch, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    stringResource(R.string.forge_worktree_branch, worktree.created?.branch.orEmpty()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        if (showOption) {
            worktree.creation?.let { creation ->
                ForgeDockCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.forge_worktree_creating, creation.branch), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                stringResource(
                                    if (creation.phase == "checkout") R.string.forge_worktree_checkout
                                    else R.string.forge_worktree_prepare,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (creation.held) {
                            TextButton(
                                onClick = {
                                    busy = true
                                    scope.launch { report(store.stopCreation(sessionId), context.getString(R.string.forge_worktree_cancelled)) }
                                },
                                enabled = !busy,
                            ) { Text(stringResource(R.string.forge_worktree_cancel)) }
                        }
                    }
                    if (worktree.waiting == "creation") {
                        Text(stringResource(R.string.forge_worktree_waiting), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } ?: worktree.failure?.let { failure ->
                ForgeDockCard {
                    Text(stringResource(R.string.forge_worktree_failed, failure.message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    Text(
                        stringResource(
                            if (failure.phase == "checkout") R.string.forge_worktree_checkout
                            else R.string.forge_worktree_prepare,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = {
                                busy = true
                                scope.launch { report(store.retryWorktree(sessionId), context.getString(R.string.forge_worktree_retrying)) }
                            },
                            enabled = !busy,
                        ) { Text(stringResource(R.string.forge_worktree_retry)) }
                        TextButton(
                            onClick = {
                                busy = true
                                scope.launch { report(store.cancelWorktree(sessionId), context.getString(R.string.forge_worktree_skipped)) }
                            },
                            enabled = !busy,
                        ) { Text(stringResource(R.string.forge_worktree_skip)) }
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
                    Icon(FeatherIcons.GitBranch, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.forge_worktree_option), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val hint = when (worktree.blocker) {
                            "read-only" -> stringResource(R.string.forge_worktree_blocked_readonly)
                            "outside-writable-root" -> stringResource(R.string.forge_worktree_blocked_outside)
                            else -> stringResource(R.string.forge_worktree_hint)
                        }
                        Text(hint, style = MaterialTheme.typography.labelSmall, color = if (blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (requested && !blocked) {
                            AssistChip(
                                onClick = { branchSheetOpen = true },
                                label = {
                                    Text(
                                        base?.let { stringResource(R.string.forge_worktree_base, it) }
                                            ?: stringResource(R.string.forge_worktree_base_head),
                                        maxLines = 1,
                                    )
                                },
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

/** A padded Material3 card for the worktree dock's own status surfaces. */
@Composable
private fun ForgeDockCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    OutlinedCard(
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
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
    val created = worktree.created
    ForgeDockCard {
        Text(stringResource(R.string.forge_worktree_missing), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
        created?.let {
            Text(stringResource(R.string.forge_worktree_branch, it.branch), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        worktree.restoration?.let {
            Text(
                stringResource(
                    if (it.phase == "checkout") R.string.forge_worktree_restoring_checkout
                    else R.string.forge_worktree_restoring_prepare,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = onRestore,
                enabled = !busy && worktree.restoration == null,
            ) { Text(stringResource(R.string.forge_worktree_restore)) }
            TextButton(
                onClick = onContinue,
                enabled = !busy && worktree.restoration == null,
            ) { Text(stringResource(R.string.forge_worktree_continue_project)) }
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
    var branches by remember { mutableStateOf<ForgeBranches?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sessionId) {
        when (val result = store.branches(sessionId)) {
            is RpcResult.Ok -> branches = result.value
            is RpcResult.Err -> error = forgeErrorTextValue(context, result.error)
        }
    }
    dev.dsh.mobile.mesh.ui.components.AppBottomSheet(
        title = stringResource(R.string.forge_worktree_base_title),
        onDismiss = onDismiss,
    ) {
        if (error != null) {
            Text(error!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            return@AppBottomSheet
        }
        val listing = branches
        if (listing == null) {
            Text(stringResource(R.string.common_loading), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@AppBottomSheet
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
                    Text(name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    if (name == current) StatusBadge(stringResource(R.string.forge_worktree_current))
                }
            }
        }
    }
}
