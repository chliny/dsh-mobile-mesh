package dev.dsh.mobile.mesh.core.wire

import dev.dsh.mobile.mesh.core.wire.dto.AskUserQuestionAnswer
import dev.dsh.mobile.mesh.core.wire.dto.AskUserQuestionAnswerItem
import dev.dsh.mobile.mesh.core.wire.dto.AskUserQuestionRequestEvent
import dev.dsh.mobile.mesh.core.wire.dto.AskUserQuestionWait
import dev.dsh.mobile.mesh.core.wire.dto.RemoteEventFrame
import dev.dsh.mobile.mesh.core.wire.dto.RemoteEventFrameSerializer
import dev.dsh.mobile.mesh.core.wire.dto.SessionProjectionsBlock
import dev.dsh.mobile.mesh.core.wire.dto.SessionProjectionsValue
import dev.dsh.mobile.mesh.core.wire.dto.userQuestionsView
import java.io.InputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserQuestionsWireTest {
    @Test
    fun `legacy request without wait still decodes and timed request retains call identity`() {
        val legacy = decodeFromString<AskUserQuestionRequestEvent>(
            """{"questions":[{"id":"q","question":"Choose"}]}""",
        )
        assertNull(legacy.wait)
        val timed = decodeFromString<AskUserQuestionRequestEvent>(
            """{"questions":[{"id":"q","question":"Choose"}],"wait":{"callId":"call-1","timed":true}}""",
        )
        assertEquals(AskUserQuestionWait("call-1", true), timed.wait)
        val indefinite = decodeFromString<AskUserQuestionRequestEvent>(
            """{"questions":[],"wait":{"callId":"call-2"}}""",
        )
        assertNull(indefinite.wait?.timed)
        val frame = WireJson.decodeFromString(RemoteEventFrameSerializer,
            """{"type":"waterfall","event":"user-questions/request","eventId":"e","agentId":"agent", "request":{"questions":[],"wait":{"callId":"call-1","timed":true}}}""") as RemoteEventFrame.Waterfall
        assertEquals("call-1", decodeFromJsonElement(AskUserQuestionRequestEvent.serializer(), frame.request).wait?.callId)
    }

    @Test
    fun `projection reads active and settled host values without deriving client state`() {
        val json = Json.parseToJsonElement("""{"active":[{"callId":"a","questions":[{"id":"q","question":"Approve?","detail":"plan markdown","intent":{"kind":"plan-review","approve":"Yes","callId":"tool-1"},"options":[{"label":"Yes"}]}],"state":"continued"}],"settled":[{"callId":"b","answers":[{"id":"q2","selected":[],"custom":"later"}]}]}""")
        val baseline = SessionProjectionsBlock(asOfSeq = 4, values = mapOf("userQuestions" to json))
        val view = baseline.userQuestionsView()!!
        assertEquals("continued", view.active.single().state)
        assertEquals("tool-1", (view.active.single().questions.single().intent as dev.dsh.mobile.mesh.core.wire.dto.AskUserQuestionIntent.PlanReview).callId)
        assertEquals("later", view.settled.single().answers.single().custom)
        assertEquals(view, SessionProjectionsValue(4, mapOf("userQuestions" to json)).userQuestionsView())
        assertNull(SessionProjectionsValue(4).userQuestionsView())
        assertTrue(decodeFromString<dev.dsh.mobile.mesh.core.wire.dto.UserQuestionsProjectionView>("""{"active":[],"settled":[]}""").active.isEmpty())
    }

    private class RecordingTransport : RpcTransport {
        var path = ""
        var body = ""
        override suspend fun post(path: String, body: String): RpcHttpResponse {
            this.path = path
            this.body = body
            val rpcId = Json.parseToJsonElement(body).jsonObject["rpcId"]!!.jsonPrimitive.content
            return RpcHttpResponse(200, """{"type":"server-response","rpcId":"$rpcId","result":{"ok":true,"value":true}}""")
        }
        override suspend fun <T> download(path: String, consume: (String?, String?, InputStream) -> T): T = error("unused")
        override suspend fun upload(path: String, contentType: String, contentLength: Long, body: InputStream, onProgress: ((Long) -> Unit)?): RpcHttpResponse = error("unused")
    }

    @Test
    fun `continued answer sends exact named remote arguments and bare boolean result`() = runTest {
        val transport = RecordingTransport()
        val result = DshApiClient(transport).userQuestionsAnswer(
            "agent-1", "call-1", AskUserQuestionAnswer(listOf(AskUserQuestionAnswerItem("q", emptyList(), "other"))),
        )
        assertEquals(RpcResult.Ok(true), result)
        assertEquals("/api/userQuestions/answer", transport.path)
        val request = Json.parseToJsonElement(transport.body).jsonObject
        assertEquals("userQuestions/answer", request["method"]!!.jsonPrimitive.content)
        val args = request["payload"]!!.jsonObject["args"]!!.jsonObject
        assertEquals(setOf("agentId", "callId", "answer"), args.keys)
        assertEquals("agent-1", args["agentId"]!!.jsonPrimitive.content)
        assertEquals("call-1", args["callId"]!!.jsonPrimitive.content)
        assertEquals("other", args["answer"]!!.jsonObject["answers"]!!.let {
            (it as kotlinx.serialization.json.JsonArray)[0].jsonObject["custom"]!!.jsonPrimitive.content
        })
    }

    private class FakeChannel(private val receiver: WsChannelSink) : WsChannel("ws://localhost/unused", OkHttpClient(), receiver) {
        val sent = mutableListOf<String>()
        override fun start() { receiver.onOpen() }
        override fun send(text: String): Boolean { sent += text; return true }
        override fun close() { receiver.onClosed(null) }
        fun receive(text: String) = receiver.onMessage(text)
    }

    @Test
    fun `attach wait opens mux with agent and call and decodes remaining milliseconds`() = runTest {
        lateinit var channel: FakeChannel
        val mux = RemoteStreamMux(channelFactory = { sink -> FakeChannel(sink).also { channel = it } })
        mux.start()
        val collector = async {
            mux.userQuestionsAttachWait("agent-1", "call-1").first()
        }
        testScheduler.runCurrent()
        val opened = Json.parseToJsonElement(channel.sent.first()).jsonObject
        assertEquals("userQuestions/attachWait", opened["endpoint"]!!.jsonPrimitive.content)
        val args = opened["payload"]!!.jsonObject["args"]!!.jsonObject
        assertEquals(setOf("agentId", "callId"), args.keys)
        assertEquals("agent-1", args["agentId"]!!.jsonPrimitive.content)
        assertEquals("call-1", args["callId"]!!.jsonPrimitive.content)
        val streamId = opened["streamId"]!!.jsonPrimitive.content
        channel.receive("""{"type":"item","streamId":"$streamId","value":{"remainingMs":3210}}""")
        assertEquals(3210L, collector.await().remainingMs)
        assertTrue(channel.sent.any { Json.parseToJsonElement(it).jsonObject["type"]?.jsonPrimitive?.content == "cancel" })
        mux.close()
    }
}
