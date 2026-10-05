package dev.dsh.mobile.mesh.ui.screens.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.core.session.CommandNode
import dev.dsh.mobile.mesh.ui.components.DisclosureRow
import dev.dsh.mobile.mesh.ui.components.DisclosureState
import dev.dsh.mobile.mesh.ui.components.FeatherIcons
import dev.dsh.mobile.mesh.ui.components.StateDot
import dev.dsh.mobile.mesh.ui.components.StateDotState
import dev.dsh.mobile.mesh.ui.theme.DsTheme
import dev.dsh.mobile.mesh.ui.theme.DsType
import kotlinx.serialization.json.JsonObject
import androidx.compose.ui.res.stringResource

@Composable
internal fun CommandActivityRow(row: ActivityRow.Command, context: ChatNodeContext) {
    val source = row.run ?: row.done ?: return
    val run = row.run?.data as? JsonObject
    val done = row.done?.data as? JsonObject
    val name = run?.get("name").asString() ?: done?.get("name").asString() ?: source.kind
    val summary = run?.get("text").asString() ?: done?.get("text").asString()
    val status = commandDoneStatus(row.done)
    val expanded = context.disclosure(DisclosureKeys.commandActivity(source.seq))
    DisclosureRow(
        title = "/$name",
        summary = listOfNotNull(summary, status).joinToString(" · ").ifBlank { null },
        icon = FeatherIcons.Terminal,
        expanded = expanded.expanded,
        onToggle = expanded.onToggle,
        state = if (status == "error" || status == "failed") DisclosureState.Error else DisclosureState.Idle,
    ) {
        Column(Modifier.padding(start = 28.dp, top = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            row.run?.let { CommandPayload("run", it) }
            row.done?.let { CommandPayload("done", it) }
        }
    }
}

@Composable
private fun CommandPayload(label: String, node: CommandNode) {
    Text(
        "$label · ${node.data}",
        style = DsType.caption11.copy(fontFamily = DsType.codeFont),
        color = DsTheme.colors.labelCaption,
    )
}

@Composable
internal fun WorkflowActivityRow(row: ActivityRow.Workflow, context: ChatNodeContext) {
    val activity = remember(row.events) { workflowActivity(row.events) }
    val colors = DsTheme.colors
    val expanded = context.disclosure(DisclosureKeys.workflowActivity(row.anchorSeq))
    DisclosureRow(
        title = stringResource(R.string.workflow_title),
        summary = listOfNotNull(activity.name, workflowStatusLabel(activity.status)).joinToString(" · ").ifBlank { null },
        icon = FeatherIcons.GitBranch,
        expanded = expanded.expanded,
        onToggle = expanded.onToggle,
        state = when (activity.status) {
            "running" -> DisclosureState.Running
            "error", "failed" -> DisclosureState.Error
            else -> DisclosureState.Idle
        },
    ) {
        activity.members.forEach { member ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 28.dp, top = 2.dp)
                    .clickable(enabled = member.childId != null) { member.childId?.let(context.onOpenSubagent) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StateDot(workflowMemberDot(member.status), size = 8.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    member.label ?: member.childId ?: member.seq?.toString().orEmpty(),
                    style = DsType.small13, color = colors.labelSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                member.status?.let { Text(workflowStatusLabel(it) ?: it, style = DsType.caption11, color = colors.labelTertiary) }
            }
        }
    }
}
