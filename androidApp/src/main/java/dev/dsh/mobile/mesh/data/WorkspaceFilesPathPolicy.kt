package dev.dsh.mobile.mesh.data

internal fun workspaceFilesPathOrRoot(path: String?): String =
    path?.trim()?.replace('\\', '/')?.takeIf { it.isNotBlank() } ?: "."
