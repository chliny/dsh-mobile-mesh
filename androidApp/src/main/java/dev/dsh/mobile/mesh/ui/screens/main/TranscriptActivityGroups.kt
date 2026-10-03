package dev.dsh.mobile.mesh.ui.screens.main

import dev.dsh.mobile.mesh.core.session.ChatNode
import dev.dsh.mobile.mesh.core.session.CommandNode
import dev.dsh.mobile.mesh.core.session.WorkflowNode
import kotlinx.serialization.json.JsonObject

/** Presentation-only activity rows; source nodes and their sequence numbers remain unchanged. */
internal sealed interface ActivityRow {
    val anchorSeq: Long
    data class Node(val node: ChatNode) : ActivityRow {
        override val anchorSeq: Long = node.seq
    }
    data class Command(val run: CommandNode?, val done: CommandNode?) : ActivityRow {
        override val anchorSeq: Long = listOfNotNull(run?.seq, done?.seq).min()
    }
    data class Workflow(val events: List<WorkflowNode>) : ActivityRow {
        override val anchorSeq: Long = events.first().seq
    }
}

private fun ChatNode.field(name: String): String? = when (this) {
    is CommandNode -> (data as? JsonObject)?.get(name).asString()?.takeIf { it.isNotBlank() }
    is WorkflowNode -> (data as? JsonObject)?.get(name).asString()?.takeIf { it.isNotBlank() }
    else -> null
}

/** Match by durable IDs only; old command events without an ID retain their independent rows. */
internal fun groupTranscriptActivity(nodes: List<ChatNode>): List<ActivityRow> {
    val runs = mutableMapOf<String, Int>()
    val dones = mutableMapOf<String, Int>()
    val workflows = mutableMapOf<String, MutableList<Int>>()
    nodes.forEachIndexed { index, node ->
        when (node) {
            is CommandNode -> {
                val id = node.field("commandId") ?: return@forEachIndexed
                if (node.kind == "command/run") runs.putIfAbsent(id, index)
                if (node.kind == "command/done") dones.putIfAbsent(id, index)
            }
            is WorkflowNode -> node.field("runId")?.let { id ->
                workflows.getOrPut(id) { mutableListOf() }.add(index)
            }
            else -> Unit
        }
    }
    val rows = mutableListOf<ActivityRow>()
    val consumed = mutableSetOf<Int>()
    nodes.forEachIndexed { index, node ->
        if (index in consumed) return@forEachIndexed
        when (node) {
            is CommandNode -> {
                val id = node.field("commandId")
                val match = id?.let { key ->
                    if (node.kind == "command/run") dones[key] else if (node.kind == "command/done") runs[key] else null
                }
                val partner = match?.takeIf { it !in consumed && it != index }?.let { nodes[it] as? CommandNode }
                if (partner != null) consumed.add(match)
                rows.add(ActivityRow.Command(
                    run = if (node.kind == "command/run") node else partner?.takeIf { it.kind == "command/run" },
                    done = if (node.kind == "command/done") node else partner?.takeIf { it.kind == "command/done" },
                ))
            }
            is WorkflowNode -> {
                val indices = node.field("runId")?.let { workflows[it] }.orEmpty()
                if (indices.isNotEmpty()) {
                    consumed.addAll(indices)
                    rows.add(ActivityRow.Workflow(indices.map { nodes[it] as WorkflowNode }))
                } else rows.add(ActivityRow.Workflow(listOf(node)))
            }
            else -> rows.add(ActivityRow.Node(node))
        }
    }
    return rows
}

internal fun commandDoneStatus(done: CommandNode?): String? =
    (done?.data as? JsonObject)?.let { data ->
        data["kind"].asString() ?: data["status"].asString() ?: data["outcome"].asString()
    }

internal data class WorkflowActivityMember(
    val seq: Int?, val label: String?, val childId: String?, val status: String?,
)
internal data class WorkflowActivity(
    val name: String?, val status: String?, val members: List<WorkflowActivityMember>,
)

/** Merge lifecycle events by member seq, including end-before-start and partial history pages. */
internal fun workflowActivity(events: List<WorkflowNode>): WorkflowActivity {
    var name: String? = null
    var status: String? = null
    val members = linkedMapOf<Int, WorkflowActivityMember>()
    val unnumbered = mutableListOf<WorkflowActivityMember>()
    events.forEach { event ->
        val data = event.data as? JsonObject ?: return@forEach
        // Older snapshots carry ready-made members/phases rather than separate lifecycle events.
        // Preserve those disclosures, including their child navigation links, while preferring the
        // seq-keyed lifecycle entries whenever both formats are present.
        val legacyMembers = parseWorkflowMembers(event.data)
        if (data["members"] != null || data["phases"] != null || event.kind !in setOf(
                "tool-workflow/run-start", "tool-workflow/run-end",
                "tool-workflow/agent-start", "tool-workflow/agent-end",
            )) {
            name = data["name"].asString() ?: name
            status = data["status"].asString() ?: data["stopReason"].asString()
                ?: data["outcome"].asString() ?: status
            legacyMembers.forEach { member ->
                unnumbered.add(WorkflowActivityMember(null, member.label, member.childId, member.status))
            }
        }
        when (event.kind) {
            "tool-workflow/run-start" -> { name = data["name"].asString() ?: name; if (status == null) status = "running" }
            "tool-workflow/run-end" -> status = data["stopReason"].asString() ?: status
            "tool-workflow/agent-start", "tool-workflow/agent-end" -> {
                val seq = data["seq"].asString()?.toIntOrNull()
                val old = seq?.let { members[it] }
                val member = WorkflowActivityMember(
                    seq, data["label"].asString() ?: old?.label,
                    data["childId"].asString() ?: old?.childId,
                    data["outcome"].asString() ?: old?.status ?: if (event.kind.endsWith("agent-start")) "running" else null,
                )
                if (seq == null) unnumbered.add(member) else members[seq] = member
            }
        }
    }
    return WorkflowActivity(name, status, members.toSortedMap().values.toList() + unnumbered)
}
