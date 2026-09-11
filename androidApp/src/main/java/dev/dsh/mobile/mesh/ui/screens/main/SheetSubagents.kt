package dev.dsh.mobile.mesh.ui.screens.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.core.session.AssistantMessageNode
import dev.dsh.mobile.mesh.core.session.ChatNode
import dev.dsh.mobile.mesh.core.session.ContextMessageNode
import dev.dsh.mobile.mesh.core.session.ConversationSnapshot
import dev.dsh.mobile.mesh.core.session.UserMessageNode
import dev.dsh.mobile.mesh.core.wire.dto.SubagentListEntry
import dev.dsh.mobile.mesh.data.SessionStore
import dev.dsh.mobile.mesh.ui.components.DsBottomSheet
import dev.dsh.mobile.mesh.ui.components.DsButton
import dev.dsh.mobile.mesh.ui.components.DsButtonSize
import dev.dsh.mobile.mesh.ui.components.DsButtonVariant
import dev.dsh.mobile.mesh.ui.components.DsPill
import dev.dsh.mobile.mesh.ui.components.MarkdownText
import dev.dsh.mobile.mesh.ui.components.StateDot
import dev.dsh.mobile.mesh.ui.components.StateDotState
import dev.dsh.mobile.mesh.ui.components.UserBubble
import dev.dsh.mobile.mesh.ui.theme.DsSpacing
import dev.dsh.mobile.mesh.ui.theme.DsTheme
import dev.dsh.mobile.mesh.ui.theme.DsType
import kotlinx.coroutines.launch

/**
 * The subagent catalog, with the selected child's transcript inline.
 *
 * Only continuable children accept messages; one-shot children and any child whose parent is
 * offline are read-only, which the sheet states rather than offering an input that would be
 * rejected.
 */
@Composable
internal fun SubagentsSheet(
    store: SessionStore,
    entries: List<SubagentListEntry>,
    conversation: ConversationSnapshot?,
    mode: String?,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val colors = DsTheme.colors
    val childId = conversation?.sessionId
    var draft by remember { mutableStateOf("") }
    val childRunning = entries
        .firstOrNull { subagentId(it) == childId }
        ?.let { subagentRunning(it) } == true

    DsBottomSheet(
        title = stringResource(R.string.subagents_title),
        subtitle = entries.size.takeIf { it > 0 }?.toString(),
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (entries.isEmpty()) {
                Text(
                    stringResource(R.string.subagents_empty),
                    style = DsType.caption11,
                    color = colors.labelTertiary,
                )
            }
            entries.forEach { entry ->
                SubagentRow(
                    entry = entry,
                    selected = subagentId(entry) == childId,
                    onClick = {
                        subagentId(entry)?.let { id -> scope.launch { store.openSubagentTranscript(id) } }
                    },
                )
            }

            conversation?.let { child ->
                if (child.nodes.isEmpty()) {
                    Text(
                        stringResource(R.string.common_loading),
                        style = DsType.caption11,
                        color = colors.labelTertiary,
                    )
                } else {
                    Column(Modifier.padding(vertical = DsSpacing.small)) {
                        child.nodes.forEach { node -> SubagentTranscriptRow(node) }
                    }
                }

                if (mode != "continuable") {
                    Text(
                        stringResource(R.string.subagents_readonly),
                        style = DsType.caption11,
                        color = colors.labelTertiary,
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextField(
                            value = draft,
                            onValueChange = { draft = it },
                            modifier = Modifier.weight(1f),
                            placeholder = {
                                Text(stringResource(R.string.subagents_message), style = DsType.std14)
                            },
                            colors = dialogTextFieldColors(),
                        )
                        Spacer(Modifier.width(DsSpacing.small))
                        if (childRunning) {
                            DsButton(
                                text = stringResource(R.string.subagents_interrupt),
                                onClick = {
                                    childId?.let { id -> scope.launch { store.interruptSubagent(id) } }
                                },
                                variant = DsButtonVariant.Danger,
                                size = DsButtonSize.Small,
                            )
                        } else {
                            DsButton(
                                text = "",
                                icon = Icons.Filled.ArrowUpward,
                                onClick = {
                                    val text = draft
                                    val id = childId
                                    if (text.isNotBlank() && id != null) {
                                        scope.launch { store.promptSubagent(id, text) }
                                        draft = ""
                                    }
                                },
                                variant = DsButtonVariant.Info,
                                enabled = draft.isNotBlank() && childId != null,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubagentRow(entry: SubagentListEntry, selected: Boolean, onClick: () -> Unit) {
    val colors = DsTheme.colors
    val modeLabel = when (entry) {
        is SubagentListEntry.ChildOneShot -> stringResource(R.string.subagents_oneshot)
        is SubagentListEntry.ChildContinuable -> stringResource(R.string.subagents_continuable)
        else -> null
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = DsSpacing.xsmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StateDot(if (subagentRunning(entry)) StateDotState.Running else StateDotState.Idle)
        Spacer(Modifier.width(DsSpacing.small))
        Text(
            subagentLabel(entry) ?: subagentId(entry).orEmpty(),
            style = DsType.small13,
            color = colors.labelPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        modeLabel?.let { DsPill(text = it) }
        if (selected) {
            Spacer(Modifier.width(DsSpacing.xsmall))
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun SubagentTranscriptRow(node: ChatNode) {
    when (node) {
        is UserMessageNode -> UserBubble(node.previewText)
        is ContextMessageNode -> Unit
        is AssistantMessageNode -> if (node.plainText.isNotBlank()) MarkdownText(node.plainText)
        else -> Unit
    }
}
