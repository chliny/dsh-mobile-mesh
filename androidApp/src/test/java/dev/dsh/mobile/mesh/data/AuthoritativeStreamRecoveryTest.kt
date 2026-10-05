package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.core.wire.RemoteStreamException
import dev.dsh.mobile.mesh.core.wire.RpcError
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class AuthoritativeStreamRecoveryTest {
    @Test fun `a slow logical stream reopens and accepts the next authoritative baseline`() = runBlocking {
        var opens = 0
        var current = true
        val seen = mutableListOf<String>()
        val waits = mutableListOf<Long>()
        followAuthoritativeStream(
            current = { current },
            open = {
                opens++
                flow {
                    emit(JsonPrimitive(if (opens == 1) "old-baseline" else "new-baseline"))
                    if (opens == 1) throw RemoteStreamException(RpcError("consumer_fell_behind", "overflow"), false)
                }
            },
            onItem = { item ->
                seen += (item as JsonPrimitive).content
                if (seen.size == 2) current = false
            },
            onFailure = { assertEquals("consumer_fell_behind", (it as RemoteStreamException).error.code) },
            waitBeforeRetry = { waits += it },
        )
        assertEquals(2, opens)
        assertEquals(listOf("old-baseline", "new-baseline"), seen)
        assertEquals(listOf(1_000L), waits)
    }

    @Test fun `missing optional remote and retired generations do not spin`() = runBlocking {
        var opens = 0
        followAuthoritativeStream(
            current = { true },
            open = {
                opens++
                flow { throw RemoteStreamException(RpcError("gateway/invocation-unavailable", "missing"), false) }
            },
            onItem = {},
            onFailure = {},
            waitBeforeRetry = { error("unavailable route must not retry") },
        )
        assertEquals(1, opens)
    }
}
