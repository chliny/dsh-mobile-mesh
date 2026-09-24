package dev.dsh.mobile.mesh.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSelectionProjectionPolicyTest {
    @Test
    fun `current session model projection is applied`() {
        assertTrue(shouldApplyModelSelectionProjection("session-a", "session-a", currentSeq = 4, incomingSeq = 5))
    }

    @Test
    fun `model projection from another session is ignored`() {
        assertFalse(shouldApplyModelSelectionProjection("session-a", "session-b", currentSeq = null, incomingSeq = 5))
    }

    @Test
    fun `older model projection cannot overwrite a newer selection`() {
        assertFalse(shouldApplyModelSelectionProjection("session-a", "session-a", currentSeq = 8, incomingSeq = 7))
    }

    @Test
    fun `same sequence can replace selection projection`() {
        assertTrue(shouldApplyModelSelectionProjection("session-a", "session-a", currentSeq = 8, incomingSeq = 8))
    }
}
