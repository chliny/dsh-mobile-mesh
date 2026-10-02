package dev.dsh.mobile.mesh.ui.screens.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalAutoCreateTest {
    @Test fun createsImmediatelyOnFirstEmptyListWithoutWaitingForWebView() {
        val creation = InitialTerminalCreation()
        assertTrue(creation.pending)
        assertTrue(creation.listed(0))
        assertFalse(creation.pending)
        assertFalse(creation.listed(0)) // reconnect/list refresh cannot create again
    }

    @Test fun existingShellSuppressesAutomaticCreation() {
        val creation = InitialTerminalCreation()
        assertFalse(creation.listed(1))
        assertFalse(creation.listed(0)) // closing the last shell does not auto-create
    }

    @Test fun plusPressedBeforeFirstListPreventsSecondCreation() {
        val creation = InitialTerminalCreation()
        creation.manual()
        assertFalse(creation.pending)
        assertFalse(creation.listed(0))
    }
}
