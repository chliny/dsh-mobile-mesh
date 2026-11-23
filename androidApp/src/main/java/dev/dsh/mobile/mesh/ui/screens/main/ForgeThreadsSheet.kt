package dev.dsh.mobile.mesh.ui.screens.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.core.forge.forgeReviewDeleteCommentWrite
import dev.dsh.mobile.mesh.core.forge.forgeReviewEditCommentWrite
import dev.dsh.mobile.mesh.core.forge.forgeReviewReplyWrite
import dev.dsh.mobile.mesh.core.forge.forgeReviewResolveWrite
import dev.dsh.mobile.mesh.core.wire.RpcResult
import dev.dsh.mobile.mesh.core.wire.dto.ForgeReviewComment
import dev.dsh.mobile.mesh.core.wire.dto.ForgeReviewData
import dev.dsh.mobile.mesh.core.wire.dto.ForgeReviewThread
import dev.dsh.mobile.mesh.ui.components.DsBottomSheet
import dev.dsh.mobile.mesh.ui.components.DsButton
import dev.dsh.mobile.mesh.ui.components.DsButtonSize
import dev.dsh.mobile.mesh.ui.components.DsButtonVariant
import dev.dsh.mobile.mesh.ui.components.DsIconButton
import dev.dsh.mobile.mesh.ui.components.DsMenu
import dev.dsh.mobile.mesh.ui.components.DsPill
import dev.dsh.mobile.mesh.ui.components.MarkdownText
import dev.dsh.mobile.mesh.ui.components.MenuItem
import dev.dsh.mobile.mesh.ui.components.relativeTime
import dev.dsh.mobile.mesh.ui.rememberForgeStore
import dev.dsh.mobile.mesh.ui.theme.DsTheme
import dev.dsh.mobile.mesh.ui.theme.DsType
import java.time.Instant
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

private enum class ForgeThreadFilter { Unresolved, All, Resolved }

/**
 * Review threads of one change request.
 *
 * A list-detail sheet rather than the Web's inline thread cards: a phone shows one file at a time,
 * so a thread list keeps the file's diff readable while the detail still exposes every read/write
 * the Host offers (reply, resolve, edit, delete, quote into the composer).
 */
