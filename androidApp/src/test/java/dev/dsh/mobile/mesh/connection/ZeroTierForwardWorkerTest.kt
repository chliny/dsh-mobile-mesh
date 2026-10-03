package dev.dsh.mobile.mesh.connection

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZeroTierForwardWorkerTest {
    @Test
    fun `request preface classifier handles split lines and only whitelisted routes`() {
        assertTrue(ZeroTierForwardWorker.classifyHttpPreface("GET /api/remote".toByteArray(), complete = false) == null)
        val partialLine = "GET /api/remote.mux HTTP/1.1\r".toByteArray()
        assertTrue(ZeroTierForwardWorker.classifyHttpPreface(partialLine, complete = false) == null)
        val muxLine = "GET /api/remote.mux HTTP/1.1\r\nHost: secret.example\r\nCookie: secret\r\n".toByteArray()
        assertTrue(ZeroTierForwardWorker.classifyHttpPreface(muxLine, complete = true) == "GET /api/remote.mux")
        val listRoute = "POST /api/session/list?token=secret HTTP/1.1\r\n".toByteArray()
        assertTrue(ZeroTierForwardWorker.classifyHttpPreface(listRoute, complete = true) == "POST /api/session/list")
        val privatePath = "POST /api/session/private-method?token=secret HTTP/1.1\r\n".toByteArray()
        assertTrue(ZeroTierForwardWorker.classifyHttpPreface(privatePath, complete = true) == "POST other-path")
    }

    @Test
    fun `socket relay errors use bounded errno categories without logging native messages`() {
        assertTrue(ZeroTierForwardWorker.safeSocketErrorCategory(java.io.IOException("recv failed errno=11 (EAGAIN)")) == "EAGAIN")
        assertTrue(ZeroTierForwardWorker.safeSocketErrorCategory(java.io.IOException("native read errno=-4")) == "EINTR")
        assertTrue(ZeroTierForwardWorker.safeSocketErrorCategory(java.io.IOException("native read errno=104")) == "RESET")
        assertTrue(ZeroTierForwardWorker.safeSocketErrorCategory(java.io.IOException("recv failed errno=200 (service unavailable)")) == "SERVICE")
        assertTrue(ZeroTierForwardWorker.safeSocketErrorCategory(java.io.IOException("private endpoint /path?secret=x")) == "OTHER")
        assertTrue(ZeroTierForwardWorker.safeSocketErrorCategory(java.io.IOException("errno=-104")) == "RESET")
        assertTrue(ZeroTierForwardWorker.safeSocketErrorCategory(java.io.IOException("unavailable errno=2000")) == "OTHER")
        assertTrue(ZeroTierForwardWorker.readFailureReason(java.io.IOException("temporary failure errno=104")) == "read:IOException:RESET")
        assertTrue(ZeroTierForwardWorker.readFailureReason(java.net.SocketException("Connection reset by peer")) == "read:SocketException:RESET")
        assertTrue(ZeroTierForwardWorker.readFailureReason(java.io.IOException("private endpoint /secret?token=x")) == "read:IOException:OTHER")
        assertTrue(ZeroTierForwardWorker.writeFailureReason(java.net.SocketException("Broken pipe errno=32")) == "write:SocketException:BROKEN_PIPE")
    }

    @Test
    fun `response status classifier extracts only the numeric HTTP status`() {
        assertTrue(ZeroTierForwardWorker.classifyHttpStatus("HTTP/1.1 200 OK\r\nSet-Cookie: secret\r\n".toByteArray()) == 200)
        assertTrue(ZeroTierForwardWorker.classifyHttpStatus("HTTP/1.1 503 Service Unavailable\r\n".toByteArray()) == 503)
        assertTrue(ZeroTierForwardWorker.classifyHttpStatus("not an HTTP response\r\n".toByteArray()) == null)
        assertTrue(ZeroTierForwardWorker.classifyHttpStatus("HTTP/1.1 200".toByteArray()) == null)
    }

    @Test
    fun `request classification maps safe display labels into lifecycle categories`() {
        assertTrue(ZeroTierForwardWorker.requestClass("GET /api/remote.mux") == "GET_REMOTE_MUX")
        assertTrue(ZeroTierForwardWorker.requestClass("POST /api/session/list") == "POST_SESSION_API")
        assertTrue(ZeroTierForwardWorker.requestClass("POST other-path") == "POST_OTHER")
        assertTrue(ZeroTierForwardWorker.requestClass("tls-record") == "TLS")
        assertTrue(ZeroTierForwardWorker.requestClass("empty") == "EMPTY")
        assertTrue(ZeroTierForwardWorker.requestClass("ascii-letter") == "ASCII_OTHER")
    }

    @Test
    fun `request preface classifier recognizes TLS and safely buckets non HTTP prefixes`() {
        assertTrue(ZeroTierForwardWorker.classifyHttpPreface(byteArrayOf(0x16, 0x03, 0x01), complete = false) == "tls-record")
        assertTrue(ZeroTierForwardWorker.classifyHttpPreface(byteArrayOf(0x17, 0x03, 0x03), complete = false) == "tls-record")
        assertTrue(ZeroTierForwardWorker.classifyHttpPreface(byteArrayOf(1, 2, 3), complete = true) == "binary-other")
        assertTrue(ZeroTierForwardWorker.classifyHttpPreface(byteArrayOf(0x41, 0x42), complete = true) == "ascii-letter")
    }

    @Test
    fun `native EOF terminates relay immediately instead of polling as timeout`() {
        val executor = Executors.newFixedThreadPool(2)
        val localInput = CloseBlockingInputStream()
        val local = FakeSocket(localInput, ByteArrayOutputStream()) { localInput.release() }
        val finished = CountDownLatch(1)
        val worker = ZeroTierForwardWorker(
            local = local,
            remoteInput = java.io.ByteArrayInputStream(byteArrayOf()),
            remoteOutput = ByteArrayOutputStream(),
            closeRemote = {},
            executor = executor,
            onFinished = { finished.countDown() },
        )
        try {
            worker.start()
            assertTrue("native EOF must close loopback promptly", finished.await(1, TimeUnit.SECONDS))
            assertTrue(worker.diagnostics.remoteToLocalEnd == "native-eof")
            assertTrue(worker.diagnostics.remoteToLocalReadBytes == 0L)
            assertTrue(worker.diagnostics.remoteToLocalBytes == 0L)
            assertTrue(worker.diagnostics.localToRemoteReadBytes == 0L)
            assertTrue(worker.diagnostics.localToRemoteBytes == 0L)
        } finally { executor.shutdownNow() }
    }

    @Test
    fun `local EOF half closes native output while preserving host response`() {
        val executor = Executors.newFixedThreadPool(2)
        val localOutput = ByteArrayOutputStream()
        val remoteOutputShutdown = CountDownLatch(1)
        val workerFinished = CountDownLatch(1)
        val worker = ZeroTierForwardWorker(
            local = FakeSocket(EndOfStreamInputStream(), localOutput) {},
            remoteInput = java.io.ByteArrayInputStream("response".toByteArray()),
            remoteOutput = ByteArrayOutputStream(),
            closeRemote = {},
            shutdownRemoteOutput = { remoteOutputShutdown.countDown() },
            executor = executor,
            onFinished = { workerFinished.countDown() },
        )
        try {
            worker.start()
            assertTrue("client FIN was not forwarded as native half-close", remoteOutputShutdown.await(1, TimeUnit.SECONDS))
            assertTrue("host response did not finish", workerFinished.await(1, TimeUnit.SECONDS))
            assertTrue(localOutput.toString() == "response")
            assertTrue(worker.diagnostics.localToRemoteEnd == "local-eof")
        } finally { executor.shutdownNow() }
    }

    @Test
    fun `close wakes both directions before native socket closes`() {
        val executor = Executors.newFixedThreadPool(2)
        val localInput = CloseBlockingInputStream()
        val localOutput = ByteArrayOutputStream()
        val remoteInput = PollingInputStream()
        val local = FakeSocket(localInput, localOutput) { localInput.release() }
        val remoteClosed = AtomicBoolean(false)
        val workerFinished = CountDownLatch(1)
        val worker = ZeroTierForwardWorker(
            local = local,
            remoteInput = remoteInput,
            remoteOutput = ByteArrayOutputStream(),
            closeRemote = {
                assertTrue("local-to-remote copy must have exited", localInput.exited.await(0, TimeUnit.MILLISECONDS))
                assertTrue("remote-to-local copy must have exited", remoteInput.exited.await(0, TimeUnit.MILLISECONDS))
                remoteClosed.set(true)
            },
            executor = executor,
            onFinished = { workerFinished.countDown() },
        )

        try {
            worker.start()
            assertTrue(localInput.entered.await(1, TimeUnit.SECONDS))
            assertTrue(remoteInput.entered.await(1, TimeUnit.SECONDS))
            worker.close()

            assertTrue("forward worker did not finish", workerFinished.await(1, TimeUnit.SECONDS))
            assertTrue("native socket was not closed", remoteClosed.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `start racing close does not escape a closed loopback socket`() {
        val executor = Executors.newFixedThreadPool(2)
        val localInput = CloseBlockingInputStream()
        val local = FakeSocket(localInput, ByteArrayOutputStream()) { localInput.release() }
        val worker = ZeroTierForwardWorker(
            local = local,
            remoteInput = PollingInputStream(),
            remoteOutput = ByteArrayOutputStream(),
            closeRemote = {},
            executor = executor,
            onFinished = {},
        )
        try {
            worker.close()
            worker.start()
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `rejected forwarding tasks close the native socket`() {
        val remoteClosed = AtomicBoolean(false)
        val workerFinished = CountDownLatch(1)
        val rejectingExecutor = object : java.util.concurrent.AbstractExecutorService() {
            override fun shutdown() = Unit
            override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
            override fun isShutdown() = false
            override fun isTerminated() = false
            override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
            override fun execute(command: Runnable) = throw java.util.concurrent.RejectedExecutionException("closed")
        }
        val worker = ZeroTierForwardWorker(
            local = FakeSocket(CloseBlockingInputStream(), ByteArrayOutputStream()) {},
            remoteInput = PollingInputStream(),
            remoteOutput = ByteArrayOutputStream(),
            closeRemote = { remoteClosed.set(true) },
            executor = rejectingExecutor,
            onFinished = { workerFinished.countDown() },
        )

        worker.start()

        assertTrue(workerFinished.await(1, TimeUnit.SECONDS))
        assertTrue(remoteClosed.get())
    }

    @Test
    fun `native socket stays open until both forwarding directions exit`() {
        val executor = Executors.newFixedThreadPool(2)
        val localInput = CloseBlockingInputStream()
        val remoteInput = ControlledPollingInputStream()
        val local = FakeSocket(localInput, ByteArrayOutputStream()) { localInput.release() }
        val remoteClosed = AtomicBoolean(false)
        val workerFinished = CountDownLatch(1)
        val worker = ZeroTierForwardWorker(
            local = local,
            remoteInput = remoteInput,
            remoteOutput = ByteArrayOutputStream(),
            closeRemote = { remoteClosed.set(true) },
            executor = executor,
            onFinished = { workerFinished.countDown() },
        )

        try {
            worker.start()
            assertTrue(localInput.entered.await(1, TimeUnit.SECONDS))
            assertTrue(remoteInput.entered.await(1, TimeUnit.SECONDS))
            worker.close()
            assertTrue(localInput.exited.await(1, TimeUnit.SECONDS))
            assertFalse("native socket closed while remote read was active", remoteClosed.get())

            remoteInput.release()
            assertTrue(workerFinished.await(1, TimeUnit.SECONDS))
            assertTrue(remoteClosed.get())
        } finally {
            executor.shutdownNow()
        }
    }

    private class FakeSocket(
        private val input: InputStream,
        private val output: ByteArrayOutputStream,
        private val onClose: () -> Unit,
    ) : Socket() {
        override fun getInputStream(): InputStream = input
        override fun getOutputStream() = output
        override fun close() = onClose()
    }

    private class EndOfStreamInputStream : InputStream() {
        override fun read(): Int = -1
    }

    private class CloseBlockingInputStream : InputStream() {
        val entered = CountDownLatch(1)
        val exited = CountDownLatch(1)
        private val released = CountDownLatch(1)

        override fun read(): Int {
            entered.countDown()
            released.await()
            exited.countDown()
            return -1
        }

        fun release() {
            released.countDown()
        }
    }

    private class PollingInputStream : InputStream() {
        val entered = CountDownLatch(1)
        val exited = CountDownLatch(1)

        override fun read(): Int {
            entered.countDown()
            Thread.sleep(25)
            exited.countDown()
            return -1
        }
    }

    private class ControlledPollingInputStream : InputStream() {
        val entered = CountDownLatch(1)
        val exited = CountDownLatch(1)
        private val poll = CountDownLatch(1)

        override fun read(): Int {
            entered.countDown()
            poll.await()
            exited.countDown()
            return -1
        }

        fun release() {
            poll.countDown()
        }
    }
}
