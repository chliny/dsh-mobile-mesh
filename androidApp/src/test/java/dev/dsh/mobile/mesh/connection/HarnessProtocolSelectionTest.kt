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
    @Test fun `established reconnect skips optional protocol probes even when still undetermined`() {
        assertEquals(HarnessProtocol.PARENT_CATALOG, protocolForRecoveredConnection(true, true, HarnessProtocol.PARENT_CATALOG))
        assertEquals(HarnessProtocol.UNDETERMINED, protocolForRecoveredConnection(true, true, null))
        assertNull(protocolForRecoveredConnection(false, true, HarnessProtocol.PARENT_CATALOG))
        assertNull(protocolForRecoveredConnection(true, false, null))
    }

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
        assertEquals(HarnessProtocol.PARENT_CATALOG,
            protocolForRecoveredConnection(true, true, intent.selected))
        assertEquals(true, intent.supports(HarnessCapability.SUBAGENT_PROJECTION))
    }

    @Test fun `successful initial list supplies optional probes without another list request`() = runBlocking {
        val selection = HarnessProtocolSelection()
        val summary = list("""{"items":[{"sessionId":"known","agentAvailable":true}]}""")
        var calls = 0
        var currentAttemptSessionId: String? = null
        selection.detect {
            calls++
            RpcResult.Ok(summary).also { currentAttemptSessionId = sessionIdForCapabilityProbe(it.value) }
        }
        // ConnectionManager captures this ID from the same protocol probe. No second list is
        // necessary, and a later attempt cannot reuse a stale session ID.
        assertEquals("known", currentAttemptSessionId)
        assertEquals(1, calls)
        currentAttemptSessionId = null
        selection.detect { error("a cached protocol must not reuse the old session list") }
        assertNull(currentAttemptSessionId)
        assertEquals("known", sessionIdForCapabilityProbe(summary))
        assertNull(sessionIdForCapabilityProbe(list("""{"items":[{"sessionId":23}]}""")))
        assertNull(sessionIdForCapabilityProbe(list("""{"items":[]}""")))
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

    @Test fun `timed out probe reuses last confirmed version rather than defaulting`() = runBlocking {
        val selection = HarnessProtocolSelection(HarnessProtocol.PARENT_CATALOG)
        assertEquals(HarnessProtocol.PARENT_CATALOG, selection.detect(
            probe = { delay(1_000); error("timed out") },
            probeProjection = { error("must not run") },
            probeTimeoutMs = 20,
        ))
        assertEquals(true, selection.supports(HarnessCapability.SUBAGENT_PROJECTION))
        assertEquals(HarnessProtocol.PARENT_CATALOG, selection.detect { error("must not retry") })
    }

    @Test fun `failed projection probe reuses last confirmed legacy version`() = runBlocking {
        val selection = HarnessProtocolSelection(HarnessProtocol.LEGACY_SUBAGENTS)
        assertEquals(HarnessProtocol.LEGACY_SUBAGENTS, selection.detect(
            probe = { RpcResult.Ok(list("""{"items":[]}""")) },
            probeProjection = { RpcResult.Err(RpcError("offline", "network unreachable")) },
        ))
        assertEquals(false, selection.supports(HarnessCapability.SUBAGENT_PROJECTION))
    }

    @Test fun `successful probe replaces previously remembered version`() = runBlocking {
        val selection = HarnessProtocolSelection(HarnessProtocol.LEGACY_SUBAGENTS)
        assertEquals(HarnessProtocol.PARENT_CATALOG, selection.detect {
            RpcResult.Ok(list("""{"items":[{"sessionId":"s1","agentAvailable":true}]}"""))
        })
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

    @Test fun `terminal entry survives foreground reconnect without a session id or second probe`() = runBlocking {
        val selection = HarnessProtocolSelection()
        assertEquals(true, selection.probeBooleanCapability(HarnessCapability.TERMINAL) {
            RpcResult.Ok(emptyList<Any>())
        })
        // Reconnect deliberately skips session/list; replay the observed capability on publication
        // and again after a replacement generation completes its foreground liveness check.
        assertEquals(true, terminalCapabilityForConnection(selection, null, null))
        assertEquals(true, terminalCapabilityForConnection(selection, null, false))
        assertEquals(false, terminalCapabilityForConnection(HarnessProtocolSelection(), null, null))
    }

    @Test fun `unsupported terminal route remains hidden on reconnect and a new intent does not inherit it`() = runBlocking {
        val selection = HarnessProtocolSelection()
        selection.probeBooleanCapability(HarnessCapability.TERMINAL) {
            RpcResult.Err(RpcError("internal", "HTTP 404", TransportFailures.details(TransportFailure.NOT_FOUND, 404)))
        }
        assertEquals(false, terminalCapabilityForConnection(selection, null, true))
        assertEquals(false, terminalCapabilityForConnection(HarnessProtocolSelection(), null, null))
        assertEquals(true, terminalCapabilityForConnection(HarnessProtocolSelection(), "session", true))
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

    @Test fun `user questions projection is positive evidence cached for reconnect`() = runBlocking {
        val selection = HarnessProtocolSelection()
        assertNull(selection.supports(HarnessCapability.USER_QUESTIONS))
        selection.observeProjectionKeys(mapOf("todos" to kotlinx.serialization.json.JsonNull))
        assertNull(selection.supports(HarnessCapability.USER_QUESTIONS))
        selection.observeProjectionKeys(mapOf("userQuestions" to list("""{"active":[],"settled":[]}""")))
        assertEquals(true, selection.supports(HarnessCapability.USER_QUESTIONS))
        selection.observeProjectionKeys(emptyMap())
        // Background reconnect skips the session list and projection probe, but the same intent
        // still publishes this positive observation; an explicit new intent starts unknown.
        assertEquals(true, selection.supports(HarnessCapability.USER_QUESTIONS))
        assertNull(HarnessProtocolSelection().supports(HarnessCapability.USER_QUESTIONS))
    }

    @Test fun `authentication and unrelated failures do not select any version`() {
        val intent = HarnessProtocolSelection()
        assertThrows(ProtocolProbeException::class.java) {
            runBlocking { intent.detect { RpcResult.Err(RpcError("unauthenticated", "HTTP 401")) } }
        }
        assertNull(intent.selected)
    }
}
