package dev.dsh.mobile.mesh.ui.screens.main

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceFilesScreenPathTest {
    @Test
    fun `root child path never creates a leading empty segment`() {
        assertEquals("src", childWorkspacePath(".", "src"))
    }

    @Test
    fun `nested child path joins normalized server paths`() {
        assertEquals("src/main", childWorkspacePath("src/", "/main"))
    }

    @Test
    fun `absolute root and its children look up the same keys published by list store`() {
        assertEquals(".", workspaceFilesLevelPath("/"))
        assertEquals("src", workspaceFilesLevelPath("/src"))
        assertEquals(" dir ", workspaceFilesLevelPath("/ dir "))
    }

    @Test
    fun `back from direct child of absolute root returns slash rather than blank path`() {
        assertEquals("/", parentWorkspaceFilesPath("/src", "/"))
        assertEquals("/", parentWorkspaceFilesPath("/", "/"))
        assertEquals("/home/user", parentWorkspaceFilesPath("/home/user/src", "/home/user"))
        assertEquals(" ", parentWorkspaceFilesPath(" /child", "."))
    }

    @Test
    fun `empty child preserves the normalized parent`() {
        assertEquals(".", childWorkspacePath(".", ""))
        assertEquals("src", childWorkspacePath("src", ""))
        assertEquals("/", childWorkspacePath("/", ""))
    }

    @Test
    fun `server file names and directory segments retain whitespace`() {
        assertEquals(" leading ", childWorkspacePath(".", " leading "))
        assertEquals("dir /file ", childWorkspacePath("dir ", "file "))
        assertEquals(" /child", childWorkspacePath(" ", "child"))
        assertEquals(" ", childWorkspacePath(".", " "))
        assertEquals(" /child", childWorkspacePath(normalizeWorkspaceFilesPath(" "), "child"))
        assertEquals(" /child", childWorkspacePath(normalizeWorkspaceFilesRoot(" "), "child"))
        assertEquals("/ name ", childWorkspacePath("/", " name "))
    }
}
