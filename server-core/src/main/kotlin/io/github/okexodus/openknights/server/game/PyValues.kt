package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.PyText
import java.math.BigInteger

/**
 * Python's text-to-number rules as the reference uses them on table cells (every cell is raw text):
 * `str.strip()`, `str.isdigit()` and `int(text)` (base 10: surrounding whitespace, one sign, Unicode decimal digits,
 * single underscores between digits; anything else is a ValueError).
 */
object PyValues {
    /** Raised where the reference raises ValueError on a malformed number. */
    class ValueError(message: String) : IllegalArgumentException(message)

    fun strip(text: String): String = PyText.strip(text)

    /** `str.isdigit()`: non-empty and every code point a digit (Unicode `Nd` and the other digit characters). */
    fun isDigit(text: String): Boolean = text.isNotEmpty() && text.codePoints().allMatch { PyText.isDigit(it) }

    /** `int(text)` for a base-10 text. */
    fun parseInt(text: String): BigInteger {
        val s = strip(text)
        var i = 0
        var negative = false
        if (s.isNotEmpty() && (s[0] == '+' || s[0] == '-')) { negative = s[0] == '-'; i = 1 }
        val digits = StringBuilder()
        var lastUnderscore = true           // no leading underscore
        var cps = s.substring(i).codePoints().toArray()
        if (cps.isEmpty()) throw ValueError("invalid literal for int() with base 10: '$text'")
        for (cp in cps) {
            if (cp == '_'.code) {
                if (lastUnderscore) throw ValueError("invalid literal for int() with base 10: '$text'")
                lastUnderscore = true
                continue
            }
            val d = Character.digit(cp, 10)
            if (d < 0 || !PyText.isDecimal(cp)) throw ValueError("invalid literal for int() with base 10: '$text'")
            digits.append(('0' + d))
            lastUnderscore = false
        }
        if (lastUnderscore) throw ValueError("invalid literal for int() with base 10: '$text'")
        val value = BigInteger(digits.toString())
        return if (negative) value.negate() else value
    }

    fun parseLong(text: String): Long = parseInt(text).longValueExact()

    /** `int(value)` if `value.lstrip("-").isdigit()` else default (the daily inputs' `_int`), after `(value or "").strip()`. */
    fun digitInt(value: String?, default: Long = 0): Long {
        val v = strip(value ?: "")
        return if (isDigit(v.trimStart('-'))) parseLong(v) else default
    }
}
