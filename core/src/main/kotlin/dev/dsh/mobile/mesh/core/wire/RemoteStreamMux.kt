package dev.dsh.mobile.mesh.core.wire

import dev.dsh.mobile.mesh.core.wire.dto.UserQuestionWaitRemaining
import dev.dsh.mobile.mesh.core.wire.dto.RemoteStreamClientMessage
import dev.dsh.mobile.mesh.core.wire.dto.RemoteStreamClientMessageSerializer
import dev.dsh.mobile.mesh.core.wire.dto.RemoteStreamServerMessage
import dev.dsh.mobile.mesh.core.wire.dto.RemoteStreamServerMessageSerializer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

/**
 * A logical stream failed, or the socket carrying it did.
 *
 * [carrier] separates the two, and the difference decides whether retrying is meaningful. A
 * carrier failure says nothing about the request — the socket went away underneath it — so
 * reopening on a fresh generation is reasonable. A business failure is the host's considered
 * answer and will be identical next time.
 */
class RemoteStreamException(
    val error: RpcError,
    val carrier: Boolean,
) : Exception(error.message)

/** Fallback recorded when the mux is closed deliberately rather than by a failure. */
class MuxClosedException : Exception("remote stream mux closed")

/**
 * One open logical stream.
 *
 * Deliberately not a `Flow`: the connection handshake has to take the opening frame, inspect it,
 * and then keep reading the *same* stream, which a cold flow cannot express — collecting it twice
 * would open two event generations for one connection. [RemoteStreamMux.openStream] wraps this in
 * a flow for the ordinary consumers that just want items.
 *
 * Single-consumer. [receive] from one coroutine only.
 */
interface RemoteStream {
    /**
     * The next item, or null once the host ends the stream.
     *
     * @throws RemoteStreamException when the host fails the stream or its socket dies.
     */
    suspend fun receive(): JsonElement?

    /** Send one JSON value to the host. Throws when the uplink has ended or the carrier is closed. */
    fun send(value: JsonElement? = null)

    /** Half-close the client-to-host direction. Idempotent. */
    fun endUplink()

    /** Tell the host to stop. Idempotent, and safe to call on an already-dead stream. */
    fun cancel()
}

/**
 * The client half of the harness Gateway stream mux (`/api/remote.mux`).
 *
 * Harness 0.1.2 carries every server-initiated stream — host events, session follow, session
 * control, workspace follow — over this one socket as independently cancellable *logical*
 * streams. It replaces `/api/events.mux` and `/api/events.host`, which were downlink-only
 * sockets each carrying one fixed frame union.
 *
 * This class owns one physical socket and nothing more. It does not reconnect: [ConnectionLoop]
 * already owns generation lifetime and backoff for this client, and giving the mux a second
 * retry loop would mean two components disagreeing about when a generation ended. When the
 * socket dies every logical stream on it fails with a carrier error and the loop builds a new
 * mux.
 *
 * Items are delivered in arrival order per stream. No ordering is promised *between* streams —
 * the host interleaves them freely.
 */
