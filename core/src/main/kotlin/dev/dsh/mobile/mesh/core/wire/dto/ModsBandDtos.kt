package dev.dsh.mobile.mesh.core.wire.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Optional Claude Code mods AbovePrompt surface; null tree means there is nothing to draw. */
@Serializable
data class ModsBandSnapshot(
    val generation: Long,
    val tree: List<JsonElement>? = null,
)