@Composable
internal fun ForgeThreadsSheet(
    sessionId: String,
    url: String,
    headSha: String,
    data: ForgeReviewData?,
    showToast: (String) -> Unit,
    onChanged: () -> Unit,
    onDismiss: () -> Unit,
    pathFilter: String? = null,
    threadIds: Set<String>? = null,
) {
    val store = rememberForgeStore()
    val colors = DsTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var filter by remember { mutableStateOf(ForgeThreadFilter.Unresolved) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var reply by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<ForgeReviewComment?>(null) }
    var editBody by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<ForgeReviewComment?>(null) }
    val selected = data?.threads?.firstOrNull { it.id == selectedId }

    fun write(write: JsonObject, successRes: Int) {
        scope.launch {
            when (val result = store.reviewWrite(sessionId, url, write)) {
                is RpcResult.Ok -> {
                    val value = result.value
                    if (value.ok) {
                        showToast(context.getString(successRes))
                        editing = null
                        reply = ""
                        onChanged()
                    } else {
                        showToast(forgeReviewWriteErrorText(context, value.code, value.message))
                    }
                }
                is RpcResult.Err -> showToast(forgeErrorTextValue(context, result.error))
            }
        }
    }

    DsBottomSheet(title = stringResource(R.string.forge_discussions_title), onDismiss = onDismiss) {
        if (data == null) {
            Text(stringResource(R.string.common_loading), style = DsType.small13, color = colors.labelSecondary)
            return@DsBottomSheet
        }
        if (selected != null) {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            DsButton(
                text = stringResource(R.string.common_back),
                onClick = { selectedId = null; editing = null; reply = "" },
                variant = DsButtonVariant.Ghost,
                size = DsButtonSize.Small,
            )
            Text(selected.path, style = DsType.small13Strong, color = colors.labelPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(forgeThreadRange(selected), style = DsType.caption11, color = colors.labelTertiary)
                if (selected.resolved) DsPill(stringResource(R.string.forge_thread_resolved))
                if (selected.current == null && selected.subject == "line") {
                    DsPill(stringResource(R.string.forge_thread_outdated), warn = true)
                }
            }
            selected.comments.forEach { comment ->
                ForgeThreadCommentRow(
                    comment = comment,
                    editing = editing?.id == comment.id,
                    editBody = editBody,
                    onEditBody = { editBody = it },
                    onStartEdit = {
                        editing = comment
                        editBody = comment.body
                    },
                    onCancelEdit = { editing = null },
                    onSaveEdit = {
                        write(
                            forgeReviewEditCommentWrite(headSha, comment.id, editBody.trim()),
                            R.string.forge_comment_saved,
                        )
                    },
                    onDelete = { deleting = comment },
                    onOpen = comment.url?.let { link -> { runCatching { uriHandler.openUri(link) } } },
                )
            }
            if (selected.comments.isEmpty()) {
                Text(stringResource(R.string.forge_thread_no_comments), style = DsType.caption11, color = colors.labelTertiary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selected.viewerCanResolve) {
                    DsButton(
                        text = stringResource(
                            if (selected.resolved) R.string.forge_thread_unresolve else R.string.forge_thread_resolve,
                        ),
                        onClick = {
                            write(
                                forgeReviewResolveWrite(headSha, selected.id, !selected.resolved),
                                if (selected.resolved) R.string.forge_thread_reopened else R.string.forge_thread_resolved_notice,
                            )
                        },
                        variant = DsButtonVariant.Outline,
                        size = DsButtonSize.Small,
                    )
                }
                DsButton(
                    text = stringResource(R.string.forge_thread_add_to_input),
                    onClick = {
                        store.addThreadReference(sessionId, selected, url)
                        showToast(context.getString(R.string.forge_thread_reference_added))
                    },
                    variant = DsButtonVariant.Ghost,
                    size = DsButtonSize.Small,
                )
            }
            if (selected.viewerCanReply) {
                OutlinedTextField(
                    value = reply,
                    onValueChange = { reply = it },
                    placeholder = { Text(stringResource(R.string.forge_thread_reply_placeholder)) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                DsButton(
                    text = stringResource(R.string.forge_thread_reply),
                    onClick = {
                        if (reply.isNotBlank()) {
                            write(
                                forgeReviewReplyWrite(
                                    headSha = headSha,
                                    threadId = selected.id,
                                    body = reply.trim(),
                                    pending = data.pendingReview != null,
                                ),
                                R.string.forge_reply_sent,
                            )
                        }
                    },
                    enabled = reply.isNotBlank(),
                    variant = DsButtonVariant.Primary,
                    size = DsButtonSize.Small,
                )
            }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                DsPill(
                    stringResource(R.string.forge_threads_unresolved),
                    selected = filter == ForgeThreadFilter.Unresolved,
                    onClick = { filter = ForgeThreadFilter.Unresolved },
                )
                DsPill(
                    stringResource(R.string.forge_threads_all),
                    selected = filter == ForgeThreadFilter.All,
                    onClick = { filter = ForgeThreadFilter.All },
                )
                DsPill(
                    stringResource(R.string.forge_threads_resolved),
                    selected = filter == ForgeThreadFilter.Resolved,
                    onClick = { filter = ForgeThreadFilter.Resolved },
                )
            }
            val threads = data.threads
                .filter { pathFilter == null || it.path == pathFilter }
                .filter { threadIds == null || it.id in threadIds }
                .filter {
                    when (filter) {
                        ForgeThreadFilter.Unresolved -> !it.resolved
                        ForgeThreadFilter.Resolved -> it.resolved
                        ForgeThreadFilter.All -> true
                    }
                }
                .sortedWith(compareBy({ it.path }, { (it.current ?: it.original)?.startLine ?: 0 }))
            if (threads.isEmpty()) {
                Text(stringResource(R.string.forge_thread_empty), style = DsType.small13, color = colors.labelSecondary)
            } else {
                LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(threads, key = { it.id }) { thread ->
                        ForgeThreadRow(thread) { selectedId = thread.id }
                    }
                    if (data.truncated) {
                        item(key = "truncated") {
                            Text(stringResource(R.string.forge_threads_truncated), style = DsType.caption11, color = colors.labelTertiary)
                        }
                    }
                }
            }
        }
    }

    deleting?.let { comment ->
        ConfirmDialog(
            title = stringResource(R.string.forge_thread_delete_comment),
            body = stringResource(R.string.forge_thread_delete_confirm),
            confirmLabel = stringResource(R.string.common_delete),
            onDismiss = { deleting = null },
            onConfirm = {
                deleting = null
                write(forgeReviewDeleteCommentWrite(headSha, comment.id), R.string.forge_comment_deleted)
            },
        )
    }
}

