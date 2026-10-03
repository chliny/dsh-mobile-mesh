package dev.dsh.mobile.mesh.ui.screens.main

/** Split UTF-8 terminal data into bounded RPC payloads without breaking Unicode code points. */
internal fun terminalInputChunks(data: String, maxBytes: Int): List<String> {
    require(maxBytes > 0) { "maxBytes must be positive" }
    if (data.isEmpty()) return listOf(data)
    val chunks = mutableListOf<String>()
    var start = 0
    var bytes = 0
    var index = 0
    while (index < data.length) {
        val codePoint = data.codePointAt(index)
        val width = Character.charCount(codePoint)
        val codePointBytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
        require(codePointBytes <= maxBytes) { "maxBytes is smaller than one UTF-8 code point" }
        if (bytes + codePointBytes > maxBytes) {
            chunks += data.substring(start, index)
            start = index
            bytes = 0
        }
        bytes += codePointBytes
        index += width
    }
    chunks += data.substring(start)
    return chunks
}
