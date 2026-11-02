package org.iz.navigation.offline

/** No input is embedded in exceptions. Existing caller-specific byte limits remain authoritative. */
internal class OfflineJsonDepthException(val reason: Reason) : IllegalArgumentException(
    if (reason == Reason.DEPTH_LIMIT) "Offline JSON nesting limit exceeded." else "Malformed offline JSON."
) {
    enum class Reason { DEPTH_LIMIT, MALFORMED_JSON }
}

/**
 * Iterative standard-JSON token/structure preflight, before org.json allocates a DOM.
 * Strict grammar prevents Android JSONTokener's comments, single quotes or unquoted-token
 * dialect from hiding nesting. No recursion, numeric conversion, input copy or parser replacement.
 */
internal object OfflineJsonDepth {
    const val MAX_CONTAINERS = 64

    fun requireSafe(text: String): String {
        val states = IntArray(MAX_CONTAINERS)
        var depth = 0
        var index = 0
        var rootComplete = false
        while (true) {
            while (index < text.length && text[index] in " \t\r\n") index++
            if (index == text.length) {
                if (depth != 0 || !rootComplete) malformed()
                return text
            }
            if (depth == 0 && rootComplete) malformed()
            if (depth > 0) {
                val top = depth - 1
                when (states[top]) {
                    OBJECT_FIRST_KEY -> {
                        if (text[index] == '}') { depth--; index++; continue }
                        if (text[index] != '"') malformed()
                        index = stringEnd(text, index)
                        states[top] = OBJECT_COLON
                        continue
                    }
                    OBJECT_KEY -> {
                        if (text[index] != '"') malformed()
                        index = stringEnd(text, index)
                        states[top] = OBJECT_COLON
                        continue
                    }
                    OBJECT_COLON -> {
                        if (text[index] != ':') malformed()
                        index++
                        states[top] = OBJECT_VALUE
                        continue
                    }
                    OBJECT_END -> {
                        when (text[index]) {
                            ',' -> { index++; states[top] = OBJECT_KEY }
                            '}' -> { index++; depth-- }
                            else -> malformed()
                        }
                        continue
                    }
                    ARRAY_END -> {
                        when (text[index]) {
                            ',' -> { index++; states[top] = ARRAY_VALUE }
                            ']' -> { index++; depth-- }
                            else -> malformed()
                        }
                        continue
                    }
                    ARRAY_FIRST_VALUE -> {
                        if (text[index] == ']') { index++; depth--; continue }
                        states[top] = ARRAY_END
                    }
                    ARRAY_VALUE -> states[top] = ARRAY_END
                    OBJECT_VALUE -> states[top] = OBJECT_END
                    else -> malformed()
                }
            } else rootComplete = true

            when (text[index]) {
                '{', '[' -> {
                    if (depth == MAX_CONTAINERS)
                        throw OfflineJsonDepthException(OfflineJsonDepthException.Reason.DEPTH_LIMIT)
                    states[depth++] = if (text[index] == '{') OBJECT_FIRST_KEY else ARRAY_FIRST_VALUE
                    index++
                }
                '"' -> index = stringEnd(text, index)
                't' -> index = literalEnd(text, index, "true")
                'f' -> index = literalEnd(text, index, "false")
                'n' -> index = literalEnd(text, index, "null")
                '-', in '0'..'9' -> index = numberEnd(text, index)
                else -> malformed()
            }
        }
    }

    private fun stringEnd(text: String, start: Int): Int {
        var index = start + 1
        while (index < text.length) {
            val char = text[index++]
            when {
                char == '"' -> return index
                char == '\\' -> {
                    if (index == text.length) malformed()
                    when (text[index++]) {
                        '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                        'u' -> {
                            if (text.length - index < 4) malformed()
                            repeat(4) {
                                if (text[index++] !in "0123456789abcdefABCDEF") malformed()
                            }
                        }
                        else -> malformed()
                    }
                }
                char.code < 0x20 -> malformed()
            }
        }
        malformed()
    }
    private fun literalEnd(text: String, start: Int, literal: String): Int {
        if (!text.regionMatches(start, literal, 0, literal.length)) malformed()
        return start + literal.length
    }
    private fun numberEnd(text: String, start: Int): Int {
        var index = start
        if (text[index] == '-') index++
        if (index == text.length) malformed()
        if (text[index] == '0') index++ else {
            if (text[index] !in '1'..'9') malformed()
            while (index < text.length && text[index] in '0'..'9') index++
        }
        if (index < text.length && text[index] == '.') {
            index++
            if (index == text.length || text[index] !in '0'..'9') malformed()
            while (index < text.length && text[index] in '0'..'9') index++
        }
        if (index < text.length && text[index] in "eE") {
            index++
            if (index < text.length && text[index] in "+-") index++
            if (index == text.length || text[index] !in '0'..'9') malformed()
            while (index < text.length && text[index] in '0'..'9') index++
        }
        return index
    }
    private fun malformed(): Nothing = throw OfflineJsonDepthException(OfflineJsonDepthException.Reason.MALFORMED_JSON)
    private const val OBJECT_FIRST_KEY = 0
    private const val OBJECT_KEY = 1
    private const val OBJECT_COLON = 2
    private const val OBJECT_VALUE = 3
    private const val OBJECT_END = 4
    private const val ARRAY_FIRST_VALUE = 5
    private const val ARRAY_VALUE = 6
    private const val ARRAY_END = 7
}