@Composable
private fun ForgeThreadRow(thread: ForgeReviewThread, onClick: () -> Unit) {
    val colors = DsTheme.colors
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(thread.path, style = DsType.small13, color = colors.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(forgeThreadRange(thread), style = DsType.caption11, color = colors.labelTertiary)
            if (thread.resolved) DsPill(stringResource(R.string.forge_thread_resolved))
            if (thread.current == null && thread.subject == "line") DsPill(stringResource(R.string.forge_thread_outdated), warn = true)
        }
        thread.comments.firstOrNull()?.let { comment ->
            Text(
                comment.body.lineSequence().firstOrNull().orEmpty(),
                style = DsType.caption11,
                color = colors.labelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            stringResource(R.string.forge_thread_comment_count, thread.comments.size),
            style = DsType.caption11,
            color = colors.labelTertiary,
        )
    }
}

@Composable
private fun ForgeThreadCommentRow(
    comment: ForgeReviewComment,
    editing: Boolean,
    editBody: String,
    onEditBody: (String) -> Unit,
    onStartEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onSaveEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpen: (() -> Unit)?,
) {
    val colors = DsTheme.colors
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(comment.author?.login ?: stringResource(R.string.forge_thread_unknown_author), style = DsType.caption11Strong, color = colors.labelSecondary)
            comment.author?.let {
                if (it.bot) DsPill(stringResource(R.string.forge_thread_bot))
            }
            if (comment.pending) DsPill(stringResource(R.string.forge_thread_pending))
            Text(threadTime(comment.createdAt), style = DsType.caption11, color = colors.labelTertiary)
            if (comment.viewerCanEdit || comment.viewerCanDelete || onOpen != null) {
                DsMenu(
                    anchor = { open ->
                        DsIconButton(
                            icon = Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.forge_more),
                            onClick = open,
                            tint = colors.labelTertiary,
                            iconSize = 16.dp,
                        )
                    },
                    items = buildList {
                        onOpen?.let { add(MenuItem(stringResource(R.string.forge_thread_view_in_browser), onClick = it)) }
                        if (comment.viewerCanEdit) add(MenuItem(stringResource(R.string.forge_thread_edit_comment), onClick = onStartEdit))
                        if (comment.viewerCanDelete) add(MenuItem(stringResource(R.string.forge_thread_delete_comment), danger = true, onClick = onDelete))
                    },
                )
            }
        }
        if (editing) {
            OutlinedTextField(
                value = editBody,
                onValueChange = onEditBody,
                minLines = 2,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DsButton(
                    text = stringResource(R.string.common_save),
                    onClick = onSaveEdit,
                    enabled = editBody.isNotBlank(),
                    variant = DsButtonVariant.Primary,
                    size = DsButtonSize.Small,
                )
                DsButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = onCancelEdit,
                    variant = DsButtonVariant.Ghost,
                    size = DsButtonSize.Small,
                )
            }
        } else {
            MarkdownText(comment.body, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun threadTime(iso: String): String {
    val millis = runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()
    return if (millis != null) relativeTime(millis) else iso.take(10)
}

@Composable
internal fun forgeThreadRange(thread: ForgeReviewThread): String {
    val anchor = thread.current ?: thread.original
    if (thread.subject == "file" || anchor == null) return stringResource(R.string.forge_thread_file)
    val range = if (anchor.startLine == anchor.endLine) anchor.startLine.toString() else "${anchor.startLine}–${anchor.endLine}"
    return "${thread.side}$range"
}
