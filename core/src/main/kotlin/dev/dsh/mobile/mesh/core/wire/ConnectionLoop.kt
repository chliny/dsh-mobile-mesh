package dev.dsh.mobile.mesh.core.wire

import dev.dsh.mobile.mesh.core.wire.dto.HostDescription
import dev.dsh.mobile.mesh.core.wire.dto.REMOTE_EVENT_STREAM_ENDPOINT
import dev.dsh.mobile.mesh.core.wire.dto.RemoteEventFrame
import kotlin.math.pow
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.JsonElement

/** Loop connection state: `connected` once the mux is open and `$events` has said `ready`. */
enum class ConnectionState {
    CONNECTED,
    RECONNECTING,
}

/** Which part of the readiness handshake is under way. */
enum class HandshakeStep {
    /** The `/api/remote.mux` WebSocket is being opened. */
    OPENING_MUX,

    /** The socket is up; the `$events` logical stream is open and its ready frame is awaited. */
    AWAITING_READY,
}

/** Why one generation failed to reach [ConnectionState.CONNECTED]. */
sealed class GenerationFailure {
    /** The mux socket did not finish its handshake inside the budget. */
    data class MuxTimedOut(val timeoutMs: Long) : GenerationFailure()

    /**
     * The mux socket failed outright — a refused connection, or a rejected upgrade.
     *
     * A rejected upgrade is the common case against a 0.1.2 harness reached directly: the whole
     * `/api` surface, this socket included, wants a browser session, so an unpaired client is
     * refused here rather than on its first call.
     */
    data class MuxFailed(val kind: TransportFailure, val message: String?) : GenerationFailure()

    /** The socket opened but `$events` did not produce a usable ready frame. */
    data class ReadyFailed(val error: RpcError) : GenerationFailure()
}

/**
 * One established connection generation: the ready facts plus the mux carrying every stream.
 *
 * [mux] is handed to the sink rather than kept private because the streams a caller needs —
 * `session/follow`, `session/control`, `workspace/follow` — are opened on demand by the layers
 * above, and they all ride this one socket. It is valid only until the next
 * [ConnectionState.RECONNECTING].
 */
data class HostGeneration(
    /** Stable host facts from the ready frame. */
    val description: HostDescription,
    /**
     * This generation's client identity.
     *
     * Every `$events/result` reply carries it, and the host refuses one bound to a retired
     * generation — which is what stops an answer typed before a reconnect from resolving a
     * request the host has already replayed.
     */
    val clientId: String,
    /** The open mux; open further logical streams here. */
    val mux: RemoteStreamMux,
)

/** Callbacks from the connection loop. Exceptions thrown here never kill the loop. */
interface LoopSinks {
    /**
     * One frame from the `$events` stream: a notification, a pending waterfall, or a withdrawal.
     *
     * The ready frame is consumed by the handshake and does not arrive here.
     */
    fun onEventFrame(frame: RemoteEventFrame)

    /** The readiness handshake completed: the mux is open and `$events` said ready. */
    fun onConnected(generation: HostGeneration)

    /** The connection state changed. */
    fun onStateChange(state: ConnectionState)

    /** Progress within the current generation's handshake. Default no-op. */
    fun onHandshakeStep(step: HandshakeStep) {}

    /**
     * One generation failed; [attempt] is 1 for the first. The loop keeps retrying afterwards —
     * this reports *why* so a caller can say so rather than waiting on a timer that cannot know.
     * Default no-op.
     */
    fun onGenerationFailed(attempt: Int, failure: GenerationFailure) {}
}

/**
 * Reconnect/backoff policy. `delay` is injectable so tests can observe backoff growth without
 * real timers.
 */
class LoopConfig(
    /** Base backoff delay in ms (first reconnect sleeps in [baseDelayMs/2, baseDelayMs]). */
    val baseDelayMs: Long = 500L,
    /** Exponential growth factor per consecutive failed generation. */
    val backoffFactor: Double = 2.0,
    /** Upper bound on the per-attempt backoff cap in ms. */
    val maxDelayMs: Long = 10_000L,
    /** Jitter span bound: the actual sleep is uniform in [cap/2, cap] of the attempt cap. */
    val jitterCapMs: Long = 10_000L,
    /** How long a generation may take to open the mux socket before it is abandoned. */
    val streamOpenTimeoutMs: Long = 3_000L,
    /**
     * How long the ready frame may take once the socket is open.
     *
     * Separate from [streamOpenTimeoutMs] because it covers a different failure: the socket is
     * established and the host is simply not answering, which the TCP layer will not report.
     */
    val readyTimeoutMs: Long = 5_000L,
    /** Injectable sleep used between generations. */
    val delay: suspend (Long) -> Unit = ::defaultSleep,
)

/** Default reconnect sleep (delegates to kotlinx.coroutines.delay). */
private suspend fun defaultSleep(ms: Long) {
    delay(ms)
}

