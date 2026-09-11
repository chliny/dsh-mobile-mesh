package dev.dsh.mobile.mesh.connection

import dev.dsh.mobile.mesh.core.wire.DshApiClient
import dev.dsh.mobile.mesh.core.wire.OkHttpRpcTransport
import dev.dsh.mobile.mesh.core.wire.RemoteStreamMux
import dev.dsh.mobile.mesh.core.wire.WsChannel
import dev.dsh.mobile.mesh.core.wire.dto.REMOTE_STREAM_MUX_PATH
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place a [DshApiClient] is built.
 *
 * Three facts have to agree for a call to reach a relay at all — the scheme, the certificate pin and
 * the bearer token — and they have to agree across the unary transport *and* the mux upgrade,
 * because the connection loop needs both to succeed inside one 3000ms generation. A relay refuses
 * an upgrade that arrives without the header, and the loop can only report that as a stream that
 * would not open. Splitting the assembly across the manager and the discovery engine is how one of
 * the three quietly goes missing, so it happens here or nowhere.
 *
 * Harness 0.1.2 reduced two downlink sockets to one, so there is a single [RemoteStreamMux] per
 * connection generation rather than a socket factory the client calls twice.
 */
@Singleton
class HarnessClientFactory @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val sessions: HarnessSessionStore,
) {
    private suspend fun cookieFor(config: HostConfig): String? = sessions.cookie(config.id)

    /**
     * A client for [config], carrying whatever credential and pin that endpoint needs.
     *
     * [timeouts] is for probes; the live connection takes the transport's own 30s defaults, because
     * a long `session/page` on a big session is not a stalled request.
     */
    suspend fun clientFor(
        config: HostConfig,
        timeouts: ProbeTimeouts? = null,
        baseUrl: String = config.baseUrl,
    ): DshApiClient {
        return DshApiClient(
            transport = OkHttpRpcTransport(
                baseUrl = baseUrl,
                client = okHttpClient,
                connectTimeoutMs = timeouts?.connectMs ?: DEFAULT_TIMEOUT_MS,
                readTimeoutMs = timeouts?.readMs ?: DEFAULT_TIMEOUT_MS,
                cookie = cookieFor(config),
                hostHeader = config.harnessAuthority,
            ),
        )
    }

    /**
     * The mux carrying every stream of one connection generation.
     *
     * Separate from [clientFor] because its lifetime is the generation's, not the client's: the
     * connection loop builds a new one per attempt and closes it when the generation ends, while
     * the unary client outlives both.
     */
    suspend fun muxFor(config: HostConfig, baseUrl: String = config.baseUrl): RemoteStreamMux {
        val cookie = cookieFor(config)
        return RemoteStreamMux { sink ->
            WsChannel("$baseUrl$REMOTE_STREAM_MUX_PATH", okHttpClient, sink, cookie, config.harnessAuthority)
        }
    }

    /**
     * A client for an address nothing is remembered about yet — the LAN sweep and the manual field.
     *
     * Deliberately unauthenticated: an address that has not been paired has no credential to send,
     * and a relay answers such a probe with the 403 that routes the user to pairing.
     */
    fun anonymousClient(baseUrl: String, timeouts: ProbeTimeouts): DshApiClient = DshApiClient(
        transport = OkHttpRpcTransport(
            baseUrl = baseUrl,
            client = okHttpClient,
            connectTimeoutMs = timeouts.connectMs,
            readTimeoutMs = timeouts.readMs,
        ),
    )

    private companion object {
        /** The transport's own default, restated so a null [ProbeTimeouts] is explicit rather than magic. */
        const val DEFAULT_TIMEOUT_MS = 30_000L
    }
}
