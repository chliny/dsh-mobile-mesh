package dev.dsh.mobile.mesh.core.wire

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteStreamMuxLivenessTest {
    private class FakeChannel(private val sink: WsChannelSink) :
        WsChannel("http://stub/api/remote.mux", OkHttpClient(), sink) {
        val sent = CopyOnWriteArrayList<String>()
        var closedCount = 0
        override fun start() = sink.onOpen()
        override fun send(text: String): Boolean {
            sent += text
            return true
        }
        override fun close() { closedCount++ }
        fun emitClosed(cause: Throwable?) = sink.onClosed(cause)
        fun emitMessage(text: String) = sink.onMessage(text)
    }

    @Test
    fun `close marks mux closed and fails every pending stream`() = runBlocking {
        lateinit var channel: FakeChannel
        val mux = RemoteStreamMux { sink -> FakeChannel(sink).also { channel = it } }
        mux.start()
        mux.awaitOpen()
        val stream = mux.open("session/follow")
        mux.close()

        assertTrue(mux.isClosed)
        assertTrue(mux.failure is MuxClosedException)
        val failure = receiveFailure(stream)
        assertTrue(failure is RemoteStreamException)
        assertTrue((failure as RemoteStreamException).carrier)
        assertEquals(1, channel.closedCount)
        mux.close()
        assertEquals(1, channel.closedCount)
    }

    @Test
    fun `job roster opens the generation mux with named request payload`() = runBlocking {
        lateinit var channel: FakeChannel
        val mux = RemoteStreamMux(channelFactory = { sink -> FakeChannel(sink).also { channel = it } })
        mux.start()
        mux.awaitOpen()

        val rosterJob = async {
            mux.openStream(
                "job/list",
                kotlinx.serialization.json.buildJsonObject {
                    put("request", kotlinx.serialization.json.buildJsonObject { put("sessionId", "session-7") })
                },
            ).collect { }
        }
        while (channel.sent.size < 1) delay(1)
        val opening = WireJson.decodeFromString(
            dev.dsh.mobile.mesh.core.wire.dto.RemoteStreamClientMessageSerializer,
            channel.sent.last(),
        ) as dev.dsh.mobile.mesh.core.wire.dto.RemoteStreamClientMessage.Open
        assertEquals("job/list", opening.endpoint)
        val args = opening.payload.jsonObject["args"]!!.jsonObject["request"]!!.jsonObject
        assertEquals("session-7", args["sessionId"]!!.jsonPrimitive.content)
        mux.close()
        rosterJob.cancel()
    }

    @Test
    fun `send emits uplink items including explicit json null`() = runBlocking {
        lateinit var channel: FakeChannel
        val mux = RemoteStreamMux(channelFactory = { sink -> FakeChannel(sink).also { channel = it } })
        mux.start()
        mux.awaitOpen()
        val stream = mux.open("sample/upload")

        stream.send(JsonPrimitive("payload"))
        stream.send(JsonNull)
        stream.send()

        val messages = channel.sent.drop(1).map { WireJson.parseToJsonElement(it).jsonObject }
        assertEquals(JsonPrimitive("payload"), messages[0]["value"])
        assertEquals(JsonNull, messages[1]["value"])
        assertTrue(messages[2].containsKey("value").not())
        mux.close()
    }

    @Test
    fun `end uplink is idempotent and rejects later sends`() = runBlocking {
        lateinit var channel: FakeChannel
        val mux = RemoteStreamMux(channelFactory = { sink -> FakeChannel(sink).also { channel = it } })
        mux.start()
        mux.awaitOpen()
        val stream = mux.open("sample/upload")

        stream.endUplink()
        stream.endUplink()
        assertEquals(2, channel.sent.size)
        val end = WireJson.decodeFromString(
            dev.dsh.mobile.mesh.core.wire.dto.RemoteStreamClientMessageSerializer,
            channel.sent.last(),
        )
        assertTrue(end is dev.dsh.mobile.mesh.core.wire.dto.RemoteStreamClientMessage.End)
        assertTrue(runCatching { stream.send(JsonPrimitive("late")) }.isFailure)
        mux.close()
    }

    @Test
    fun `carrier close fences uplink sends and receive observes terminal`() = runBlocking {
        lateinit var channel: FakeChannel
        val mux = RemoteStreamMux(channelFactory = { sink -> FakeChannel(sink).also { channel = it } })
        mux.start()
        mux.awaitOpen()
        val stream = mux.open("sample/upload")
        channel.emitClosed(IllegalStateException("socket lost"))

        assertTrue(runCatching { stream.send(JsonPrimitive("late")) }.isFailure)
        assertTrue(receiveFailure(stream) is RemoteStreamException)
    }

    @Test
    fun `host end terminates uplink and makes endUplink a no-op`() = runBlocking {
        lateinit var channel: FakeChannel
        val mux = RemoteStreamMux(channelFactory = { sink -> FakeChannel(sink).also { channel = it } })
        mux.start()
        mux.awaitOpen()
        val stream = mux.open("sample/upload")
        channel.emitMessage("""{"type":"end","streamId":"1"}""")

        assertEquals(null, stream.receive())
        stream.endUplink()
        assertEquals(1, channel.sent.size)
    }

    @Test
    fun `host error terminates uplink and rejects sends`() = runBlocking {
        lateinit var channel: FakeChannel
        val mux = RemoteStreamMux(channelFactory = { sink -> FakeChannel(sink).also { channel = it } })
        mux.start()
        mux.awaitOpen()
        val stream = mux.open("sample/upload")
        channel.emitMessage("""{"type":"error","streamId":"1","error":{"code":"failed","message":"no"}}""")

        assertTrue(receiveFailure(stream) is RemoteStreamException)
        assertTrue(runCatching { stream.send(JsonPrimitive("late")) }.isFailure)
        stream.endUplink()
        assertEquals(1, channel.sent.size)
    }

    @Test
    fun `carrier failure marks liveness and unblocks stream`() = runBlocking {
        lateinit var channel: FakeChannel
        val mux = RemoteStreamMux { sink -> FakeChannel(sink).also { channel = it } }
        mux.start()
        mux.awaitOpen()
        val stream = mux.open("session/control")
        val cause = IllegalStateException("socket lost")
        channel.emitClosed(cause)

        assertTrue(mux.isClosed)
        assertEquals(cause, mux.failure)
        assertTrue(receiveFailure(stream) is RemoteStreamException)
    }

    private suspend fun receiveFailure(stream: RemoteStream): Throwable? =
        runCatching { stream.receive() }.exceptionOrNull()

    @Test
    fun `start after close never creates a WebSocket`() {
        val created = AtomicInteger()
        val mux = RemoteStreamMux { sink ->
            created.incrementAndGet()
            FakeChannel(sink)
        }
        mux.close()
        mux.start()
        assertEquals(0, created.get())
    }

    @Test
    fun `cancelling stream wakes a concurrent receiver`() = runBlocking {
        val mux = RemoteStreamMux { sink -> FakeChannel(sink) }
        mux.start()
        mux.awaitOpen()
        val stream = mux.open("session/follow")
        val receiver = async { withTimeout(1_000) { stream.receive() } }
        delay(1)
        stream.cancel()
        assertEquals(null, receiver.await())
    }

    @Test
    fun `burst within configured buffer is ordered and does not close carrier`() = runBlocking {
        lateinit var channel: FakeChannel
        val mux = RemoteStreamMux(
            streamSignalBufferCapacity = 8,
            channelFactory = { sink -> FakeChannel(sink).also { channel = it } },
        )
        mux.start()
        mux.awaitOpen()
        val stream = mux.open("session/follow")
        repeat(8) { channel.emitMessage("{\"streamId\":\"1\",\"type\":\"item\",\"value\":$it}") }
        assertEquals((0..7).map(Int::toString), (0..7).map { stream.receive().toString() })
        assertFalse(mux.isClosed)
        mux.close()
    }

    @Test
    fun `one slow logical consumer is failed without closing shared carrier`() = runBlocking {
        lateinit var channel: FakeChannel
        val overflowedEndpoints = CopyOnWriteArrayList<String>()
        val mux = RemoteStreamMux(
            streamSignalBufferCapacity = 2,
            channelFactory = { sink -> FakeChannel(sink).also { channel = it } },
            onConsumerFellBehind = { endpoint, _ -> overflowedEndpoints += endpoint },
        )
        mux.start()
        mux.awaitOpen()
        val slow = mux.open("session/follow")
        val healthy = mux.open("session/control")
        repeat(3) { channel.emitMessage("{\"streamId\":\"1\",\"type\":\"item\",\"value\":$it}") }
        channel.emitMessage("{\"streamId\":\"2\",\"type\":\"item\",\"value\":42}")

        assertFalse("a logical stream overflow must not tear down other streams", mux.isClosed)
        assertEquals("42", healthy.receive().toString())
        assertEquals("0", slow.receive().toString())
        assertEquals("1", slow.receive().toString())
        val failure = receiveFailure(slow) as RemoteStreamException
        assertFalse(failure.carrier)
        assertEquals("consumer_fell_behind", failure.error.code)
        assertEquals(listOf("session/follow"), overflowedEndpoints.toList())
        mux.close()
    }

    @Test
    fun `capacity plus one terminates only the lagging stream after delivering queued items`() = runBlocking {
        lateinit var channel: FakeChannel
        val mux = RemoteStreamMux(
            streamSignalBufferCapacity = 4,
            channelFactory = { sink -> FakeChannel(sink).also { channel = it } },
        )
        mux.start()
        mux.awaitOpen()
        val stream = mux.open("workspace/follow")
        repeat(5) { channel.emitMessage("{\"streamId\":\"1\",\"type\":\"item\",\"value\":$it}") }

        repeat(4) { assertEquals(it.toString(), stream.receive().toString()) }
        val overflow = receiveFailure(stream) as RemoteStreamException
        assertFalse(overflow.carrier)
        assertFalse("the shared carrier stays usable", mux.isClosed)
        mux.close()
    }

    @Test
    fun `default stream buffer tolerates more than a short burst of 64 frames`() = runBlocking {
        lateinit var channel: FakeChannel
        val mux = RemoteStreamMux { sink -> FakeChannel(sink).also { channel = it } }
        mux.start()
        mux.awaitOpen()
        val stream = mux.open("session/follow")
        repeat(128) { channel.emitMessage("{\"streamId\":\"1\",\"type\":\"item\",\"value\":$it}") }
        assertFalse(mux.isClosed)
        repeat(128) { assertEquals(it.toString(), stream.receive().toString()) }
        mux.close()
    }

    @Test
    fun `open after close fails immediately without creating a stranded stream`() = runBlocking {
        val mux = RemoteStreamMux { sink -> FakeChannel(sink) }
        mux.close()
        assertTrue(mux.isClosed)
        val error = runCatching { mux.open("$events") }.exceptionOrNull()
        assertTrue(error is RemoteStreamException)
    }

    @Test
    fun `concurrent open and close leaves no pending receive`() = runBlocking {
        lateinit var channel: FakeChannel
        val mux = RemoteStreamMux { sink -> FakeChannel(sink).also { channel = it } }
        mux.start()
        mux.awaitOpen()
        val opened = AtomicInteger()
        val jobs = (1..100).map {
            async {
                runCatching {
                    val stream = mux.open("session/follow")
                    opened.incrementAndGet()
                    withTimeout(1_000) { runCatching { stream.receive() } }
                }
            }
        }
        delay(1)
        mux.close()
        jobs.forEach { it.await() }
        assertTrue(mux.isClosed)
        assertTrue(opened.get() > 0)
    }

    @Test
    fun `close is idempotent at mux level`() = runBlocking {
        lateinit var channel: FakeChannel
        val mux = RemoteStreamMux { sink -> FakeChannel(sink).also { channel = it } }
        mux.start()
        mux.close()
        mux.close()
        assertTrue(mux.isClosed)
        assertEquals(1, channel.closedCount)
    }

    private companion object {
        const val events = "events"
    }
}
