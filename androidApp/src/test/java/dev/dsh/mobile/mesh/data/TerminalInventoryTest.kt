package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.core.wire.RpcError
import dev.dsh.mobile.mesh.core.wire.RpcResult
import dev.dsh.mobile.mesh.core.wire.dto.TerminalShell
import dev.dsh.mobile.mesh.core.wire.dto.WebTerminalInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `terminal/list` stays Server-owned: the inventory only retains what the Host returned, shares one
 * in-flight request, and reloads once when the connection generation changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalInventoryTest {
    private val shell = TerminalShell(path = "/bin/bash", args = listOf("-i"), name = "Bash")

    private fun terminal(id: String, title: String = id, state: String = "running") = WebTerminalInfo(
        id = id,
        title = title,
        shell = shell,
        cwd = "/work",
        cols = 80,
        rows = 24,
        state = state,
        exitCode = null,
    )

    @Test fun `second read answers from the cached Host list`() = runTest {
        var calls = 0
        val inventory = TerminalInventory({ "gen-1" }, { calls++; RpcResult.Ok(listOf(terminal("a"))) })
        assertEquals(listOf("a"), inventory.read().map { it.id })
        assertEquals(listOf("a"), inventory.read().map { it.id })
        assertEquals(1, calls)
    }

    @Test fun `explicit refresh replaces a settled list`() = runTest {
        var calls = 0
        val inventory = TerminalInventory({ "gen-1" }, {
            calls++
            RpcResult.Ok(listOf(terminal(if (calls == 1) "old" else "new")))
        })
        assertEquals(listOf("old"), inventory.read().map { it.id })
        assertEquals(listOf("new"), inventory.read(refresh = true).map { it.id })
        assertEquals(listOf("new"), inventory.read().map { it.id })
        assertEquals(2, calls)
    }

    @Test fun `concurrent reads share one list request`() = runTest {
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        val inventory = TerminalInventory({ "gen-1" }, {
            calls++
            gate.await()
            RpcResult.Ok(listOf(terminal("a")))
        })
        val first = async { inventory.read() }
        runCurrent()
        val second = async { inventory.read() }
        runCurrent()
        assertEquals(1, calls)
        gate.complete(Unit)
        assertEquals(listOf("a"), first.await().map { it.id })
        assertEquals(listOf("a"), second.await().map { it.id })
        assertEquals(1, calls)
    }

    @Test fun `host refusal is remembered until an explicit refresh`() = runTest {
        var calls = 0
        var refuse = true
        val inventory = TerminalInventory({ "gen-1" }, {
            calls++
            if (refuse) RpcResult.Err(RpcError("terminal/unavailable", "no list"))
            else RpcResult.Ok(listOf(terminal("a")))
        })
        val cached = runCatching { inventory.read() }.exceptionOrNull()
        assertEquals("no list", (cached as TerminalListFailure).message)
        assertEquals(TerminalListFailure::class.java, runCatching { inventory.read() }.exceptionOrNull()!!::class.java)
        assertEquals(1, calls)
        refuse = false
        assertEquals(listOf("a"), inventory.read(refresh = true).map { it.id })
        assertEquals(2, calls)
    }

    @Test fun `new connection generation makes the cached list stale`() = runTest {
        var generation: Any? = "gen-1"
        var calls = 0
        val inventory = TerminalInventory({ generation }, {
            calls++
            RpcResult.Ok(listOf(terminal("a")))
        })
        inventory.read()
        inventory.read()
        assertEquals(1, calls)
        generation = "gen-2"
        inventory.read()
        assertEquals(2, calls)
        inventory.read()
        assertEquals(2, calls)
    }

    @Test fun `list response from a replaced connection never publishes`() = runTest {
        var generation: Any? = "gen-1"
        var calls = 0
        val staleGate = CompletableDeferred<Unit>()
        val inventory = TerminalInventory({ generation }, {
            calls++
            if (calls == 1) {
                staleGate.await()
                RpcResult.Ok(listOf(terminal("stale")))
            } else {
                RpcResult.Ok(listOf(terminal("current")))
            }
        })
        val stale = async { inventory.read() }
        runCurrent()
        generation = "gen-2"
        assertEquals(listOf("current"), inventory.read(refresh = true).map { it.id })
        staleGate.complete(Unit)
        assertEquals(listOf("current"), stale.await().map { it.id })
        assertEquals(listOf("current"), inventory.read().map { it.id })
        assertEquals(2, calls)
    }

    @Test fun `ordinary read replaces old host list still in flight`() = runTest {
        var generation: Any? = Any()
        val nextGeneration = Any()
        var calls = 0
        val oldReply = CompletableDeferred<Unit>()
        val inventory = TerminalInventory({ generation }, {
            calls++
            if (calls == 1) {
                oldReply.await()
                RpcResult.Ok(listOf(terminal("old-host")))
            } else RpcResult.Ok(listOf(terminal("new-host")))
        })
        val oldReader = async { inventory.read() }
        runCurrent()
        generation = nextGeneration
        val newReader = async { inventory.read() }
        runCurrent()
        assertEquals(2, calls)
        assertEquals(listOf("new-host"), newReader.await().map { it.id })
        oldReply.complete(Unit)
        assertEquals(listOf("new-host"), oldReader.await().map { it.id })
        assertEquals(listOf("new-host"), inventory.read().map { it.id })
    }

    @Test fun `old host reply without another reader reloads current generation`() = runTest {
        var generation: Any? = Any()
        var calls = 0
        val oldReply = CompletableDeferred<Unit>()
        val inventory = TerminalInventory({ generation }, {
            calls++
            if (calls == 1) {
                oldReply.await()
                RpcResult.Ok(listOf(terminal("old-host")))
            } else RpcResult.Ok(listOf(terminal("new-host")))
        })
        val oldReader = async { inventory.read() }
        runCurrent()
        generation = Any()
        oldReply.complete(Unit)
        assertEquals(listOf("new-host"), oldReader.await().map { it.id })
        assertEquals(2, calls)
    }

    @Test fun `confirmed creation and closure amend the cached list`() = runTest {
        var calls = 0
        val inventory = TerminalInventory({ "gen-1" }, { calls++; RpcResult.Ok(listOf(terminal("a"))) })
        inventory.read()
        inventory.update(terminal("b", title = "B"))
        assertEquals(listOf("a", "b"), inventory.read().map { it.id })
        inventory.update(terminal("a", title = "Renamed"))
        assertEquals(listOf("Renamed", "B"), inventory.read().map { it.title })
        inventory.remove("a")
        assertEquals(listOf("b"), inventory.read().map { it.id })
        assertEquals(1, calls)
    }

    @Test fun `mutation during an in-flight list survives its response`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val inventory = TerminalInventory({ "gen-1" }, {
            gate.await()
            RpcResult.Ok(listOf(terminal("listed")))
        })
        val reading = async { inventory.read() }
        runCurrent()
        inventory.update(terminal("created"))
        gate.complete(Unit)
        assertEquals(listOf("listed", "created"), reading.await().map { it.id })
    }

    @Test fun `cancelled list request is not cached as a failure`() = runTest {
        var calls = 0
        val firstGate = CompletableDeferred<Unit>()
        val inventory = TerminalInventory({ "gen-1" }, {
            calls++
            if (calls == 1) firstGate.await()
            RpcResult.Ok(listOf(terminal("a")))
        })
        val reading = async { inventory.read() }
        runCurrent()
        reading.cancel()
        runCurrent()
        assertEquals(listOf("a"), inventory.read().map { it.id })
        assertEquals(2, calls)
    }

    @Test fun `offline failure clears after a reconnect`() = runTest {
        var generation: Any? = null
        var online = false
        val inventory = TerminalInventory({ generation }, {
            if (!online) throw TerminalListOffline()
            RpcResult.Ok(listOf(terminal("a")))
        })
        assertEquals(TerminalListOffline::class.java, runCatching { inventory.read() }.exceptionOrNull()!!::class.java)
        online = true
        generation = "gen-2"
        assertEquals(listOf("a"), inventory.read().map { it.id })
    }

    @Test fun `empty Host list is cached like any other result`() = runTest {
        var calls = 0
        val inventory = TerminalInventory({ "gen-1" }, { calls++; RpcResult.Ok(emptyList()) })
        assertEquals(emptyList<WebTerminalInfo>(), inventory.read())
        assertEquals(emptyList<WebTerminalInfo>(), inventory.read())
        assertEquals(1, calls)
    }
}
