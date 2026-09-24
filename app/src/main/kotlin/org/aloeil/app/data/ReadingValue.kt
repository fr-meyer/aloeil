package org.aloeil.app.data

sealed interface ReadingValueResult {
    data class Valid(val canonical: String) : ReadingValueResult
    data class Invalid(val reason: Reason) : ReadingValueResult
}

enum class Reason { EMPTY, NUMBER, DECIMAL }

/** Syntax only: no clinical minimum, maximum, warning band, or rounding. */
object ReadingValue {
    fun parse(input: String): ReadingValueResult {
        val source = input.trim()
        if (source.isEmpty()) return ReadingValueResult.Invalid(Reason.EMPTY)
        val out = StringBuilder()
        var index = 0
        var digits = 0
        var separators = 0
        while (index < source.length) {
            val codePoint = source.codePointAt(index)
            val digit = Character.digit(codePoint, 10)
            when {
                digit >= 0 -> {
                    out.append(('0'.code + digit).toChar())
                    digits++
                }
                (codePoint == '.'.code || codePoint == ','.code) -> {
                    separators++
                    if (separators > 1) return ReadingValueResult.Invalid(Reason.DECIMAL)
                    out.append('.')
                }
                (codePoint == '-'.code || codePoint == '+'.code) && index == 0 ->
                    out.appendCodePoint(codePoint)
                else -> return ReadingValueResult.Invalid(Reason.NUMBER)
            }
            index += Character.charCount(codePoint)
        }
        if (digits == 0) return ReadingValueResult.Invalid(Reason.NUMBER)
        return ReadingValueResult.Valid(out.toString())
    }
}
