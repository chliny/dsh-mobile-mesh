package dev.dsh.mobile.mesh.ui.screens.main

import dev.dsh.mobile.mesh.core.session.ChatNode
import dev.dsh.mobile.mesh.core.session.TodoNode
import dev.dsh.mobile.mesh.core.session.ToolCallNode
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoPresentationTest {
    private fun call(seq: Long, args: String) = ToolCallNode(seq, "call-$seq", "todo_write", args, 1, 1)
    private fun write(seq: Long, todos: String) = TodoNode(seq, Json.parseToJsonElement(todos))

    @Test fun `summary names first parallel active task and counts others`() {
        assertEquals(TodoCallSummary(1, 4, "build", 2), todoCallSummary("""{"todos":[{"content":"done","status":"completed"},{"content":"build","status":"in_progress"},{"content":"test","status":"in_progress"},{"content":"ship","status":"in_progress"}]}"""))
        assertEquals(TodoCallSummary(0, 2, null, 0), todoCallSummary("""{"todos":[{"status":"in_progress"},{"content":"valid","status":"in_progress"}]}"""))
        assertEquals(TodoCallSummary(0, 0, null, 0), todoCallSummary("""{"todos":[]}"""))
    }

    @Test fun `malformed call degrades to generic row`() {
        assertNull(todoCallSummary("{"))
        assertNull(todoCallSummary("""{"todos":[null]}"""))
        assertNull(todoCallSummary("""{"todos":{}}"""))
        assertNull(todoDiff(call(8, "{"), emptyList()))
        assertNull(todoDiff(call(8, """{"todos":[{"content":"x","status":"unknown"}]}"""), emptyList()))
        assertNull(todoDiff(call(8, """{"todos":[{"content":" x ","status":"pending"},{"content":"x","status":"pending"}]}"""), emptyList()))
    }

    @Test fun `diff compares latest earlier durable write not later writes or success receipts`() {
        val nodes: List<ChatNode> = listOf(
            write(1, """[{"content":"old","status":"pending"}]"""),
            write(5, """[{"content":"A","status":"pending"},{"content":"B","status":"pending"},{"content":"gone","status":"completed"}]"""),
            write(20, """[{"content":"future","status":"pending"}]"""),
        )
        val diff = todoDiff(call(10, """{"todos":[{"content":"B","status":"pending"},{"content":"A","status":"completed"},{"content":"new","status":"pending"}]}"""), nodes)!!
        assertEquals(1, diff.added)
        assertEquals(2, diff.updated)
        assertEquals(1, diff.removed)
        assertEquals(listOf(TodoChange.Moved, TodoChange.Updated, TodoChange.Added, TodoChange.Removed), diff.items.map { it.change })
        assertEquals("pending", diff.items[1].previousStatus)
        assertFalse(diff.initial)
    }

    @Test fun `unknown predecessor does not claim initial list when history has more`() {
        val call = call(10, """{"todos":[{"content":"first","status":"pending"}]}""")
        assertTrue(todoDiff(call, emptyList(), hasMore = true)!!.unavailable)
        val initial = todoDiff(call, emptyList(), hasMore = false)!!
        assertTrue(initial.initial)
        assertEquals(1, initial.added)
        assertFalse(initial.unavailable)
        assertTrue(todoDiff(call, listOf(write(5, """{"bad":true}""")))!!.unavailable)
    }

    @Test fun `unchanged entries are collapsible separately from changes`() {
        val diff = todoDiff(call(4, """{"todos":[{"content":"stable","status":"completed"}]}"""),
            listOf(write(1, """[{"content":"stable","status":"completed"}]""")))!!
        assertEquals(listOf("stable"), diff.unchanged.map { it.content })
        assertTrue(diff.items.isEmpty())
    }
}
