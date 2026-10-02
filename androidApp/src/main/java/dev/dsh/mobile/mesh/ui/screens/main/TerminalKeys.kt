package dev.dsh.mobile.mesh.ui.screens.main

/** VT input for on-screen control keys; physical keyboards go directly through xterm.js. */
internal fun terminalKey(key: String, ctrl: Boolean = false, shift: Boolean = false, alt: Boolean = false): String {
    val modifier = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)
    val sequence = when (key) {
    "Esc" -> "\u001b"
    "Tab" -> if (shift && !ctrl && !alt) "\u001b[Z" else if (modifier > 1) "\u001b[1;${modifier}I" else "\t"
    "Enter" -> "\r"
    "Backspace" -> "\u007f"
    "Delete", "Insert", "PageUp", "PageDown" -> {
        val code = when (key) { "Delete" -> 3; "Insert" -> 2; "PageUp" -> 5; else -> 6 }
        if (modifier == 1) "\u001b[${code}~" else "\u001b[${code};${modifier}~"
    }
    "Home", "End" -> {
        val letter = if (key == "Home") "H" else "F"
        if (modifier == 1) "\u001b[$letter" else "\u001b[1;${modifier}$letter"
    }
    "Up", "Down", "Right", "Left" -> {
        val letter = when (key) { "Up" -> "A"; "Down" -> "B"; "Right" -> "C"; else -> "D" }
        if (modifier > 1) "\u001b[1;${modifier}$letter" else "\u001b[$letter"
    }
    else -> {
        val f = key.removePrefix("F").toIntOrNull()
        if (key.startsWith("F") && f != null && f in 1..12) {
            val code = when (f) { 1 -> "P"; 2 -> "Q"; 3 -> "R"; 4 -> "S"; else -> "" }
            if (f <= 4) {
                if (modifier == 1) "\u001bO$code" else "\u001b[1;${modifier}$code"
            } else {
                val number = mapOf(5 to 15, 6 to 17, 7 to 18, 8 to 19, 9 to 20, 10 to 21, 11 to 23, 12 to 24).getValue(f)
                if (modifier == 1) "\u001b[${number}~" else "\u001b[${number};${modifier}~"
            }
        } else if (key.length == 1 && ctrl) {
            val ch = key[0].uppercaseChar()
            when (ch) {
                in 'A'..'Z' -> (ch.code - 'A'.code + 1).toChar().toString()
                '@', ' ' -> "\u0000"
                '[' -> "\u001b"
                '\\' -> "\u001c"
                ']' -> "\u001d"
                '^' -> "\u001e"
                '_' -> "\u001f"
                '?' -> "\u007f"
                else -> key
            }
        } else if (key.length == 1 && key[0].isLetter() && shift) key.uppercase()
        else if (key.length == 1 && key[0] in 'A'..'Z') key.lowercase()
        else key
    }
    }
    return if (alt && key.length == 1) "\u001b$sequence" else sequence
}
