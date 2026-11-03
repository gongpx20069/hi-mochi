package com.example.mochi_pet.platform.voice

internal fun speechText(text: String): String = buildString {
    var cursor = 0
    while (cursor < text.length) {
        if (text[cursor] == '\\') {
            val end = minOf(cursor + 2, text.length)
            append(text, cursor, end)
            cursor = end
            continue
        }
        if (text[cursor] == '`') {
            val end = codeSpanEnd(text, cursor)
            append(text, cursor, end)
            cursor = end
            continue
        }
        val image = text.startsWith("![", cursor)
        val start = if (image) cursor + 1 else cursor
        if (text[start] == '[') {
            val labelEnd = closingBracket(text, start)
            val end = if (labelEnd != null && text.getOrNull(labelEnd + 1) == '(') {
                linkDestinationEnd(text, labelEnd + 2)
            } else null
            if (labelEnd != null && end != null) {
                if (image) append(text, cursor, end)
                else append(LABEL_ESCAPE.replace(text.substring(start + 1, labelEnd), "$1"))
                cursor = end
                continue
            }
        }
        append(text[cursor++])
    }
}

private fun closingBracket(text: String, start: Int): Int? {
    var depth = 1
    var cursor = start + 1
    while (cursor < text.length) {
        when (text[cursor]) {
            '\\' -> cursor++
            '[' -> depth++
            ']' -> if (--depth == 0) return cursor
        }
        cursor++
    }
    return null
}

private fun linkDestinationEnd(text: String, start: Int): Int? {
    var cursor = start
    while (text.getOrNull(cursor)?.isWhitespace() == true) cursor++
    if (text.getOrNull(cursor) == '<') {
        cursor++
        while (cursor < text.length && text[cursor] != '>') {
            when (text[cursor]) {
                '\n', '\r', '<' -> return null
                '\\' -> cursor++
            }
            cursor++
        }
        if (text.getOrNull(cursor) != '>') return null
        cursor++
    } else {
        var depth = 0
        while (cursor < text.length) {
            val character = text[cursor]
            if (character.isWhitespace() || (character == ')' && depth == 0)) break
            when (character) {
                '\\' -> cursor++
                '(' -> depth++
                ')' -> depth--
                '<', '>' -> return null
            }
            cursor++
        }
        if (depth != 0) return null
    }
    val destinationEnd = cursor
    while (text.getOrNull(cursor)?.isWhitespace() == true) cursor++
    if (cursor > destinationEnd && text.getOrNull(cursor) in listOf('"', '\'', '(')) {
        val closing = if (text[cursor] == '(') ')' else text[cursor]
        cursor++
        while (cursor < text.length && text[cursor] != closing) {
            if (text[cursor] == '\\') cursor++
            cursor++
        }
        if (text.getOrNull(cursor) != closing) return null
        cursor++
        while (text.getOrNull(cursor)?.isWhitespace() == true) cursor++
    }
    return if (text.getOrNull(cursor) == ')') cursor + 1 else null
}

private fun codeSpanEnd(text: String, start: Int): Int {
    var openingEnd = start
    while (text.getOrNull(openingEnd) == '`') openingEnd++
    val marker = text.substring(start, openingEnd)
    var closing = text.indexOf(marker, openingEnd)
    while (closing >= 0) {
        val end = closing + marker.length
        if (text.getOrNull(closing - 1) != '`' && text.getOrNull(end) != '`') return end
        closing = text.indexOf(marker, end)
    }
    return openingEnd
}

private val LABEL_ESCAPE = Regex("""\\([\\\[\]])""")
