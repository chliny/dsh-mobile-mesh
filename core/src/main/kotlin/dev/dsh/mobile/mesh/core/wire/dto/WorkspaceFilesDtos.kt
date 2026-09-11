package dev.dsh.mobile.mesh.core.wire.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Bounded workspace file metadata from the harness workspaceFiles Remote. */
@Serializable
data class WorkspaceFileStat(
    @SerialName("absolutePath") val absolutePath: String,
    @SerialName("version") val version: String,
    @SerialName("bytes") val bytes: Long? = null,
)

@Serializable
data class WorkspaceFileRange(
    @SerialName("offset") val offset: Int? = null,
    @SerialName("limit") val limit: Int? = null,
)

@Serializable
data class WorkspaceFileText(
    @SerialName("absolutePath") val absolutePath: String,
    @SerialName("version") val version: String,
    @SerialName("bytes") val bytes: Long? = null,
    @SerialName("offset") val offset: Int,
    @SerialName("text") val text: String,
    @SerialName("lines") val lines: Int,
    @SerialName("eof") val eof: Boolean,
)

@Serializable
data class WorkspaceFileBytes(
    @SerialName("absolutePath") val absolutePath: String,
    @SerialName("version") val version: String,
    @SerialName("bytes") val bytes: Long? = null,
    @SerialName("offset") val offset: Int,
    @SerialName("data") val data: String,
    @SerialName("eof") val eof: Boolean,
)

@Serializable
data class WorkspaceDirectoryEntry(
    @SerialName("name") val name: String,
    @SerialName("type") val type: String,
    @SerialName("size") val size: Long? = null,
)

@Serializable
data class WorkspaceDirectoryListing(
    @SerialName("path") val path: String,
    @SerialName("entries") val entries: List<WorkspaceDirectoryEntry> = emptyList(),
    @SerialName("truncated") val truncated: Boolean = false,
)
