package dev.dsh.mobile.mesh.core.wire

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.BufferedSink

/**
 * One HTTP carrier exchange: the status code, raw body and binary parts of an authenticated HTTP
 * request.
 *
 * HTTP status is carrier-only — business errors arrive as HTTP 200 with `ok: false` in the body.
 *
 * A Remote result that contains native bytes (`Uint8Array`) is not JSON at all: the gateway answers
 * `multipart/form-data` whose `metadata` part is the ordinary server-response envelope with each
 * byte field replaced by `null`, and whose other parts carry the raw bytes. Harness 0.2.0 moved
 * binary results to that encoding, so [body] keeps the JSON envelope for every other response and
 * [parts] carries the raw bytes keyed by their multipart name. Keeping the carrier-level split
 * here lets the wire client stay a JSON codec: it injects the bytes into their envelope paths.
 */
data class RpcHttpResponse(
    val status: Int,
    val body: String,
    val parts: Map<String, ByteArray> = emptyMap(),
)

/** Thrown by [RpcTransport] on a non-2xx carrier response or a network-level failure. */
class RpcTransportException(
    val status: Int,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** The HTTP carrier for unary RPCs: one `POST /api/<path>` exchange. */
interface RpcTransport {
    /**
     * POST [body] to [path] (e.g. "/api/session.list"). Returns the carrier response, or throws
     * [RpcTransportException] on a non-2xx status or transport failure.
     */
    suspend fun post(path: String, body: String): RpcHttpResponse

    /**
     * GET a response from [path] (JSON routes and binary downloads share this carrier).
     * one non-envelope read channel.
     *
     * [consume] runs on an IO thread with the response still open and must not retain the stream —
     * the connection is released as soon as it returns. Throws [RpcTransportException] on a non-2xx
     * status or transport failure.
     */
    suspend fun <T> download(
        path: String,
        consume: (contentType: String?, contentDisposition: String?, body: InputStream) -> T,
    ): T


    /**
     * POST raw bytes to [path] — the harness's one non-envelope *write* channel, the streaming
     * file-upload route harness 0.1.3 added (`/api/session/uploadFileBinary`).
     *
     * [body] is read once on an IO thread and must be positioned at the first byte to send;
     * [contentLength] is declared up front so the host can refuse an oversized upload before
     * reading it. [onProgress] is called with the running byte count as the body is written.
     * Returns the carrier response (the route answers 200 with a bare `{ok, value|error}`
     * result), or throws [RpcTransportException] on a non-2xx status or transport failure.
     */
    suspend fun upload(
        path: String,
        contentType: String,
        contentLength: Long,
        body: InputStream,
        onProgress: ((sent: Long) -> Unit)? = null,
    ): RpcHttpResponse
}

/** JSON media type used for every /api POST. */
private val JSON_MEDIA_TYPE: MediaType = "application/json; charset=utf-8".toMediaType()
private val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())
private val HEADER_END = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())

/** The `metadata` JSON envelope and raw binary fields of one multipart Remote response. */
internal data class MultipartRpcResponse(
    val metadata: String,
    val parts: Map<String, ByteArray>,
)

/**
 * Split one `multipart/form-data` Remote answer into its `metadata` envelope and binary parts.
 *
 * The gateway writes that form for results carrying native bytes: a `metadata` part holding the
 * server-response envelope with each byte field `null`, and one part per binary field whose name
 * the envelope records. Parts may appear in any order and the boundary can appear inside the
 * bytes, so the scan only treats `--boundary` at a line start as a delimiter.
 *
 * @param contentType the response `Content-Type`, whose `boundary` parameter is required
 * @param body the complete response body
 * @returns the envelope and parts, or null when this is not a well-formed multipart answer
 */
