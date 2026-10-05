package dev.dsh.mobile.mesh.ui.screens.main

import dev.dsh.mobile.mesh.core.wire.dto.SessionReferenceCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test

class SessionMentionInsertionTest {
    @Test fun `candidate inserts the server minted session URI verbatim`() {
        val candidate = SessionReferenceCandidate(
            sessionId = "source:opaque", label = "Research", mention = "@[Research](dsh-session:source%3Aopaque)",
        )
        assertEquals("Review @[Research](dsh-session:source%3Aopaque) ", insertSessionMention("Review @res", candidate))
        assertEquals("@[Research](dsh-session:source%3Aopaque) ", insertSessionMention("@", candidate))
    }

    @Test fun `switching sessions reissues the current mention query`() {
        val composer = File("src/main/java/dev/dsh/mobile/mesh/ui/screens/main/Composer.kt").readText()
        val chat = File("src/main/java/dev/dsh/mobile/mesh/ui/screens/main/ChatScreen.kt").readText()
        assertTrue(composer.contains("LaunchedEffect(mentionActive, mentionQuery, referenceSessionId)"))
        assertTrue(chat.contains("referenceSessionId = currentSessionId"))
    }
}
