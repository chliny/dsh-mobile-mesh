package dev.dsh.mobile.mesh.core.wire.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Shell profile verified in the session execution environment. */
@Serializable
data class TerminalShell(
    @SerialName("path") val path: String,
    @SerialName("args") val args: List<String>,
    @SerialName("name") val name: String,
)

/** Working directory and limits returned by `terminal/environment`. */
@Serializable
data class TerminalEnvironment(
    @SerialName("cwd") val cwd: String,
    @SerialName("maxInputBytes") val maxInputBytes: Int,
    @SerialName("maxCols") val maxCols: Int,
    @SerialName("maxRows") val maxRows: Int,
    @SerialName("scrollback") val scrollback: Int,
)

/** Host-owned terminal metadata returned by `terminal/list` and `terminal/create`. */
@Serializable
data class WebTerminalInfo(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String,
    @SerialName("shell") val shell: TerminalShell,
    @SerialName("cwd") val cwd: String,
    @SerialName("cols") val cols: Int,
    @SerialName("rows") val rows: Int,
    @SerialName("state") val state: String,
    @SerialName("exitCode") val exitCode: Int?,
    @SerialName("error") val error: String? = null,
    @SerialName("controllerId") val controllerId: String? = null,
)

/** Caller-minted identity and initial PTY dimensions for `terminal/create`. */
@Serializable
data class TerminalCreateRequest(
    @SerialName("id") val id: String,
    @SerialName("cols") val cols: Int,
    @SerialName("rows") val rows: Int,
    @SerialName("shellPath") val shellPath: String? = null,
)
