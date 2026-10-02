package dev.dsh.mobile.mesh.ui.screens.main

/** Apply the next touchscreen modifier to one keyboard event, retaining IME Unicode text intact. */
internal fun terminalTextInput(data: String, ctrl: Boolean, shift: Boolean, alt: Boolean): String {
    if (data.isEmpty() || (!ctrl && !shift && !alt)) return data
    // Android IMEs may commit a complete word, CJK phrase, or emoji in one callback. Never split it.
    if (data.codePointCount(0, data.length) != 1) return data
    val ch = data[0]
    if (ch.code !in 32..126) return data // xterm already encoded the control or escape sequence.
    val value = when {
        ctrl -> terminalKey(data, ctrl = true, shift = shift)
        shift && ch in 'a'..'z' -> data.uppercase()
        else -> data
    }
    return if (alt) "\u001b$value" else value
}
