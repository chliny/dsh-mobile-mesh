package dev.dsh.mobile.mesh.ui.screens.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.mesh.core.wire.dto.ModsBandSnapshot
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** Read a serialized button's host-minted action id; text-only buttons cannot be pressed. */
internal fun modsBandAction(node: JsonElement): String? {
    val element = node as? JsonObject ?: return null
    if ((element["type"] as? JsonPrimitive)?.contentOrNull != "Button") return null
    if ((element["props"] as? JsonObject)?.get("disabled")?.let { (it as? JsonPrimitive)?.booleanOrNull } == true) return null
    return (element["actionId"] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content
}

/** Server-supplied trees are presentation only; unrecognized elements never become actions. */
@Composable
private fun BandNode(node: JsonElement, onPress: (String) -> Unit, busy: Boolean, depth: Int = 0) {
    if (depth > 12) return
    if (node is JsonPrimitive && node.isString) {
        Text(node.content)
        return
    }
    val element = node as? JsonObject ?: return
    val props = element["props"] as? JsonObject ?: JsonObject(emptyMap())
    val children = (element["children"] as? JsonArray).orEmpty().take(100)
    when ((element["type"] as? JsonPrimitive)?.contentOrNull) {
        "Text" -> Row { children.forEach { BandNode(it, onPress, busy, depth + 1) } }
        "Box" -> {
            if ((props["flexDirection"] as? JsonPrimitive)?.contentOrNull == "row") {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    children.forEach { BandNode(it, onPress, busy, depth + 1) }
                }
            } else Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                children.forEach { BandNode(it, onPress, busy, depth + 1) }
            }
        }
        "Button" -> {
            val label = (props["label"] as? JsonPrimitive)?.contentOrNull ?: return
            val action = modsBandAction(element)
            OutlinedButton(onClick = { action?.let(onPress) }, enabled = action != null && !busy) { Text(label) }
        }
    }
}

/** Optional AbovePrompt band; an absent route or a null drawing occupies no composer space. */
@Composable
internal fun ModsBand(snapshot: ModsBandSnapshot?, busy: Boolean, onPress: (Long, String) -> Unit) {
    val tree = snapshot?.tree ?: return
    if (tree.isEmpty()) return
    Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), tonalElevation = 2.dp) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            tree.take(100).forEach { node -> BandNode(node, { onPress(snapshot.generation, it) }, busy) }
        }
    }
}
