package dev.dsh.mobile.mesh.ui.screens.main

import dev.dsh.mobile.mesh.core.wire.RemoteStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** One modern terminal attachment: one command at a time until the Host acknowledges it. */
internal class TerminalAttachmentInput(private val stream: RemoteStream) {
    private val mutex = Mutex()
    private var sequence = 0
    private var pending: Pair<Int, CompletableDeferred<String?>>? = null
    private var writable = false
    private var closed = false
    private var maxFrameBytes: Int? = null
    private var frameOverheadBytes = 0

    @Synchronized
    fun snapshot(writable: Boolean, inputWindow: JsonObject?) {
        this.writable = writable
        maxFrameBytes = inputWindow?.get("maxBytes")?.toString()?.toIntOrNull()
        frameOverheadBytes = inputWindow?.get("frameOverheadBytes")?.toString()?.toIntOrNull() ?: 0
        if (!writable) reject()
    }

    @Synchronized
    fun state(writable: Boolean) {
        this.writable = writable
        if (!writable) reject()
        else pending?.second?.complete(null)
    }

    @Synchronized
    fun acknowledge(sequence: Int, refusal: String?) {
        val current = pending ?: return
        if (current.first != sequence) return
        if (refusal != null) writable = false
        current.second.complete(refusal)
    }

    suspend fun write(data: String) = command("write") { put("data", JsonPrimitive(data)) }

    suspend fun resize(cols: Int, rows: Int) = command("resize") {
        put("cols", JsonPrimitive(cols))
        put("rows", JsonPrimitive(rows))
    }

    private suspend fun command(type: String, fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
        mutex.withLock {
            check(!closed && writable) { "Terminal input is unavailable" }
            val next = sequence + 1
            val payload = buildJsonObject {
                put("type", JsonPrimitive(type))
                put("sequence", JsonPrimitive(next))
                fields()
            }
            val bound = maxFrameBytes
            check(bound == null || payload.toString().toByteArray(Charsets.UTF_8).size + frameOverheadBytes <= bound) {
                "Terminal input exceeds the transport window"
            }
            val ack = CompletableDeferred<String?>()
            synchronized(this@TerminalAttachmentInput) {
                check(!closed && writable) { "Terminal input is unavailable" }
                pending = next to ack
                sequence = next
            }
            try {
                stream.send(payload)
                val refusal = ack.await()
                check(refusal == null) { "Terminal input refused: $refusal" }
            } finally {
                synchronized(this@TerminalAttachmentInput) {
                    if (pending?.second === ack) pending = null
                }
            }
        }
    }

    @Synchronized
    fun close() {
        closed = true
        reject()
        stream.cancel()
    }

    @Synchronized
    private fun reject() {
        pending?.second?.complete("read-only")
    }
}
