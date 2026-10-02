package dev.dsh.mobile.mesh.connection

import dev.dsh.mobile.mesh.core.wire.RpcError
import dev.dsh.mobile.mesh.core.wire.RpcResult
import dev.dsh.mobile.mesh.core.wire.TransportFailure
import dev.dsh.mobile.mesh.core.wire.TransportFailures
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class HarnessProtocolSelectionTest {
    private fun list(json: String) = Json.parseToJsonElement(json)

    @Test fun `new summary field selects parent catalog once across reconnect`() = runBlocking {
        val intent = HarnessProtocolSelection()
        var calls = 0
        val probe: suspend () -> RpcResult<JsonElement> = {
            calls++
            RpcResult.Ok(list("""{"items":[{"sessionId":"s1","agentAvailable":false}]}"""))
        }
        assertEquals(HarnessProtocol.PARENT_CATALOG, intent.detect(probe))
        assertEquals(HarnessProtocol.PARENT_CATALOG, intent.detect(probe))
        assertEquals(1, calls)
    }

    @Test fun `old summary selects legacy only for this manual connection intent`() = runBlocking {
        val old = HarnessProtocolSelection()
        val response = RpcResult.Ok(list("""{"items":[{"sessionId":"s1"}]}"""))
        assertEquals(HarnessProtocol.LEGACY_SUBAGENTS, old.detect { response })
        assertEquals(HarnessProtocol.LEGACY_SUBAGENTS, old.detect {
            RpcResult.Ok(list("""{"items":[{"sessionId":"s1","agentAvailable":true}]}"""))
        })
        assertEquals(HarnessProtocol.PARENT_CATALOG, HarnessProtocolSelection().detect {
            RpcResult.Ok(list("""{"items":[{"sessionId":"s1","agentAvailable":true}]}"""))
        })
    }

    @Test fun `version discriminator can gate the modern permission catalog`() {
        val old = HarnessProtocolSelection()
        val modern = HarnessProtocolSelection()
        runBlocking {
            assertEquals(HarnessProtocol.LEGACY_SUBAGENTS, old.detect {
                RpcResult.Ok(list("""{"items":[{"sessionId":"s1"}]}"""))
            })
            assertEquals(HarnessProtocol.PARENT_CATALOG, modern.detect {
                RpcResult.Ok(list("""{"items":[{"sessionId":"s1","agentAvailable":true}]}"""))
            })
        }
        assertEquals(false, old.selected == HarnessProtocol.PARENT_CATALOG)
        assertEquals(true, modern.selected == HarnessProtocol.PARENT_CATALOG)
    }

    @Test fun `empty or inconsistent summaries cannot be guessed as a version`() {
        assertNull(classifySessionList(list("""{"items":[]}""")))
        assertNull(classifySessionList(list("""{"items":[{"sessionId":"a"},{"sessionId":"b","agentAvailable":true}]}""")))
        assertNull(classifySessionList(list("""{"items":[{"sessionId":"a","agentAvailable":"true"}]}""")))
    }

    @Test fun `empty corpus probes projection only to recognize missing legacy route`() = runBlocking {
        var calls = 0
        val intent = HarnessProtocolSelection()
        assertEquals(HarnessProtocol.LEGACY_SUBAGENTS, intent.detect(
            probe = { calls++; RpcResult.Ok(list("""{"items":[]}""")) },
            probeProjection = {
                calls++
                RpcResult.Err(RpcError("capability-unavailable", "HTTP 404",
                    TransportFailures.details(TransportFailure.NOT_FOUND, 404)))
            },
        ))
        assertEquals(HarnessProtocol.LEGACY_SUBAGENTS, intent.detect { error("retry must not probe") })
        assertEquals(2, calls)
    }

    @Test fun `empty corpus with successful generic projection stays undetermined`() = runBlocking {
        val intent = HarnessProtocolSelection()
        assertEquals(HarnessProtocol.UNDETERMINED, intent.detect(
            probe = { RpcResult.Ok(list("""{"items":[]}""")) },
            probeProjection = { RpcResult.Ok(kotlinx.serialization.json.JsonNull) },
        ))
        assertEquals(HarnessProtocol.UNDETERMINED, intent.detect { error("retry must not probe") })
    }

    @Test fun `session list probe timeout keeps transport publishable as undetermined`() = runBlocking {
        val intent = HarnessProtocolSelection()
        assertEquals(HarnessProtocol.UNDETERMINED, withTimeout(1_000) {
            intent.detect(
                probe = { delay(1_000); error("probe should time out") },
                probeProjection = { error("projection probe should not run") },
                probeTimeoutMs = 20,
            )
        })
        assertEquals(HarnessProtocol.UNDETERMINED, intent.selected)
    }

    @Test fun `projection probe timeout on empty corpus does not fail connection`() = runBlocking {
        val intent = HarnessProtocolSelection()
        assertEquals(HarnessProtocol.UNDETERMINED, withTimeout(1_000) {
            intent.detect(
                probe = { RpcResult.Ok(list("""{"items":[]}""")) },
                probeProjection = { delay(1_000); error("probe should time out") },
                probeTimeoutMs = 20,
            )
        })
        assertEquals(HarnessProtocol.UNDETERMINED, intent.selected)
    }

    @Test fun `terminal capability probe only accepts existing route and caches result`() = runBlocking {
        val modern = HarnessProtocolSelection()
        var calls = 0
        assertEquals(true, modern.probeBooleanCapability(HarnessCapability.TERMINAL) {
            calls++
            RpcResult.Ok(emptyList<Any>())
        })
        assertEquals(true, modern.probeBooleanCapability(HarnessCapability.TERMINAL) {
            error("cached terminal capability must not probe again")
        })
        assertEquals(1, calls)

        val old = HarnessProtocolSelection()
        assertEquals(false, old.probeBooleanCapability(HarnessCapability.TERMINAL) {
            RpcResult.Err(RpcError("internal", "HTTP 404", TransportFailures.details(TransportFailure.NOT_FOUND, 404)))
        })
        assertEquals(false, old.supports(HarnessCapability.TERMINAL))
    }

    @Test fun `terminal capability refuses to interpret auth errors as missing route`() {
        val selection = HarnessProtocolSelection()
        assertThrows(ProtocolProbeException::class.java) {
            runBlocking { selection.probeBooleanCapability(HarnessCapability.TERMINAL) {
                RpcResult.Err(RpcError("unauthenticated", "HTTP 401"))
            } }
        }
        assertNull(selection.supports(HarnessCapability.TERMINAL))
    }

    @Test fun `authentication and unrelated failures do not select any version`() {
        val intent = HarnessProtocolSelection()
        assertThrows(ProtocolProbeException::class.java) {
            runBlocking { intent.detect { RpcResult.Err(RpcError("unauthenticated", "HTTP 401")) } }
        }
        assertNull(intent.selected)
    }
}
