package dev.dsh.mobile.mesh.update

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ConclusiveCheckGateTest {
    @Test
    fun `transient failure permits retry in same process`() = runTest {
        val gate = ConclusiveCheckGate()
        var attempts = 0

        gate.run { attempts++; false }
        gate.run { attempts++; true }

        assertEquals(2, attempts)
    }

    @Test
    fun `conclusive result is at most once even for concurrent callers`() = runTest {
        val gate = ConclusiveCheckGate()
        var attempts = 0

        val calls = List(8) {
            async { gate.run { attempts++; true } }
        }
        calls.forEach { it.await() }
        gate.run { attempts++; true }

        assertEquals(1, attempts)
    }
}
