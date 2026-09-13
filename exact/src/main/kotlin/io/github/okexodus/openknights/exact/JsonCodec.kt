package io.github.okexodus.openknights.exact

import java.math.BigInteger
import java.security.MessageDigest

/**
 * The reference's JSON writer and reader, byte for byte.
 *
 * [dumps] follows `json.dumps`: keyword `sortKeys` (code-point order, or numeric order for [JObj.intKeys]),
 * `separators` (the reference default is `", "` / `": "`, and `","` / `": "` once `indent` is set), `ensureAscii`
 * (everything outside space..tilde as lowercase `\uXXXX`, surrogate pairs above U+FFFF), `indent` and `allowNan`.
 * [loads] follows `json.loads` in its default strict mode: insertion order, a repeated key keeps its first position
 * and its last value, integers of any size, NaN / Infinity accepted.
 */
object Json {
    private val FORM_FEED = 12.toChar()
    private val BOM = 0xFEFF.toChar()

    /** `json.dumps(v, ensure_ascii=True, sort_keys=True, separators=(",", ":"), allow_nan=False)`. */
    fun canonical(value: JValue): String = dumps(value, sortKeys = true, itemSeparator = ",", keySeparator = ":", allowNan = false)

    /** `json.dumps(v, ensure_ascii=True, separators=(",", ":"), allow_nan=False)`: insertion order. */
    fun compact(value: JValue): String = dumps(value, itemSeparator = ",", keySeparator = ":", allowNan = false)

    /** SHA-256 (lowercase hex) of [canonical]. */
    fun checksum(value: JValue): String = sha256Hex(canonical(value).toByteArray(Charsets.UTF_8))

    fun dumps(
        value: JValue,
        sortKeys: Boolean = false,
        itemSeparator: String? = null,
        keySeparator: String = ": ",
        ensureAscii: Boolean = true,
        indent: Int? = null,
        allowNan: Boolean = true,
    ): String {
        val item = itemSeparator ?: if (indent == null) ", " else ","
        val out = StringBuilder()
        Writer(out, sortKeys, item, keySeparator, ensureAscii, indent, allowNan).write(value, 0)
        return out.toString()
    }

    private class Writer(
        val out: StringBuilder,
        val sortKeys: Boolean,
        val itemSeparator: String,
        val keySeparator: String,
        val ensureAscii: Boolean,
        val indent: Int?,
        val allowNan: Boolean,
    ) {
        fun newline(level: Int) {
            if (indent != null) out.append('\n').append(" ".repeat(indent * level))
        }

        fun write(value: JValue, level: Int) {
            when (value) {
                JNull -> out.append("null")
                is JBool -> out.append(if (value.value) "true" else "false")
                is JInt -> out.append(value.value.toString())
                is JFloat -> out.append(floatText(value.value))
                is JStr -> string(value.value)
                is JArr -> {
                    if (value.isEmpty()) { out.append("[]"); return }
                    out.append('[')
                    newline(level + 1)
                    value.forEachIndexed { i, item ->
                        if (i > 0) { out.append(itemSeparator); newline(level + 1) }
                        write(item, level + 1)
                    }
                    newline(level)
                    out.append(']')
                }
                is JObj -> {
                    if (value.isEmpty()) { out.append("{}"); return }
                    out.append('{')
                    newline(level + 1)
                    val keys = if (!sortKeys) value.keys.toList()
                    else if (value.intKeys) value.keys.sortedBy { BigInteger(it) }
                    else value.keys.sortedWith(CodePointOrder)
                    keys.forEachIndexed { i, key ->
                        if (i > 0) { out.append(itemSeparator); newline(level + 1) }
                        string(key)
                        out.append(keySeparator)
                        write(value.getValue(key), level + 1)
                    }
                    newline(level)
                    out.append('}')
                }
            }
        }

        fun floatText(d: Double): String {
            if (d.isNaN() || d.isInfinite()) {
                require(allowNan) { "Out of range float values are not JSON compliant" }
                return if (d.isNaN()) "NaN" else if (d > 0) "Infinity" else "-Infinity"
            }
            return PyFloat.repr(d)
        }

        fun string(s: String) {
            out.append('"')
            for (c in s) {
                when (c) {
                    '"' -> out.append("\\\"")
                    '\\' -> out.append("\\\\")
                    '\n' -> out.append("\\n")
                    '\r' -> out.append("\\r")
                    '\t' -> out.append("\\t")
                    '\b' -> out.append("\\b")
                    FORM_FEED -> out.append("\\f")
                    else -> if (c < ' ' || (ensureAscii && c > '~')) {
                        // UTF-16 units: a code point above U+FFFF is already a surrogate pair here.
                        out.append("\\u").append(Integer.toHexString(c.code).padStart(4, '0'))
                    } else out.append(c)
                }
            }
            out.append('"')
        }
    }