internal fun parseMultipartRpcResponse(contentType: String?, body: ByteArray): MultipartRpcResponse? {
    val boundary = contentType
        ?.split(';')
        ?.map(String::trim)
        ?.firstOrNull { it.startsWith("boundary=", ignoreCase = true) }
        ?.substringAfter('=')
        ?.trim('"')
        ?.takeIf(String::isNotBlank)
        ?: return null
    val delimiter = "--$boundary".toByteArray(Charsets.UTF_8)
    var position = indexOfDelimiter(body, delimiter, 0)
    var metadata: String? = null
    val parts = LinkedHashMap<String, ByteArray>()
    while (position >= 0) {
        var cursor = position + delimiter.size
        if (cursor + 2 <= body.size && body[cursor] == '-'.code.toByte() && body[cursor + 1] == '-'.code.toByte()) {
            break
        }
        if (cursor + 2 <= body.size && body[cursor] == CRLF[0] && body[cursor + 1] == CRLF[1]) cursor += 2
        val headerEnd = indexOf(body, HEADER_END, cursor) ?: return null
        val headers = String(body, cursor, headerEnd - cursor, Charsets.UTF_8)
        var contentEnd = indexOfDelimiter(body, delimiter, headerEnd + HEADER_END.size)
        if (contentEnd < 0) return null
        // The CRLF that precedes a delimiter belongs to the delimiter, not to the part content.
        if (contentEnd >= 2 && body[contentEnd - 1] == CRLF[1] && body[contentEnd - 2] == CRLF[0]) contentEnd -= 2
        val content = body.copyOfRange(headerEnd + HEADER_END.size, contentEnd)
        val name = headers.lineSequence()
            .firstOrNull { it.startsWith("Content-Disposition:", ignoreCase = true) }
            ?.split(';')
            ?.map(String::trim)
            ?.firstOrNull { it.startsWith("name=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim('"')
        if (name == "metadata") {
            metadata = String(content, Charsets.UTF_8)
        } else if (name != null) {
            parts[name] = content
        }
        position = contentEnd + 2
        if (position > body.size) return null
        position = indexOfDelimiter(body, delimiter, position)
    }
    val envelope = metadata ?: return null
    return MultipartRpcResponse(envelope, parts)
}

/**
 * First index of a multipart delimiter at or after [from], or -1.
 *
 * A delimiter only counts at the start of a line: a binary part may contain the same bytes, and a
 * free-text search there would truncate the data and corrupt the decode.
 */
private fun indexOfDelimiter(haystack: ByteArray, delimiter: ByteArray, from: Int): Int {
    var index = indexOf(haystack, delimiter, from)
    while (index >= 0) {
        val atLineStart = index == 0 ||
            (index >= 2 && haystack[index - 2] == CRLF[0] && haystack[index - 1] == CRLF[1])
        if (atLineStart) return index
        index = indexOf(haystack, delimiter, index + 1)
    }
    return -1
}

/** First index of [needle] at or after [from], or -1. */
private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
    if (needle.isEmpty()) return from
    var index = from
    while (index <= haystack.size - needle.size) {
        var matched = true
        for (offset in needle.indices) {
            if (haystack[index + offset] != needle[offset]) {
                matched = false
                break
            }
        }
        if (matched) return index
        index++
    }
    return -1
}

/**
 * OkHttp-backed [RpcTransport]. Sends `Content-Type: application/json`, sets the `Host` header
 * from the base URL, and times out at [connectTimeoutMs]/[readTimeoutMs] (30s by default). Non-2xx
 * responses throw [RpcTransportException] (403 mentions the harness trust fence).
 *
 * [baseUrl] may be `http://` or `https://`; the `Host` header omits the port when it is the
 * scheme's default.
 *
 * The timeouts are constructor parameters rather than something a caller pre-applies to [client]:
 * this class rebuilds the client it is handed, so a builder-applied deadline was silently replaced
 * by the 30s default. That is why a discovery probe advertising a 700ms budget could block for
 * thirty seconds.
 */
class OkHttpRpcTransport(
    baseUrl: String,
    client: OkHttpClient = defaultClient(),
    connectTimeoutMs: Long = 30_000,
    readTimeoutMs: Long = 30_000,
    writeTimeoutMs: Long = 30_000,
    private val cookie: String? = null,
    hostHeader: String? = null,
) : RpcTransport {

    private val base: HttpUrl = baseUrl.toHttpUrl()
    private val hostHeader: String = hostHeader ?: run {
        val defaultPort = when (base.scheme) {
            "http" -> 80
            "https" -> 443
            else -> -1
        }
        if (base.port == defaultPort) base.host else "${base.host}:${base.port}"
    }
    private val httpClient: OkHttpClient = client.newBuilder()
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(writeTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    override suspend fun post(path: String, body: String): RpcHttpResponse =
        suspendCancellableCoroutine { continuation ->
            val target = base.resolve(path)
                ?: throw RpcTransportException(0, "cannot resolve $path against $base")
            val request = Request.Builder()
                .url(target)
                .header("Host", hostHeader)
                .header("Content-Type", "application/json")
                .cookied(cookie)
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            RpcTransportException(0, "transport failure: ${e.message}", e),
                        )
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        if (!resp.isSuccessful) {
                            continuation.resumeWithException(
                                RpcTransportException(resp.code, carrierMessage(resp.code)),
                            )
                            return
                        }
                        val contentType = resp.header("Content-Type")
                        val multipart = contentType
                            ?.substringBefore(';')
                            ?.trim()
                            ?.equals("multipart/form-data", ignoreCase = true) == true
                        if (multipart) {
                            val parsed = parseMultipartRpcResponse(contentType, resp.body?.bytes() ?: ByteArray(0))
                            if (parsed == null) {
                                continuation.resumeWithException(
                                    RpcTransportException(resp.code, "malformed multipart Remote response"),
                                )
                            } else {
                                continuation.resume(RpcHttpResponse(resp.code, parsed.metadata, parsed.parts))
                            }
                        } else {
                            continuation.resume(RpcHttpResponse(resp.code, resp.body?.string().orEmpty()))
                        }
                    }
                }
            })
        }

    /**
     * Downloads and uploads reuse the unary client's connection pool but relax the timeouts: a
     * session log ZIP is streamed and compressed on the fly, and a file upload is bounded by the
     * link rather than the host, so 30s is a false deadline on either.
     */
    private val downloadClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(10, TimeUnit.MINUTES)
            .build()
    }

    override suspend fun <T> download(
        path: String,
        consume: (contentType: String?, contentDisposition: String?, body: InputStream) -> T,
    ): T = withContext(Dispatchers.IO) {
        val target = base.resolve(path)
            ?: throw RpcTransportException(0, "cannot resolve $path against $base")
        val request = Request.Builder()
            .url(target)
            .header("Host", hostHeader)
            .cookied(cookie)
            .get()
            .build()
        val response = try {
            downloadClient.newCall(request).execute()
        } catch (e: IOException) {
            throw RpcTransportException(0, "transport failure: ${e.message}", e)
        }
        response.use { resp ->
            if (!resp.isSuccessful) throw RpcTransportException(resp.code, carrierMessage(resp.code))
            val body = resp.body
                ?: throw RpcTransportException(resp.code, "download carried no body")
            consume(
                resp.header("Content-Type"),
                resp.header("Content-Disposition"),
                body.byteStream(),
            )
        }
    }

    override suspend fun upload(
        path: String,
        contentType: String,
        contentLength: Long,
        body: InputStream,
        onProgress: ((sent: Long) -> Unit)?,
    ): RpcHttpResponse = withContext(Dispatchers.IO) {
        val target = base.resolve(path)
            ?: throw RpcTransportException(0, "cannot resolve $path against $base")
        val requestBody = object : RequestBody() {
            override fun contentType(): MediaType? = contentType.toMediaType()
            override fun contentLength(): Long = contentLength
            override fun isOneShot(): Boolean = true
            override fun writeTo(sink: BufferedSink) {
                val buffer = ByteArray(64 * 1024)
                var sent = 0L
                while (true) {
                    val read = body.read(buffer)
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                    sent += read
                    onProgress?.invoke(sent)
                }
            }
        }
        val request = Request.Builder()
            .url(target)
            .header("Host", hostHeader)
            .cookied(cookie)
            .post(requestBody)
            .build()
        val response = try {
            downloadClient.newCall(request).execute()
        } catch (e: IOException) {
            throw RpcTransportException(0, "transport failure: ${e.message}", e)
        }
        response.use { resp ->
            val responseBody = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RpcTransportException(resp.code, carrierMessage(resp.code))
            RpcHttpResponse(resp.code, responseBody)
        }
    }

    private companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * Set `Cookie` when there is a harness browser session, and leave the request untouched when
 * there is not.
 *
 * Harness 0.1.2 authenticates the complete `/api` surface — every Remote call, the mux upgrade,
 * and the session-log download — against one signed cookie, and answers 401 without it. The
 * cookie is obtained once per authority by exchanging the launch token at `GET /?token=…`; see
 * `HarnessSession`. It is deliberately not an `Authorization` header, because the harness refuses
 * to read the token from one.
 */
