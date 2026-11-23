package dev.dsh.mobile.mesh.ui.screens.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import dev.dsh.mobile.mesh.core.forge.forgeReviewDiscardPendingWrite
import dev.dsh.mobile.mesh.core.forge.forgeReviewSubmitPendingWrite
import dev.dsh.mobile.mesh.core.forge.forgeSubmitReviewRequest
import dev.dsh.mobile.mesh.core.wire.RpcResult
import dev.dsh.mobile.mesh.core.wire.dto.ForgeReviewData
import dev.dsh.mobile.mesh.ui.components.DsBottomSheet
import dev.dsh.mobile.mesh.ui.components.DsButton
import dev.dsh.mobile.mesh.ui.components.DsButtonSize
import dev.dsh.mobile.mesh.ui.components.DsButtonVariant
import dev.dsh.mobile.mesh.ui.components.DsPill
import dev.dsh.mobile.mesh.ui.rememberForgeStore
import dev.dsh.mobile.mesh.ui.theme.DsTheme
import dev.dsh.mobile.mesh.ui.theme.DsType
import kotlinx.coroutines.launch

/** Submit or discard the signed-in user's pending GitHub review. */
@Composable
internal fun ForgeSubmitReviewSheet(
    sessionId: String,
    url: String,
    headSha: String,
    data: ForgeReviewData,
    showToast: (String) -> Unit,
    onChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val store = rememberForgeStore()
    val context = LocalContext.current
    val colors = DsTheme.colors
    val scope = rememberCoroutineScope()
    val pending = data.pendingReview
    var event by remember { mutableStateOf("comment") }
    var body by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    fun submit() {
        if ((event == "request-changes" || (pending == null && event == "comment")) && body.isBlank()) {
            showToast(context.getString(R.string.forge_review_need_body))
            return
        }
        busy = true
        scope.launch {
            if (pending == null) {
                when (val result = store.submitReview(
                    sessionId,
                    url,
                    forgeSubmitReviewRequest(headSha, event, body.trim(), emptyList()),
                )) {
                    is RpcResult.Ok -> {
                        val value = result.value
                        if (value.ok) {
                            showToast(context.getString(R.string.forge_review_submitted))
                            onChanged()
                            onDismiss()
                        } else {
                            showToast(forgeReviewWriteErrorText(context, value.code, value.message))
                        }
                    }
                    is RpcResult.Err -> showToast(forgeErrorTextValue(context, result.error))
                }
            } else {
                when (val result = store.reviewWrite(
                    sessionId,
                    url,
                    forgeReviewSubmitPendingWrite(headSha, pending.id, event, body.trim()),
                )) {
                    is RpcResult.Ok -> {
                        val value = result.value
                        if (value.ok) {
                            showToast(context.getString(R.string.forge_review_submitted))
                            onChanged()
                            onDismiss()
                        } else {
                            showToast(forgeReviewWriteErrorText(context, value.code, value.message))
                        }
                    }
                    is RpcResult.Err -> showToast(forgeErrorTextValue(context, result.error))
                }
            }
            busy = false
        }
    }

    fun discard() {
        busy = true
        scope.launch {
            when (val result = store.reviewWrite(
                sessionId,
                url,
                forgeReviewDiscardPendingWrite(headSha, pending?.id.orEmpty()),
            )) {
                is RpcResult.Ok -> {
                    val value = result.value
                    if (value.ok) {
                        showToast(context.getString(R.string.forge_review_discarded))
                        onChanged()
                        onDismiss()
                    } else {
                        showToast(forgeReviewWriteErrorText(context, value.code, value.message))
                    }
                }
                is RpcResult.Err -> showToast(forgeErrorTextValue(context, result.error))
            }
            busy = false
        }
    }

    DsBottomSheet(
        title = stringResource(if (pending == null) R.string.forge_review_submit else R.string.forge_review_pending),
        onDismiss = onDismiss,
    ) {
        if (pending != null) {
            Text(
                stringResource(R.string.forge_review_pending_count, pending.comments),
                style = DsType.small13,
                color = colors.labelSecondary,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            DsPill(
                text = stringResource(R.string.forge_review_event_comment),
                selected = event == "comment",
                onClick = { event = "comment" },
            )
            DsPill(
                text = stringResource(R.string.forge_review_event_approve),
                selected = event == "approve",
                onClick = { event = "approve" },
            )
            DsPill(
                text = stringResource(R.string.forge_review_event_request_changes),
                selected = event == "request-changes",
                onClick = { event = "request-changes" },
            )
        }
        OutlinedTextField(
            value = body,
            onValueChange = { body = it },
            placeholder = { Text(stringResource(R.string.forge_review_body)) },
            minLines = 2,
            maxLines = 5,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (pending != null) {
                DsButton(
                    text = stringResource(R.string.forge_review_discard),
                    onClick = { confirmDiscard = true },
                    enabled = !busy,
                    variant = DsButtonVariant.Danger,
                    size = DsButtonSize.Small,
                )
            }
            DsButton(
                text = stringResource(R.string.forge_review_submit),
                onClick = ::submit,
                enabled = !busy,
                variant = DsButtonVariant.Primary,
                size = DsButtonSize.Small,
            )
        }
    }

    if (confirmDiscard && pending != null) {
        ConfirmDialog(
            title = stringResource(R.string.forge_review_discard),
            body = stringResource(R.string.forge_review_discard_confirm),
            confirmLabel = stringResource(R.string.common_discard),
            onDismiss = { confirmDiscard = false },
            onConfirm = {
                confirmDiscard = false
                discard()
            },
        )
    }
}

internal fun forgeReviewWriteErrorText(context: android.content.Context, code: String?, message: String?): String =
    when (code) {
        "stale" -> context.getString(R.string.forge_stale_head)
        "unsupported" -> context.getString(R.string.forge_thread_unsupported)
        "unknown" -> context.getString(R.string.forge_review_unknown)
        else -> message ?: context.getString(R.string.forge_thread_unsupported)
    }
