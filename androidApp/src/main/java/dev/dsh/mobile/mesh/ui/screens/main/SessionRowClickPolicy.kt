package dev.dsh.mobile.mesh.ui.screens.main

internal fun shouldExpandChildrenOnSessionClick(
    childCount: Int,
    childrenExpanded: Boolean,
): Boolean = childCount > 0 && !childrenExpanded