internal fun Request.Builder.cookied(cookie: String?): Request.Builder =
    if (cookie == null) this else header("Cookie", cookie)

/**
 * Carrier-layer failure text; 403 names the trust fence because that is the usual cause.
 *
 * File-level so the WebSocket path can wrap a failed upgrade in the same shape as a failed POST —
 * a fence rejection of `/api/events.mux` is the same fact as one on `/api/host.describe`.
 */
internal fun carrierMessage(status: Int): String = when (status) {
    // Since 0.1.2 these are two different facts and the connect screen has to say which. 403 is
    // the Host/Origin fence, which runs first and is about *where* the request came from. 401 is
    // the browser session, which is about *who* is asking — a harness that would answer happily
    // if this client had exchanged a launch token. Collapsing them sends people to reconfigure a
    // firewall when they actually need to re-pair.
    401 -> "harness has no browser session for this client (HTTP 401)"
    403 -> "harness trust fence rejected the request (HTTP 403)"
    else -> "carrier returned HTTP $status"
}

/** Receives WebSocket carrier events; all callbacks may run on OkHttp's socket threads. */
interface WsChannelSink {
    /**
     * One complete text message, still unparsed.
     *
     * The carrier deliberately does not decode: harness 0.1.2 multiplexes every logical stream
     * over this one socket, so which parser applies is a function of the message, not the socket.
     * Parsing here would put the mux's dispatch table in the transport.
     */
    fun onMessage(text: String)

