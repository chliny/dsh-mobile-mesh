package dev.dsh.mobile.mesh.ui.screens.main

import dev.dsh.mobile.mesh.data.QuestionOutcome
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionDraftPolicyTest {
    @Test
    fun `failed dismissal retains the composer draft`() {
        assertFalse(shouldClearComposerDraftAfterQuestionDismiss(QuestionOutcome.Unsent("offline")))
        assertFalse(shouldClearComposerDraftAfterQuestionDismiss(QuestionOutcome.Refused("not-pending")))
    }

    @Test
    fun `accepted dismissal may clear the draft before discussing`() {
        assertTrue(shouldClearComposerDraftAfterQuestionDismiss(QuestionOutcome.Accepted))
    }
}