class RemoteStreamMux(
    private val streamSignalBufferCapacity: Int = DEFAULT_STREAM_SIGNAL_BUFFER_CAPACITY,
    private val onConsumerFellBehind: (endpoint: String, capacity: Int) -> Unit = { _, _ -> },
    private val channelFactory: (sink: WsChannelSink) -> WsChannel,
) {
    init {
        require(streamSignalBufferCapacity > 0) { "streamSignalBufferCapacity must be positive" }
    }

    /** What can arrive on a logical stream. */
    private sealed class Signal {
        data class Item(val value: JsonElement) : Signal()
        data class Failed(val error: RpcError, val carrier: Boolean) : Signal()
        object Ended : Signal()
    }

    private inner class Stream(
        private val streamId: String,
        val endpoint: String,
    ) : RemoteStream {
        val signals: Channel<Signal> = Channel(streamSignalBufferCapacity)
        val carrierTerminated = CompletableDeferred<Unit>()
        private val done = AtomicBoolean(false)
        private val uplinkEnded = AtomicBoolean(false)
        @Volatile private var terminal: Signal? = null

        override suspend fun receive(): JsonElement? = when (val signal = signals.receiveCatching().getOrNull() ?: terminal ?: Signal.Ended) {
            is Signal.Item -> signal.value
            is Signal.Failed -> {
                done.set(true)
                throw RemoteStreamException(signal.error, signal.carrier)
            }
            Signal.Ended -> {
                done.set(true)
                null
            }
        }

        suspend fun awaitCarrierTermination() {
            carrierTerminated.await()
        }

        fun terminate(signal: Signal) {
            synchronized(lifecycleLock) {
                if (signal is Signal.Failed && signal.carrier) carrierTerminated.complete(Unit)
                done.set(true)
                uplinkEnded.set(true)
                terminal = signal
                // Closing preserves already queued ordered items, then makes receive() observe terminal
                // even when the bounded queue was full and could not accept one more signal.
                signals.close()
            }
        }

        override fun send(value: JsonElement?) {
            synchronized(lifecycleLock) {
                check(!done.get()) { "remote stream is terminal" }
                check(!uplinkEnded.get()) { "remote stream uplink has ended" }
                if (!send(RemoteStreamClientMessage.Item(streamId = streamId, value = value))) {
                    throw carrierFailure(closedCause)
                }
            }
        }

        override fun endUplink() {
            synchronized(lifecycleLock) {
                if (done.get() || !uplinkEnded.compareAndSet(false, true)) return
                if (!send(RemoteStreamClientMessage.End(streamId = streamId))) {
                    throw carrierFailure(closedCause)
                }
            }
        }

        override fun cancel() {
            if (!done.compareAndSet(false, true)) return
            // Wake any concurrent receiver before removing this stream: after removal failAll()
            // cannot reach its channel, so silently dropping it would strand receive() forever.
            terminate(Signal.Ended)
            // Best-effort: on a dead socket the send fails, which is exactly when the host has
            // already forgotten the stream.
            if (streams.remove(streamId) != null && closedCause == null) {
                send(RemoteStreamClientMessage.Cancel(streamId = streamId))
            }
        }
    }

    private val streams = ConcurrentHashMap<String, Stream>()
    /** Serializes stream registration/send with carrier teardown. */
    private val lifecycleLock = Any()
    private val nextStreamId = AtomicLong(1)
    private val started = AtomicBoolean(false)
    private val opened = CompletableDeferred<Unit>()
    private val closed = CompletableDeferred<Unit>()
    private val channelClosed = AtomicBoolean(false)
    private val inboundMessages = LongAdder()
    private val outboundMessages = LongAdder()
    private val carrierTerminal = CompletableDeferred<Throwable?>()
    data class Diagnostics(
        val inboundMessages: Long,
        val outboundMessages: Long,
        val activeStreams: Int,
    )

    val diagnostics: Diagnostics
        get() = Diagnostics(inboundMessages.sum(), outboundMessages.sum(), streams.size)

    @Volatile
    private var closedCause: Throwable? = null

    @Volatile
    private var channel: WsChannel? = null

    private val sink = object : WsChannelSink {
        override fun onOpen() {
            opened.complete(Unit)
        }

        override fun onMessage(text: String) {
            inboundMessages.increment()
            val message = try {
                WireJson.decodeFromString(RemoteStreamServerMessageSerializer, text)
            } catch (e: SerializationException) {
                // Carrier-level drift: this client cannot account for the socket's state any
                // more, so end the whole generation rather than dropping one message and
                // continuing against a mux it no longer understands.
                failAll(e)
                return
            } catch (e: IllegalArgumentException) {
                failAll(e)
                return
            }
            // An item for a stream nobody is reading is ordinary: a cancel and an in-flight item
            // cross on the wire every time a screen closes.
            val stream = streams[message.streamId] ?: return
            when (message) {
                is RemoteStreamServerMessage.Item -> {
                    // A slow logical stream is isolated to this subscriber: overflowing its
                    // bounded queue terminates that stream rather than an otherwise healthy shared
                    // WebSocket carrier. Ordered stateful items are never silently dropped.
                    if (stream.signals.trySend(Signal.Item(message.value ?: JsonObject(emptyMap()))).isFailure) {
                        runCatching { onConsumerFellBehind(stream.endpoint, streamSignalBufferCapacity) }
                        // The local stream is abandoned, but the shared carrier stays alive. Tell
                        // the host to release any attachment/controller owned by this subscriber.
                        streams.remove(message.streamId)
                        if (closedCause == null) send(RemoteStreamClientMessage.Cancel(streamId = message.streamId))
                        stream.terminate(
                            Signal.Failed(
                                RpcError("consumer_fell_behind", "remote stream consumer fell behind"),
                                carrier = false,
                            ),
                        )
                    }
                }
                is RemoteStreamServerMessage.Error -> {
                    streams.remove(message.streamId)
                    stream.terminate(Signal.Failed(message.error, carrier = false))
                }
                is RemoteStreamServerMessage.End -> {
                    streams.remove(message.streamId)
                    stream.terminate(Signal.Ended)
                }
            }
        }

        override fun onClosed(cause: Throwable?) {
            failAll(cause)
        }
    }

    /**
     * Whether the socket has been torn down, and why.
     *
     * Read to tell a clean end of the host's event stream from a carrier failure; the two produce
     * the same reconnect but a different explanation.
     */
    val failure: Throwable?
        get() = closedCause

    /** True after the physical WebSocket carrier has closed or failed. */
    val isClosed: Boolean
        get() = closed.isCompleted || closedCause != null

    /** Perform the handshake. Idempotent; [awaitOpen] waits for it to land. */
    fun start() {
        val created: WsChannel
        synchronized(lifecycleLock) {
            if (!started.compareAndSet(false, true) || closedCause != null || channelClosed.get()) return
            created = channelFactory(sink)
            // close() and start() share this transaction. Without it close could mark the channel
            // closed while it was still null, leaking a later-created WebSocket forever.
            channel = created
            try {
                // WsChannel.close() before start is otherwise a no-op because it has no OkHttp
                // WebSocket yet. Keep actual startup in this transaction so close cannot win the
                // publication/start gap and strand a later socket.
                created.start()
            } catch (error: Throwable) {
                failAll(error)
                if (channelClosed.compareAndSet(false, true)) created.close()
                throw error
            }
        }
    }

    /**
     * Suspend until the socket is open, or throw whatever closed it.
     *
     * A rejected upgrade — 401 for a missing browser session, 403 for the Host fence — arrives
     * here as the [RpcTransportException] the carrier wrapped it in, which is the only place its
     * status survives.
     */
    suspend fun awaitOpen() {
        opened.await()
        closedCause?.let { throw it }
    }

    /** Suspend until the physical WebSocket closes, returning the terminal carrier cause. */
    suspend fun awaitClosed(): Throwable? = carrierTerminal.await()

    /** Observe a carrier termination without consuming the logical stream's ordered items. */
    suspend fun awaitStreamCarrierTermination(stream: RemoteStream) {
        (stream as? Stream)?.awaitCarrierTermination() ?: closed.await()
    }

    /**
     * Open one logical stream and send its `open` immediately.
     *
     * @param endpoint the Remote endpoint, e.g. `session/follow` or the internal `$events`.
     * @param args the named argument object; keys must match the remote descriptor exactly.
     * @throws RemoteStreamException when the socket is already gone.
     */
    fun open(endpoint: String, args: JsonElement = JsonObject(emptyMap())): RemoteStream {
        val streamId = nextStreamId.getAndIncrement().toString()
        val stream = Stream(streamId, endpoint)
        // Registration and the first send are one lifecycle transaction. Without this, failAll()
        // can observe an empty map between the check and registration, leaving this stream blocked
        // forever after a concurrent close.
        synchronized(lifecycleLock) {
            closedCause?.let { throw carrierFailure(it) }
            streams[streamId] = stream
            val sent = send(
                RemoteStreamClientMessage.Open(
                    streamId = streamId,
                    endpoint = endpoint,
                    payload = JsonObject(mapOf("args" to args)),
                ),
            )
            if (!sent) {
                streams.remove(streamId)
                throw carrierFailure(closedCause)
            }
        }
        return stream
    }

    /**
     * Open one logical stream as a cold flow.
     *
     * The flow completes when the host ends the stream and throws [RemoteStreamException] when
     * the host fails it or the socket dies. Abandoning the collection cancels the stream upstream.
     */
    fun openStream(endpoint: String, args: JsonElement = JsonObject(emptyMap())): Flow<JsonElement> = flow {
        val stream = open(endpoint, args)
        try {
            while (true) {
                val item = stream.receive() ?: break
                emit(item)
            }
        } finally {
            stream.cancel()
        }
    }

    /**
     * Claim a live root agent's foreground wait. The host emits one remaining duration, then
     * holds the stream open until the wait settles; cancellation releases this Client's claim.
     */
    fun userQuestionsAttachWait(agentId: String, callId: String): Flow<UserQuestionWaitRemaining> =
        openStream("userQuestions/attachWait", JsonObject(mapOf(
            "agentId" to kotlinx.serialization.json.JsonPrimitive(agentId),
            "callId" to kotlinx.serialization.json.JsonPrimitive(callId),
        ))).map { decodeFromJsonElement(UserQuestionWaitRemaining.serializer(), it) }

    /** Tear the socket down and fail every open logical stream. Idempotent. */
    fun close() {
        failAll(null)
        if (channelClosed.compareAndSet(false, true)) {
            channel?.close()
            channel = null
        }
    }

    private fun send(message: RemoteStreamClientMessage): Boolean {
        val text = WireJson.encodeToString(RemoteStreamClientMessageSerializer, message)
        val sent = channel?.send(text) ?: false
        if (sent) outboundMessages.increment()
        return sent
    }

    /**
     * End every logical stream with one carrier failure.
     *
     * The cause is recorded before the fan-out so a stream opened concurrently sees the closure
     * rather than registering itself with nothing left to complete it.
     */
    private fun failAll(cause: Throwable?) {
        synchronized(lifecycleLock) {
            val closure = cause ?: MuxClosedException()
            if (closedCause == null) closedCause = closure
            carrierTerminal.complete(closure)
            closed.complete(Unit)
            // Unblocks awaitOpen for a socket that failed its upgrade and never opened at all.
            opened.complete(Unit)
            val error = carrierError(closure)
            streams.keys.toList().forEach { streamId ->
                streams.remove(streamId)?.terminate(Signal.Failed(error, carrier = true))
            }
        }
    }

    private companion object {
        /** Default bound per stream: absorbs ordinary bursts while keeping callback memory finite. */
        const val DEFAULT_STREAM_SIGNAL_BUFFER_CAPACITY = 512
    }

    private fun carrierFailure(cause: Throwable?): RemoteStreamException =
        RemoteStreamException(carrierError(cause ?: MuxClosedException()), carrier = true)

    private fun carrierError(cause: Throwable): RpcError {
        val status = (cause as? RpcTransportException)?.status ?: 0
        val details = when (cause) {
            is WebSocketClosedException -> kotlinx.serialization.json.buildJsonObject {
                put("transport", TransportFailures.classify(cause).name)
                put("websocketCloseCode", cause.code)
            }
            else -> TransportFailures.details(TransportFailures.classify(cause), status)
        }
        return RpcError(
            code = "internal",
            message = cause.message ?: "remote stream carrier failed",
            details = details,
        )
    }
}