    /** String order of the reference: by code point (Java's compareTo orders UTF-16 units). */
    val CodePointOrder: Comparator<String> = Comparator { a, b ->
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a.codePointAt(i)
            val cb = b.codePointAt(j)
            if (ca != cb) return@Comparator ca.compareTo(cb)
            i += Character.charCount(ca)
            j += Character.charCount(cb)
        }
        (a.length - i).compareTo(b.length - j)
    }

    fun loads(text: String): JValue {
        val reader = Reader(text)
        reader.skipWhitespace()
        val value = reader.value()
        reader.skipWhitespace()
        if (reader.pos != text.length) throw JsonError("Extra data", reader.pos)
        return value
    }

    fun loads(bytes: ByteArray): JValue = loads(bytes.toString(Charsets.UTF_8).let { if (it.startsWith(BOM)) throw JsonError("Unexpected UTF-8 BOM", 0) else it })

    class JsonError(message: String, val position: Int) : IllegalArgumentException("$message at $position")

    private class Reader(val s: String) {
        var pos = 0

        fun skipWhitespace() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\t' || s[pos] == '\n' || s[pos] == '\r')) pos++
        }

        fun value(): JValue {
            if (pos >= s.length) throw JsonError("Expecting value", pos)
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> JStr(string())
                'n' -> literal("null", JNull)
                't' -> literal("true", JBool(true))
                'f' -> literal("false", JBool(false))
                'N' -> literal("NaN", JFloat(Double.NaN))
                'I' -> literal("Infinity", JFloat(Double.POSITIVE_INFINITY))
                else -> if (c == '-' && s.startsWith("-Infinity", pos)) literal("-Infinity", JFloat(Double.NEGATIVE_INFINITY))
                else number()
            }
        }

        fun literal(word: String, value: JValue): JValue {
            if (!s.startsWith(word, pos)) throw JsonError("Expecting value", pos)
            pos += word.length
            return value
        }

        fun number(): JValue {
            val start = pos
            if (pos < s.length && s[pos] == '-') pos++
            if (pos >= s.length || !s[pos].isAsciiDigit()) throw JsonError("Expecting value", start)
            if (s[pos] == '0') pos++ else while (pos < s.length && s[pos].isAsciiDigit()) pos++
            var isFloat = false
            if (pos + 1 < s.length && s[pos] == '.' && s[pos + 1].isAsciiDigit()) {
                isFloat = true
                pos++
                while (pos < s.length && s[pos].isAsciiDigit()) pos++
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                var p = pos + 1
                if (p < s.length && (s[p] == '+' || s[p] == '-')) p++
                if (p < s.length && s[p].isAsciiDigit()) {
                    isFloat = true
                    while (p < s.length && s[p].isAsciiDigit()) p++
                    pos = p
                }
            }
            val text = s.substring(start, pos)
            return if (isFloat) JFloat(text.toDouble()) else JInt(BigInteger(text))
        }

        fun string(): String {
            pos++ // opening quote
            val out = StringBuilder()
            while (true) {
                if (pos >= s.length) throw JsonError("Unterminated string", pos)
                val c = s[pos++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        if (pos >= s.length) throw JsonError("Unterminated string", pos)
                        when (val e = s[pos++]) {
                            '"' -> out.append('"'); '\\' -> out.append('\\'); '/' -> out.append('/')
                            'b' -> out.append('\b'); 'f' -> out.append(FORM_FEED); 'n' -> out.append('\n')
                            'r' -> out.append('\r'); 't' -> out.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw JsonError("Invalid \\uXXXX escape", pos)
                                val unit = s.substring(pos, pos + 4).toIntOrNull(16)
                                    ?: throw JsonError("Invalid \\uXXXX escape", pos)
                                pos += 4
                                out.append(unit.toChar())
                            }
                            else -> throw JsonError("Invalid \\escape: $e", pos - 1)
                        }
                    }
                    c < ' ' -> throw JsonError("Invalid control character", pos - 1)
                    else -> out.append(c)
                }
            }
        }

        fun arr(): JArr {
            pos++
            val items = JArr()
            skipWhitespace()
            if (pos < s.length && s[pos] == ']') { pos++; return items }
            while (true) {
                skipWhitespace()
                items.add(value())
                skipWhitespace()
                if (pos >= s.length) throw JsonError("Expecting ',' delimiter", pos)
                when (s[pos++]) {
                    ',' -> continue
                    ']' -> return items
                    else -> throw JsonError("Expecting ',' delimiter", pos - 1)
                }
            }
        }

        fun obj(): JObj {
            pos++
            val map = LinkedHashMap<String, JValue>()
            skipWhitespace()
            if (pos < s.length && s[pos] == '}') { pos++; return JObj(map) }
            while (true) {
                skipWhitespace()
                if (pos >= s.length || s[pos] != '"') throw JsonError("Expecting property name enclosed in double quotes", pos)
                val key = string()
                skipWhitespace()
                if (pos >= s.length || s[pos] != ':') throw JsonError("Expecting ':' delimiter", pos)
                pos++
                skipWhitespace()
                map[key] = value()   // a repeated key keeps its first position, takes the last value
                skipWhitespace()
                if (pos >= s.length) throw JsonError("Expecting ',' delimiter", pos)
                when (s[pos++]) {
                    ',' -> continue
                    '}' -> return JObj(map)
                    else -> throw JsonError("Expecting ',' delimiter", pos - 1)
                }
            }
        }

        private fun Char.isAsciiDigit() = this in '0'..'9'
    }
}

fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()

fun ByteArray.toHexString(): String {
    val hex = "0123456789abcdef"
    val chars = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xFF
        chars[2 * i] = hex[v ushr 4]
        chars[2 * i + 1] = hex[v and 0xF]
    }
    return String(chars)
}

fun String.hexBytes(): ByteArray {
    require(length % 2 == 0) { "hex text must have an even length" }
    return ByteArray(length / 2) { ((Character.digit(this[2 * it], 16) shl 4) or Character.digit(this[2 * it + 1], 16)).toByte() }
        .also { require(all { c -> Character.digit(c, 16) >= 0 }) { "not hex text" } }
}
