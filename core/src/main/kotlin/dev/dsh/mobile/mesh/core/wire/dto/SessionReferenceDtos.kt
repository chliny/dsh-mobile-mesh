package dev.dsh.mobile.mesh.core.wire.dto

import kotlinx.serialization.Serializable

/** Server-ranked session candidate with its canonical prompt mention. */
@Serializable
data class SessionReferenceCandidate(
    val sessionId: String,
    val label: String,
    val displayTitle: String? = null,
    val cwd: String? = null,
    val sameWorkspace: Boolean = false,
    val createdAt: Long = 0,
    val mention: String,
)
