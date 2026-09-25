package dev.dsh.mobile.mesh.ui.screens.main

import dev.dsh.mobile.mesh.data.QuestionOutcome

/** Do not erase a session draft until the server has accepted the pending-question dismissal. */
internal fun shouldClearComposerDraftAfterQuestionDismiss(outcome: QuestionOutcome): Boolean =
    outcome is QuestionOutcome.Accepted
