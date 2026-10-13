package dev.dsh.mobile.mesh.connection

import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedConnectRegistryTest {
    @Test
    fun `relay retirement fences blocked native connect and closes it on bounded completion`() {
        val registry = BoundedConnectRegistry<FakeSocket> { it.close() }
        val enteredConnect = CountDownLatch(1)
        val releaseConnect = CountDownLatch(1)
        val finishedConnect = CountDownLatch(1)
        val socket = FakeSocket()
        assertTrue(registry.register(socket))

        val thread = Thread {
            enteredConnect.countDown()
            releaseConnect.await()
            // The connect completed after its owner retired; transfer must fail and close it.
            assertFalse(registry.finish(socket, connected = true))
            finishedConnect.countDown()
        }
        thread.start()

        assertTrue("connect did not start", enteredConnect.await(1, TimeUnit.SECONDS))
        registry.close()
        assertFalse("do not close a native socket concurrently with blocking connect", socket.closed.get())
        releaseConnect.countDown()
        assertTrue("timed-out connect did not finish cleanup", finishedConnect.await(1, TimeUnit.SECONDS))
        assertTrue("late socket must be closed", socket.closed.get())
        thread.join(1_000)
    }

    @Test
    fun `late socket registration after relay retirement closes immediately`() {
        val registry = BoundedConnectRegistry<FakeSocket> { it.close() }
        val socket = FakeSocket()
        registry.close()
        assertFalse(registry.register(socket))
        assertTrue(socket.closed.get())
    }

    @Test
    fun `successful connect transfers ownership to its caller`() {
        val registry = BoundedConnectRegistry<FakeSocket> { it.close() }
        val socket = FakeSocket()
        assertTrue(registry.register(socket))
        assertTrue(registry.finish(socket, connected = true))
        registry.close()
        assertFalse("transferred socket remains owned by the forwarding worker", socket.closed.get())
        socket.close()
    }

    private class FakeSocket : Closeable {
        val closed = AtomicBoolean(false)
        override fun close() {
            closed.set(true)
        }
    }
}
