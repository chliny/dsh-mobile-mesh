package dev.dsh.mobile.mesh.core.session

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class QuestionReplyConversationTest {
    private fun event(seq: Long, type: String, data: String) =
        SessionEventEnvelope(type, seq, seq, Json.parseToJsonElement(data))

    @Test
    fun `pending question result keeps the submitted answer visible when the final result arrives`() {
        val call = event(1, "tool/call", """{"callId":"call-1","name":"ask_user_question","arguments":"{\"questions\":[{\"id\":\"mode\",\"question\":\"Which mode?\",\"header\":\"Mode\"}]}"}""")
        val pending = event(2, "tool/result", """{"message":{"source":{"kind":"tool","callId":"call-1"},"content":[{"type":"text","text":"{\"pending\":true}"}]}}""")
        val answered = event(3, "tool/result", """{"message":{"source":{"kind":"tool","callId":"call-1"},"content":[{"type":"text","text":"{\"answers\":[{\"id\":\"mode\",\"selected\":[\"Careful\"],\"custom\":\"notes\"}]}"}]}}""")

        val snapshot = EventFold("s1").fold(listOf(call, pending, answered))
        val reply = snapshot.nodes.filterIsInstance<QuestionReplyNode>().single()

        assertEquals("Which mode?", reply.questions.single().question)
        assertEquals("Mode", reply.questions.single().header)
        assertEquals(listOf("Careful"), reply.answers.single().selected)
        assertEquals("notes", reply.answers.single().custom)
        assertFalse(EventFold("s1").fold(listOf(call, pending)).nodes.any { it is QuestionReplyNode })

        val incremental = EventFold.Incremental(EventFold("s1").fold(listOf(call, pending)), "s1")
        val afterFinalResult = incremental.apply(answered)!!.nodes.filterIsInstance<QuestionReplyNode>().single()
        assertEquals("Which mode?", afterFinalResult.questions.single().question)
        assertEquals(listOf("Careful"), afterFinalResult.answers.single().selected)
    }

    @Test
    fun `immediate and late representations of one answer produce one visible reply`() {
        val call = event(1, "tool/call", """{"callId":"call-1","name":"ask_user_question","arguments":"{\"questions\":[{\"id\":\"mode\",\"question\":\"Which mode?\"}]}"}""")
        val immediate = event(2, "tool/result", """{"message":{"source":{"kind":"tool","callId":"call-1"},"content":[{"type":"text","text":"{\"answers\":[{\"id\":\"mode\",\"selected\":[\"Careful\"]}]}"}]}}""")
        val late = event(3, "user/message", """{"id":"late-1","source":{"kind":"user-question-reply","callId":"call-1","outcome":"answered"},"content":[{"type":"text","text":"{\"questions\":[{\"id\":\"mode\",\"question\":\"Which mode?\"}],\"answers\":[{\"id\":\"mode\",\"selected\":[\"Fast\"]}]}"}]}""")

        val snapshot = EventFold("s1").fold(listOf(call, immediate, late))
        val replies = snapshot.nodes.filterIsInstance<QuestionReplyNode>()

        assertEquals(1, replies.size)
        assertEquals("Which mode?", replies.single().questions.single().question)
        assertEquals(listOf("Careful"), replies.single().answers.single().selected)
    }

    @Test
    fun `a late plan review or malformed answered payload is not shown as a question bubble or raw JSON`() {
        val planReview = event(1, "user/message", """{"id":"review-1","source":{"kind":"user-question-reply","callId":"review-call","outcome":"answered"},"content":[{"type":"text","text":"{\"questions\":[{\"id\":\"review\",\"question\":\"Approve?\",\"intent\":{\"kind\":\"plan-review\"}}],\"answers\":[{\"id\":\"review\",\"selected\":[\"Approve\"]}]}"}]}""")
        val malformed = event(2, "user/message", """{"id":"malformed-1","source":{"kind":"user-question-reply","callId":"malformed-call","outcome":"answered"},"content":[{"type":"text","text":"{\"answers\":[{\"id\":\"q\",\"selected\":[\"Yes\"]}]}"}]}""")

        val nodes = EventFold("s1").fold(listOf(planReview, malformed)).nodes

        assertFalse(nodes.any { it is QuestionReplyNode })
        assertFalse(nodes.any { it is UserMessageNode || it is ContextMessageNode })
    }

    @Test
    fun `late answer is visible as reply rather than injected context`() {
        val reply = event(9, "user/message", """{"id":"late-1","source":{"kind":"user-question-reply","callId":"call-2","outcome":"answered"},"content":[{"type":"text","text":"{\"questions\":[{\"id\":\"go\",\"question\":\"Proceed?\"}],\"answers\":[{\"id\":\"go\",\"selected\":[\"Yes\"]}]}"}]}""")
        val nodes = EventFold("s1").fold(listOf(reply)).nodes
        val questionReply = nodes.filterIsInstance<QuestionReplyNode>().single()

        assertEquals(listOf(QuestionReplyQuestion("go", "Proceed?")), questionReply.questions)
        assertEquals(listOf("Yes"), questionReply.answers.single().selected)
        assertFalse(nodes.any { it is ContextMessageNode || it is UserMessageNode })
    }

    @Test
    fun `answered PTC question is visible in the transcript`() {
        val start = event(4, "tool/ptc-dispatch-start", """{"rootCallId":"run","parentCallId":"run","subCallId":"run:ptc:1","name":"ask_user_question","arguments":{"questions":[{"id":"q","question":"Continue?"}]}}""")
        val end = event(5, "tool/ptc-dispatch", """{"rootCallId":"run","parentCallId":"run","subCallId":"run:ptc:1","name":"ask_user_question","arguments":{"questions":[{"id":"q","question":"Continue?"}]},"isError":false,"content":[{"type":"text","text":"{\"answers\":[{\"id\":\"q\",\"selected\":[\"Yes\"]}]}"}]}""")
        val incremental = EventFold.Incremental(EventFold("s1").fold(listOf(start)), "s1")
        val reply = incremental.apply(end)!!.nodes.filterIsInstance<QuestionReplyNode>().single()

        assertEquals("Continue?", reply.questions.single().question)
        assertEquals(listOf("Yes"), reply.answers.single().selected)
    }

    @Test
    fun `answered PTC frame restores its question when history omits the start event`() {
        val completed = event(12, "tool/ptc-dispatch", """{"subCallId":"run:ptc:2","name":"ask_user_question","arguments":{"questions":[{"id":"scope","question":"Which scope?","header":"Scope"}]},"isError":false,"content":[{"type":"text","text":"{\"answers\":[{\"id\":\"scope\",\"selected\":[\"Current session\"]}]}"}]}""")

        val snapshot = EventFold("s1").fold(listOf(completed))
        val reply = snapshot.nodes.filterIsInstance<QuestionReplyNode>().single()

        assertEquals("Which scope?", reply.questions.single().question)
        assertEquals("Scope", reply.questions.single().header)
        assertEquals(listOf("Current session"), reply.answers.single().selected)
    }
}
