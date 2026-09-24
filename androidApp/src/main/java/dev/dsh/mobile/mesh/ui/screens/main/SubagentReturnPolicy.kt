package dev.dsh.mobile.mesh.ui.screens.main

/** Only navigation initiated from a parent chat should return to that parent; list entry returns to list. */
internal fun subagentReturnSessionId(enteredFromParentSessionId: String?): String? =
    enteredFromParentSessionId?.takeIf { it.isNotBlank() }
