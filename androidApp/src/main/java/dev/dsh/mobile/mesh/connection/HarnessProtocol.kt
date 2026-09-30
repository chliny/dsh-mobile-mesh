package dev.dsh.mobile.mesh.connection

import dev.dsh.mobile.mesh.core.wire.RpcResult
import dev.dsh.mobile.mesh.core.wire.TransportFailure
import dev.dsh.mobile.mesh.core.wire.TransportFailures
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Session API generation: a parent-catalog host replaced `subagents/list`. */
enum class HarnessProtocol { LEGACY_SUBAGENTS, PARENT_CATALOG, UNDETERMINED }

enum class HarnessCapability { SUBAGENT_PROJECTION }

/** Keep optional version discovery from consuming the whole connection operation timeout. */
internal const val HARNESS_PROTOCOL_PROBE_TIMEOUT_MS = 8_000L

/** One connection intent's observed protocol; automatic retries reuse its settled result. */
internal class HarnessProtocolSelection {
    private val lock = Mutex()
    @Volatile var selected: HarnessProtocol? = null
        private set
    private val mutableCapabilities = mutableMapOf<HarnessCapability, Boolean>()
    val capabilities: Map<HarnessCapability, Boolean> get() = synchronized(mutableCapabilities) { mutableCapabilities.toMap() }

    fun supports(capability: HarnessCapability): Boolean? =
        synchronized(mutableCapabilities) { mutableCapabilities[capability] }

    private fun record(capability: HarnessCapability, supported: Boolean) {
        synchronized(mutableCapabilities) { mutableCapabilities[capability] = supported }
    }

    suspend fun probeBooleanCapability(
        capability: HarnessCapability,
        probe: suspend () -> RpcResult<*>,
    ): Boolean = lock.withLock {
        supports(capability)?.let { return@withLock it }
        val supported = when (val result = probe()) {
            is RpcResult.Ok -> true
            is RpcResult.Err -> when {
                TransportFailures.of(result.error) == TransportFailure.NOT_FOUND &&
                    TransportFailures.statusOf(result.error) == 404 -> false
                result.error.code == "gateway/invocation-unavailable" -> false
                else -> throw ProtocolProbeException(result.error.code, result.error.message)
            }
        }
        record(capability, supported)
        supported
    }

    suspend fun detect(probe: suspend () -> RpcResult<JsonElement>): HarnessProtocol =
        detect(probe, probeProjection = {
            throw ProtocolProbeException("inconclusive", "session/list returned no usable session summaries")
        })

    suspend fun detect(
        probe: suspend () -> RpcResult<JsonElement>,
        probeProjection: suspend () -> RpcResult<*>,
        probeTimeoutMs: Long = HARNESS_PROTOCOL_PROBE_TIMEOUT_MS,
    ): HarnessProtocol = lock.withLock {
        selected?.let { return@withLock it }
        val next = try {
            val result = withTimeoutOrNull(probeTimeoutMs) { probe() }
            classifyProbeResult(result, probeProjection, probeTimeoutMs)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (probeFailure: ProtocolProbeException) {
            throw probeFailure
        } catch (_: Throwable) {
            HarnessProtocol.UNDETERMINED
        }
        selected = next
        record(HarnessCapability.SUBAGENT_PROJECTION, next == HarnessProtocol.PARENT_CATALOG)
        next
    }

    private suspend fun classifyProbeResult(
        result: RpcResult<JsonElement>?,
        probeProjection: suspend () -> RpcResult<*>,
        probeTimeoutMs: Long,
    ): HarnessProtocol = when (result) {
            null -> HarnessProtocol.UNDETERMINED
            is RpcResult.Ok -> classifySessionList(result.value) ?: run {
                val projection = withTimeoutOrNull(probeTimeoutMs) { probeProjection() }
                    ?: return@run HarnessProtocol.UNDETERMINED
                classifyProjectionRoute(projection)
            }
            is RpcResult.Err -> {
                // A temporary timeout must not tear down a transport that has already completed
                // its ready handshake. Leave the API family unknown; the session-list baseline will
                // retry against the published client and report a real data-fetch error if needed.
                if (TransportFailures.of(result.error) == TransportFailure.TIMEOUT ||
                    TransportFailures.of(result.error) == TransportFailure.OTHER && result.error.message.contains("timeout", ignoreCase = true)
                ) {
                    HarnessProtocol.UNDETERMINED
                } else {
                    // No Session Controller can serve the app; a missing *list* route does not identify
                    // a usable older release. Preserve the transport diagnostic without guessing.
                    val kind = TransportFailures.of(result.error)
                    throw ProtocolProbeException(
                        result.error.code,
                        if (kind == TransportFailure.NOT_FOUND) "session/list is unavailable" else result.error.message,
                    )
                }
            }
        }
    }

/** The mandatory `agentAvailable` summary field arrived in the same backend change as the catalog. */
internal fun classifySessionList(value: JsonElement): HarnessProtocol? {
    val rows = ((value as? JsonObject)?.get("items") as? JsonArray) ?: return null
    if (rows.isEmpty()) return null // An empty corpus has no release discriminator: never guess.
    val availability = rows.map { (it as? JsonObject)?.get("agentAvailable") }
    if (availability.all { it is JsonPrimitive && !it.isString && it.booleanOrNull != null }) {
        return HarnessProtocol.PARENT_CATALOG
    }
    if (availability.all { it == null }) return HarnessProtocol.LEGACY_SUBAGENTS
    return null
}

/** Empty session lists cannot reveal the release; a missing projection route can still identify legacy. */
internal fun classifyProjectionRoute(result: RpcResult<*>): HarnessProtocol = when (result) {
    is RpcResult.Ok -> HarnessProtocol.UNDETERMINED
    is RpcResult.Err -> {
        if (TransportFailures.of(result.error) == TransportFailure.NOT_FOUND ||
            result.error.code == "gateway/invocation-unavailable" &&
            (result.error.details as? JsonObject)?.get("endpoint") == JsonPrimitive("session/projections")
        ) HarnessProtocol.LEGACY_SUBAGENTS
        else throw ProtocolProbeException(result.error.code, result.error.message)
    }
}

internal class ProtocolProbeException(code: String, message: String) :
    Exception("Could not determine Harness session API ($code): $message")