    /** The WebSocket handshake completed and the socket is ready. */
    fun onOpen()

    /**
     * The socket closed or failed. `cause` is non-null on a failure, null on a clean close.
     * Every logical stream riding this socket ends with it.
     */
    fun onClosed(cause: Throwable?)
}

/**
 * A bidirectional WebSocket to `/api/remote.mux`.
 *
 * Its predecessor was downlink-only: `/api/events.mux` and `/api/events.host` closed the socket
 * with 1008 on any client message. The 0.1.2 mux is the opposite — the client must send `open`
 * and `cancel` to get anything at all — so [send] is the substantive difference here, not an
 * extra.
 *
 * The host sends RFC 6455 Ping at `websocketHeartbeatIntervalMs` and OkHttp answers Pong at the
 * protocol layer, so idle liveness needs no application frame.
 *
 * The browser-session cookie is required on the upgrade too, so a missing session costs the entire
 * connection generation rather than one call.
 */
class WebSocketClosedException(val code: Int, reason: String) : IOException(
    "WebSocket closed code=$code reason=${reason.take(MAX_CLOSE_REASON_LENGTH)}",
)

private const val MAX_CLOSE_REASON_LENGTH = 120

open class WsChannel(
    private val url: String,
    private val client: OkHttpClient,
    private val sink: WsChannelSink,
    private val cookie: String? = null,
    private val hostHeader: String? = null,
) {
    @Volatile
    private var webSocket: WebSocket? = null

    private val lifecycleLock = Any()
    @Volatile
    private var started: Boolean = false
    @Volatile
    private var closed: Boolean = false

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            sink.onOpen()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            sink.onMessage(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // Complete a peer-initiated close handshake so onClosed is delivered promptly and
            // the connection loop can replace the retired generation without waiting for timeout.
            // An empty Close frame is reported as 1005, which cannot itself be sent on the wire.
            // Do not echo the peer's reason: it may not be valid UTF-8 or may exceed the control
            // frame limit once re-encoded, and OkHttp rejects a close whose reason cannot be sent.
            webSocket.close(if (code == 1005) 1000 else code, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            // Preserve the peer's close frame in diagnostics. A clean WebSocket close is still a
            // mux carrier loss: without code/reason the reconnect loop can only report a generic
            // "remote stream mux closed", which hides server restarts and policy closes.
            sink.onClosed(WebSocketClosedException(code, reason))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // A rejected upgrade carries its status only on the response; OkHttp reports it as a
            // bare ProtocolException ("Expected HTTP 101 … was '403'"), which no caller can
            // classify without reading English. Re-wrap so a trust-fence rejection of the stream
            // reads the same as one on a POST — and so 401 (no browser session) stays tellable
            // apart from 403 (Host/Origin refused), which now mean different things.
            sink.onClosed(
                if (response != null) {
                    RpcTransportException(response.code, carrierMessage(response.code), t)
                } else {
                    t
                },
            )
        }
    }

    /** Perform the RFC 6455 handshake and begin reading messages. Idempotent. */
    open fun start() = synchronized(lifecycleLock) {
        if (started || closed) return@synchronized
        started = true
        val request = Request.Builder()
            .url(url)
            .cookied(cookie)
            .apply { hostHeader?.let { header("Host", it) } }
            .build()
        val created = client.newWebSocket(request, listener)
        // close() shares this lock, so it cannot cancel a null socket immediately before this
        // assignment and leave a newly-created carrier running after its mux was torn down.
        if (closed) created.cancel() else webSocket = created
    }

    /**
     * Queue one text message. Returns false when the socket is gone or its send buffer is full —
     * the caller must treat that as the stream having failed rather than retrying, because
     * OkHttp has already begun tearing the socket down by then.
     */
    open fun send(text: String): Boolean = webSocket?.send(text) ?: false

    /** Tear the socket down. Idempotent. */
    open fun close() {
        val toClose = synchronized(lifecycleLock) {
            closed = true
            webSocket.also { webSocket = null }
        }
        toClose?.cancel()
    }
}
