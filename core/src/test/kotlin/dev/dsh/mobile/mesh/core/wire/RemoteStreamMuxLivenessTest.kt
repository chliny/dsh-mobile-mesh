package dev.dsh.mobile.mesh.core.wire

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
