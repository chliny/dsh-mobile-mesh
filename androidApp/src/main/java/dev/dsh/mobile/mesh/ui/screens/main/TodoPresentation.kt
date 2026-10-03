package dev.dsh.mobile.mesh.ui.screens.main

import dev.dsh.mobile.mesh.core.session.ChatNode
import dev.dsh.mobile.mesh.core.session.TodoNode
import dev.dsh.mobile.mesh.core.session.ToolCallNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A read-only rendering of a recorded todo_write invocation, never a mutation of the live dock. */
internal data class TodoCallSummary(val completed: Int, val total: Int, val activeContent: String?, val activeExtra: Int)

internal enum class TodoChange { Added, Updated, Moved, Removed }

internal data class TodoDetailItem(
    val content: String,
    val status: String,
    val change: TodoChange? = null,
    val previousStatus: String? = null,
)

internal data class TodoDiff(
    val items: List<TodoDetailItem>,
    val unchanged: List<TodoDetailItem>,
    val added: Int,
    val updated: Int,
    val removed: Int,
    val initial: Boolean = false,
    val unavailable: Boolean = false,
)

private val todoJson = Json { isLenient = true }

/** Accept only an object with an array of object items; a rejected/mid-stream call may be malformed. */
private fun todoItems(arguments: String): JsonArray? = runCatching {
    ((todoJson.parseToJsonElement(arguments) as? JsonObject)?.get("todos") as? JsonArray)
        ?.takeIf { array -> array.all { it is JsonObject } }
}.getOrNull()

internal fun todoCallSummary(arguments: String): TodoCallSummary? {
    val items = todoItems(arguments) ?: return null
    val active = items.filter { (it as JsonObject)["status"].todoString() == "in_progress" }
    val first = (active.firstOrNull() as? JsonObject)?.get("content").todoString()?.takeIf { it.isNotBlank() }
    return TodoCallSummary(
        completed = items.count { (it as JsonObject)["status"].todoString() == "completed" },
        total = items.size,
        activeContent = first,
        activeExtra = if (first != null) active.size - 1 else 0,
    )
}

private fun JsonElement?.todoString(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/** Details require valid, uniquely titled statuses, just as Web's todosDetail does. */
internal fun todoDetailItems(element: JsonElement?): List<TodoDetailItem>? {
    val array = element as? JsonArray ?: return null
    val seen = mutableSetOf<String>()
    return array.map { value ->
        val item = value as? JsonObject ?: return null
        val content = item["content"].todoString()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val status = item["status"].todoString()?.takeIf { it in setOf("pending", "in_progress", "completed") } ?: return null
        if (!seen.add(content)) return null
        TodoDetailItem(content, status)
    }
}

/** Do not confuse the absence of a loaded predecessor with a known-empty initial list. */
internal fun todoDiff(call: ToolCallNode, nodes: List<ChatNode>, hasMore: Boolean = false): TodoDiff? {
    val current = todoDetailItems(todoItems(call.arguments)) ?: return null
    val predecessor = nodes.filterIsInstance<TodoNode>().filter { it.seq < call.seq }.maxByOrNull { it.seq }
    if (predecessor == null && hasMore) return TodoDiff(current, emptyList(), 0, 0, 0, unavailable = true)
    val before = (if (predecessor == null) emptyList() else todoDetailItems(predecessor.todos))
        ?: return TodoDiff(current, emptyList(), 0, 0, 0, unavailable = true)
    val previousByTitle = before.associateByTo(linkedMapOf()) { it.content }
    val currentTitles = current.mapTo(mutableSetOf()) { it.content }
    val retainedPositions = before.filter { it.content in currentTitles }
        .mapIndexed { index, item -> item.content to index }.toMap()
    var retainedIndex = 0
    var added = 0
    var updated = 0
    val items = mutableListOf<TodoDetailItem>()
    val unchanged = mutableListOf<TodoDetailItem>()
    for (item in current) {
        val old = previousByTitle.remove(item.content)
        if (old == null) {
            added++
            items += item.copy(change = TodoChange.Added)
        } else {
            val moved = retainedPositions[item.content] != retainedIndex++
            val changed = old.status != item.status
            if (moved || changed) {
                updated++
                items += item.copy(
                    change = if (changed) TodoChange.Updated else TodoChange.Moved,
                    previousStatus = if (changed) old.status else null,
                )
            } else unchanged += item
        }
    }
    items += previousByTitle.values.map { it.copy(change = TodoChange.Removed) }
    return TodoDiff(items, unchanged, added, updated, previousByTitle.size, initial = predecessor == null)
}