/**
 * Owns the readiness handshake and reconnect loop for the harness connection.
 *
 * 1. Open the `/api/remote.mux` WebSocket (open timeout 3s).
 * 2. Open the Gateway-internal `$events` logical stream and read its first item, which must be a
 *    `ready` frame. That frame — not a `host.describe` call — is what makes the generation
 *    connected: it proves the host installed its incremental listeners before answering, so no
 *    baseline read can race them.
 * 3. Forward `$events` frames to [LoopSinks] until the stream ends, fails, or the socket dies —
 *    then reconnect with exponential backoff (base 500ms, factor 2, max 10s, jitter cap/2..cap).
 *
 * The two sockets this replaces each carried a fixed frame union and needed no client message.
 * Here there is one socket, and everything else the app streams is a further logical stream on
 * it, opened through [HostGeneration.mux].
 *
 * [start] and [stop] are idempotent. Sink exceptions are contained and never kill the loop.
 */
class ConnectionLoop(
    private val muxFactory: suspend () -> RemoteStreamMux,
    private val sinks: LoopSinks,
    private val config: LoopConfig = LoopConfig(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val lifecycleLock = Any()

    @Volatile
    private var job: Job? = null

    /** Identifies the loop owner of [current], so a late cancellation cannot close a new mux. */
    private val generationToken = AtomicLong(0)
    private val loopToken = AtomicLong(0)

    @Volatile
    private var current: RemoteStreamMux? = null

    @Volatile
    private var currentToken: Long = 0L

    /** Begin the loop. Idempotent and serialized against [stop]. */
    fun start() = synchronized(lifecycleLock) {
        if (job != null) return@synchronized
        val token = loopToken.incrementAndGet()
        job = scope.launch { runLoop(token) }
    }

    /** Stop the loop and tear down the mux. Idempotent and serialized against [start]. */
    fun stop() = synchronized(lifecycleLock) {
        val running = job ?: return@synchronized
        job = null
        running.cancel()
        closeGeneration(currentToken)
    }

    // ---------------------------------------------------------------- loop

    private suspend fun runLoop(ownerToken: Long) {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            val startedAt = System.nanoTime()
            safeSink { sinks.onStateChange(ConnectionState.RECONNECTING) }
            val token = generationToken.incrementAndGet()
            when (val opened = openGeneration(ownerToken, token)) {
                is Opened.Ok -> {
                    val elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
                    println("ConnectionLoop: generation connected in ${elapsedMs}ms")
                    attempt = 0
                    safeSink { sinks.onConnected(opened.generation) }
                    safeSink { sinks.onStateChange(ConnectionState.CONNECTED) }
                    consumeEvents(opened.events, opened.generation.mux, opened.generation.clientId)
                    closeGeneration(token, ownerToken)
                }

                is Opened.Failed -> {
                    attempt += 1
                    val reported = attempt
                    closeGeneration(token, ownerToken)
                    safeSink { sinks.onGenerationFailed(reported, opened.failure) }
                }
            }
            if (!currentCoroutineContext().isActive) break
            // A transport recovery can deliberately replace the loop while the old generation is
            // still unwinding. Do not spend a backoff tick after a successful replacement.
            if (attempt == 0) continue
            config.delay(nextBackoff(attempt))
        }
    }

    /** Outcome of one handshake attempt: the ready generation, or why it did not open. */
    private sealed class Opened {
        data class Ok(val generation: HostGeneration, val events: RemoteStream) : Opened()
        data class Failed(val failure: GenerationFailure) : Opened()
    }

    /**
     * Open the mux, open `$events`, and read its ready frame — or report why none of that worked.
     *
     * The stream handle is read here only far enough to take its opening frame, then handed back
     * so [consumeEvents] continues the *same* logical stream. That is why the mux hands out a
     * handle rather than a cold flow: collecting a flow twice would open two event generations
     * for one connection.
     */
    private suspend fun openGeneration(ownerToken: Long, token: Long): Opened {
        safeSink { sinks.onHandshakeStep(HandshakeStep.OPENING_MUX) }
        val mux = try {
            muxFactory()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return Opened.Failed(
                GenerationFailure.MuxFailed(TransportFailures.classify(e), e.message),
            )
        }
        val openingIsActive = currentCoroutineContext().isActive
        synchronized(lifecycleLock) {
            if (job == null || loopToken.get() != ownerToken || !openingIsActive) {
                runCatching { mux.close() }
                throw CancellationException("connection loop stopped while opening mux")
            }
            current = mux
            currentToken = token
        }

        try {
            mux.start()
            // OkHttp delivers onOpen as the authoritative handshake completion event. The deadline
            // remains only a safety ceiling for a broken callback/transport, never the readiness
            // mechanism itself.
            if (withTimeoutOrNull(config.streamOpenTimeoutMs) { mux.awaitOpen() } == null) {
                return Opened.Failed(GenerationFailure.MuxTimedOut(config.streamOpenTimeoutMs))
            }
        } catch (e: TimeoutCancellationException) {
            return Opened.Failed(GenerationFailure.MuxTimedOut(config.streamOpenTimeoutMs))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return Opened.Failed(
                GenerationFailure.MuxFailed(TransportFailures.classify(e), e.message),
            )
        }

        safeSink { sinks.onHandshakeStep(HandshakeStep.AWAITING_READY) }
        val events = try {
            mux.open(REMOTE_EVENT_STREAM_ENDPOINT)
        } catch (e: RemoteStreamException) {
            return Opened.Failed(GenerationFailure.ReadyFailed(e.error))
        }
        val ready = try {
            // The ready frame is delivered by the mux stream event, not inferred from elapsed time.
            // Keep the timeout solely as a bounded failure path for a peer that never responds.
            withTimeout(config.readyTimeoutMs) { events.receive() }
        } catch (e: TimeoutCancellationException) {
            return Opened.Failed(
                GenerationFailure.ReadyFailed(
                    RpcError("internal", "no ready frame within ${config.readyTimeoutMs}ms"),
                ),
            )
        } catch (e: RemoteStreamException) {
            return Opened.Failed(GenerationFailure.ReadyFailed(e.error))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return Opened.Failed(
                GenerationFailure.ReadyFailed(RpcError("internal", e.message ?: "events stream failed")),
            )
        }

        if (ready == null) {
            return Opened.Failed(
                GenerationFailure.ReadyFailed(RpcError("internal", "events stream ended before it was ready")),
            )
        }
        val frame = decodeEventFrame(ready)
            ?: return Opened.Failed(
                GenerationFailure.ReadyFailed(RpcError("internal", "opening event frame did not parse: $ready")),
            )
        // A generation that opens on anything but `ready` is a protocol failure, not a frame to
        // skip: every later reply is bound to the clientId this frame carries, so without it
        // there is nothing to answer a waterfall with.
        if (frame !is RemoteEventFrame.Ready) {
            return Opened.Failed(
                GenerationFailure.ReadyFailed(
                    RpcError("internal", "events stream opened with \"${frame.type}\", not \"ready\""),
                ),
            )
        }

        return Opened.Ok(
            generation = HostGeneration(
                description = HostDescription(home = frame.host.home),
                clientId = frame.clientId,
                mux = mux,
            ),
            events = events,
        )
    }

    /** Forward `$events` frames until its carrier fails. */
    private suspend fun consumeEvents(events: RemoteStream, mux: RemoteStreamMux, clientId: String) {
        try {
            while (true) {
                val value = events.receive()
                if (value == null) {
                    // `$events` can be a finite bootstrap stream. Its clean end does not close the
                    // shared mux, whose session streams remain usable until the carrier ends.
                    mux.awaitClosed()
                    return
                }
                val frame = decodeEventFrame(value)
                // One unparseable frame is not worth ending a generation over: the allowlist
                // upstream grows and the union already passes unknown kinds through, so only a
                // frame that is not an object at all lands here.
                if (frame != null) {
                    val bound = if (frame is RemoteEventFrame.Waterfall) {
                        frame.copy(clientId = clientId)
                    } else frame
                    safeSink { sinks.onEventFrame(bound) }
                }
            }
        } catch (e: CancellationException) {
            // A transport replacement cancels the old loop. It is not a carrier failure and must
            // not turn into a visible "remote stream carrier failed" retry attempt.
            throw e
        } catch (e: RemoteStreamException) {
            if (!e.carrier) {
                // `$events` is one logical stream on the shared mux. A host-level failure of
                // that stream does not invalidate the session/control streams on the same socket.
                mux.awaitClosed()
            }
        } finally {
            // The shared mux owns the carrier lifecycle. Cancelling the logical events stream here
            // can race a reconnect/close on native-backed transports; close the generation instead.
        }
    }

    private fun decodeEventFrame(value: JsonElement): RemoteEventFrame? = try {
        decodeFromJsonElement(RemoteEventFrame.serializer(), value)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    /** Tear down the current generation's socket. */
    private fun closeGeneration(
        token: Long,
        ownerToken: Long = loopToken.get(),
        allowWhenStopped: Boolean = false,
    ) {
        val mux = synchronized(lifecycleLock) {
            if ((!allowWhenStopped && loopToken.get() != ownerToken) || currentToken != token) return
            val value = current
            current = null
            currentToken = 0L
            value
        }
        if (mux != null) runCatching { mux.close() }
    }

    /**
     * Exponential backoff with jitter: the attempt cap is min(maxDelay, base * factor^attempt),
     * and the actual sleep is uniform in [cap/2, cap].
     */
    private fun nextBackoff(attempt: Int): Long {
        val cap = minOf(
            config.maxDelayMs,
            (config.baseDelayMs * config.backoffFactor.pow(attempt)).toLong(),
        ).coerceAtLeast(config.baseDelayMs)
        val bounded = minOf(cap, config.jitterCapMs)
        val half = bounded / 2
        val span = bounded - half
        return if (span <= 0) half else half + Random.nextLong(span + 1)
    }

    /** Sink exceptions must not kill the loop. */
    private inline fun safeSink(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            // Contain: a misbehaving sink must not take the loop down.
        }
    }

}
