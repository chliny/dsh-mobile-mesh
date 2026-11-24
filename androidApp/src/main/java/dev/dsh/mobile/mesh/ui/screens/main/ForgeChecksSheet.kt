package dev.dsh.mobile.mesh.ui.screens.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.core.wire.RpcError
import dev.dsh.mobile.mesh.core.wire.RpcResult
import dev.dsh.mobile.mesh.core.wire.dto.ForgeChangeRequestCheck
import dev.dsh.mobile.mesh.core.wire.dto.ForgeChangeRequestSnapshot
import dev.dsh.mobile.mesh.ui.components.AppBottomSheet
import dev.dsh.mobile.mesh.ui.components.StateDot
import dev.dsh.mobile.mesh.ui.components.StateDotState
import dev.dsh.mobile.mesh.ui.components.StatusBadge
import dev.dsh.mobile.mesh.ui.rememberForgeStore
import java.time.Instant
import kotlinx.coroutines.launch

/** Complete checks of one change request, with rerun when the provider allows it. */
@Composable
internal fun ForgeChecksSheet(
    sessionId: String,
    url: String,
    headSha: String,
    preview: ForgeChangeRequestSnapshot?,
    showToast: (String) -> Unit,
    onChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val store = rememberForgeStore()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<RpcError?>(null) }
    var full by remember { mutableStateOf<ForgeChangeRequestSnapshot?>(null) }
    var rerunBusy by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(sessionId, url, headSha, reload) {
        loading = true
        error = null
        when (val result = store.checks(sessionId, url, headSha)) {
            is RpcResult.Ok -> full = result.value
            is RpcResult.Err -> error = result.error
        }
        loading = false
    }

    val snapshot = full ?: preview
    val checks = snapshot?.checks.orEmpty()
    AppBottomSheet(
        title = stringResource(R.string.forge_checks_title),
        onDismiss = onDismiss,
        trailing = {
            IconButton(onClick = { reload++ }) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.forge_refresh),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) {
        if (loading && full == null) {
            Text(stringResource(R.string.forge_checks_loading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@AppBottomSheet
        }
        error?.let {
            Text(forgeErrorText(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { reload++ }) {
                Text(stringResource(R.string.common_retry))
            }
            return@AppBottomSheet
        }
        if (checks.isEmpty()) {
            Text(stringResource(R.string.forge_checks_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(count = checks.size, key = { it }) { index ->
                    val check = checks[index]
                    ForgeCheckRow(check, onOpen = check.url?.let { link -> { runCatching { uriHandler.openUri(link) } } })
                }
                if (snapshot?.checksTruncated == true) {
                    item(key = "truncated") {
                        Text(stringResource(R.string.forge_checks_truncated), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        val canRerun = snapshot?.canRerunChecks == true && checks.any { it.state == "failure" } &&
            checks.none { it.state == "pending" }
        if (canRerun) {
            OutlinedButton(
                onClick = {
                    rerunBusy = true
                    scope.launch {
                        when (val result = store.rerunChecks(sessionId, url, headSha)) {
                            is RpcResult.Ok -> {
                                showToast(context.getString(R.string.forge_action_requested))
                                onChanged()
                                reload++
                            }
                            is RpcResult.Err -> showToast(forgeErrorTextValue(context, result.error))
                        }
                        rerunBusy = false
                    }
                },
                enabled = !rerunBusy,
            ) { Text(stringResource(R.string.forge_checks_rerun)) }
        }
    }
}

@Composable
private fun ForgeCheckRow(check: ForgeChangeRequestCheck, onOpen: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StateDot(forgeCheckDot(check.state), size = 8.dp)
        Column(Modifier.weight(1f)) {
            Text(
                check.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val detail = listOfNotNull(
                check.workflow?.takeIf { it.isNotBlank() },
                stringResource(forgeCheckStateLabel(check.state)),
                forgeDuration(check.startedAt, check.completedAt),
            ).joinToString(" · ")
            if (detail.isNotEmpty()) {
                Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (check.state == "failure") {
            StatusBadge(
                text = stringResource(R.string.forge_check_failure),
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

private fun forgeCheckDot(state: String): StateDotState = when (state) {
    "failure" -> StateDotState.Error
    "success" -> StateDotState.Done
    "pending" -> StateDotState.Running
    "skipped", "cancelled", "neutral" -> StateDotState.Idle
    else -> StateDotState.Idle
}

private fun forgeCheckStateLabel(state: String): Int = when (state) {
    "failure" -> R.string.forge_check_failure
    "success" -> R.string.forge_check_success
    "pending" -> R.string.forge_check_pending
    "skipped" -> R.string.forge_check_skipped
    "cancelled" -> R.string.forge_check_cancelled
    else -> R.string.forge_check_neutral
}

private fun forgeDuration(startedAt: String?, completedAt: String?): String? {
    val start = parseInstant(startedAt) ?: return null
    val end = parseInstant(completedAt) ?: Instant.now()
    val seconds = java.time.Duration.between(start, end).seconds.coerceAtLeast(0)
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }
}

private fun parseInstant(value: String?): Instant? =
    value?.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() }
