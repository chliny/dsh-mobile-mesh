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
        private val input: CloseBlockingInputStream,
        private val output: ByteArrayOutputStream,
        private val onClose: () -> Unit,
    ) : Socket() {
        override fun getInputStream(): InputStream = input
        override fun getOutputStream() = output
        override fun close() = onClose()
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
