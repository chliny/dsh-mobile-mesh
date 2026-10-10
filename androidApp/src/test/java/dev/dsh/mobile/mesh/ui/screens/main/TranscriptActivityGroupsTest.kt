package dev.dsh.mobile.mesh.ui.screens.main

import dev.dsh.mobile.mesh.core.session.CommandNode
import dev.dsh.mobile.mesh.core.session.WorkflowNode
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class TranscriptActivityGroupsTest {
    private fun command(seq: Long, kind: String, id: String? = null) = CommandNode(seq, kind, buildJsonObject {
        put("name", "inspect")
        if (id != null) put("commandId", id)
    })
    private fun workflow(seq: Long, kind: String, runId: String?, memberSeq: Int? = null,
                         label: String? = null, childId: String? = null, outcome: String? = null) =
        WorkflowNode(seq, "tool-workflow/$kind", buildJsonObject {
            if (runId != null) put("runId", runId)
            if (memberSeq != null) put("seq", memberSeq)
            if (label != null) put("label", label)
            if (childId != null) put("childId", childId)
            if (outcome != null) put(if (kind == "run-end") "stopReason" else "outcome", outcome)
            if (kind == "run-start") put("name", "review")
        })

    @Test fun `commands pair by id across unrelated events without merging old no-id traffic`() {
        val events = listOf(command(1, "command/run", "a"), command(2, "command/run", "b"),
            command(3, "command/done", "b"), command(4, "command/done", "a"),
            command(5, "command/run"), command(6, "command/done"))
        val groups = groupTranscriptActivity(events).filterIsInstance<ActivityRow.Command>()
        assertEquals(4, groups.size)
        assertEquals(listOf(1L to 4L, 2L to 3L, 5L to null, null to 6L),
            groups.map { it.run?.seq to it.done?.seq })
    }

    @Test fun `command and workflow row keys stay stable when older start events arrive`() {
        val commandDoneOnly = groupTranscriptActivity(listOf(command(90, "command/done", "cmd-1")))
            .single() as ActivityRow.Command
        val commandPaired = groupTranscriptActivity(listOf(
            command(10, "command/run", "cmd-1"), command(90, "command/done", "cmd-1"),
        )).single() as ActivityRow.Command
        assertEquals(TranscriptRow.Command(commandDoneOnly).key, TranscriptRow.Command(commandPaired).key)
        assertEquals(
            DisclosureKeys.commandActivity(commandDoneOnly.commandId, commandDoneOnly.anchorSeq),
            DisclosureKeys.commandActivity(commandPaired.commandId, commandPaired.anchorSeq),
        )

        val workflowEndOnly = groupTranscriptActivity(listOf(workflow(90, "run-end", "run-1")))
            .single() as ActivityRow.Workflow
        val workflowPaired = groupTranscriptActivity(listOf(
            workflow(10, "run-start", "run-1"), workflow(90, "run-end", "run-1"),
        )).single() as ActivityRow.Workflow
        assertEquals(TranscriptRow.Workflow(workflowEndOnly).key, TranscriptRow.Workflow(workflowPaired).key)
        assertEquals(
            DisclosureKeys.workflowActivity(workflowEndOnly.runId, workflowEndOnly.anchorSeq),
            DisclosureKeys.workflowActivity(workflowPaired.runId, workflowPaired.anchorSeq),
        )
    }

    @Test fun `command done kind error supplies failure status`() {
        val done = CommandNode(2, "command/done", buildJsonObject {
            put("commandId", "a")
            put("kind", "error")
            put("text", "Failure details")
        })
        assertEquals("error", commandDoneStatus(done))
        val paired = groupTranscriptActivity(listOf(command(1, "command/run", "a"), done))
            .single() as ActivityRow.Command
        assertEquals("error", commandDoneStatus(paired.done))
    }

    @Test fun `orphan done and partial page retain their own command row`() {
        val done = command(11, "command/done", "not-loaded")
        assertEquals(listOf(null to 11L), groupTranscriptActivity(listOf(done))
            .filterIsInstance<ActivityRow.Command>().map { it.run?.seq to it.done?.seq })
    }

    @Test fun `workflow lifecycle groups by runId and settlements join member sequence`() {
        val events = listOf(workflow(1, "run-start", "a"), workflow(2, "agent-start", "a", 2, "Second", "child-2"),
            workflow(3, "run-start", "b"), workflow(4, "agent-end", "a", 2, outcome = "failed"),
            workflow(5, "agent-end", "a", 1, outcome = "completed"),
            workflow(6, "agent-start", "a", 1, "First", "child-1"),
            workflow(7, "run-end", "a", outcome = "error"), workflow(8, "run-end", "b", outcome = "completed"))
        val groups = groupTranscriptActivity(events).filterIsInstance<ActivityRow.Workflow>()
        assertEquals(listOf(listOf(1L, 2L, 4L, 5L, 6L, 7L), listOf(3L, 8L)),
            groups.map { it.events.map(WorkflowNode::seq) })
        val activity = workflowActivity(groups.first().events)
        assertEquals("review", activity.name)
        assertEquals("error", activity.status)
        assertEquals(listOf(1, 2), activity.members.map { it.seq })
        assertEquals(listOf("child-1", "child-2"), activity.members.map { it.childId })
        assertEquals(listOf("completed", "failed"), activity.members.map { it.status })
    }

    @Test fun `orphan lifecycle events and old runId-less events remain visible`() {
        val events = listOf(workflow(1, "agent-end", "orphan", 5, outcome = "cancelled"),
            workflow(2, "agent-start", null, 1), workflow(3, "run-end", null, outcome = "error"))
        val groups = groupTranscriptActivity(events).filterIsInstance<ActivityRow.Workflow>()
        assertEquals(3, groups.size)
        assertEquals("cancelled", workflowActivity(groups.first().events).members.single().status)
    }

    @Test fun `legacy workflow embedded members and phases retain statuses and child links`() {
        val embedded = WorkflowNode(10, "tool-workflow/legacy", buildJsonObject {
            put("name", "old run")
            put("status", "completed")
            put("members", buildJsonArray {
                add(buildJsonObject {
                    put("label", "Member A")
                    put("childId", "child-a")
                    put("status", "failed")
                })
            })
        })
        val phases = WorkflowNode(11, "tool-workflow/legacy", buildJsonObject {
            put("phases", buildJsonArray {
                add(buildJsonObject { put("name", "Phase B"); put("outcome", "completed") })
            })
        })
        val rows = groupTranscriptActivity(listOf(embedded, phases)).filterIsInstance<ActivityRow.Workflow>()
        assertEquals(2, rows.size)
        val activity = workflowActivity(rows.first().events)
        assertEquals("old run", activity.name)
        assertEquals("completed", activity.status)
        assertEquals(listOf(WorkflowActivityMember(null, "Member A", "child-a", "failed")), activity.members)
        assertEquals(listOf(WorkflowActivityMember(null, "Phase B", null, "completed")),
            workflowActivity(rows.last().events).members)
    }

    @Test fun `collapsed process boundaries prevent grouping hidden members with visible events`() {
        val run = command(1, "command/run", "a")
        val done = command(3, "command/done", "a")
        val parts = listOf(TranscriptPart.Node(run), TranscriptPart.Process(2, listOf(done)))
        val collapsed = buildTranscriptRows(parts, emptyMap())
        assertEquals(2, collapsed.size)
        assertNull((collapsed.first() as TranscriptRow.Command).activity.done)
        val expanded = buildTranscriptRows(parts, mapOf(2L to true))
        assertEquals(3, expanded.size)
        assertNull((expanded.first() as TranscriptRow.Command).activity.done)
        assertNull((expanded.last() as TranscriptRow.Command).activity.run)
    }
}
