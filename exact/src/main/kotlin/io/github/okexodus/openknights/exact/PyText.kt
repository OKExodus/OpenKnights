package io.github.okexodus.openknights.exact

import java.text.Normalizer

/**
 * Text rules of the reference: its character classes (`isalnum`, `isspace`, `isdigit`, `isdecimal`) and full case
 * folding (`casefold`) come from its own Unicode data (shipped as `unicode-tables.json`, generated from it), so they
 * do not depend on the Unicode version of the running JVM. Lengths count code points, like the reference.
 */
object PyText {
    private class Ranges(val starts: IntArray, val ends: IntArray) {
        fun contains(cp: Int): Boolean {
            var lo = 0
            var hi = starts.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                when {
                    cp < starts[mid] -> hi = mid - 1
                    cp > ends[mid] -> lo = mid + 1
                    else -> return true
                }
            }
            return false
        }
    }

    private val tables: JObj by lazy {
        val stream = PyText::class.java.getResourceAsStream("/io/github/okexodus/openknights/exact/unicode-tables.json")
            ?: error("unicode-tables.json is missing")
        stream.use { Json.loads(it.readBytes()).asObj }
    }

    private fun ranges(name: String): Ranges {
        val list = tables.arr(name)
        return Ranges(IntArray(list.size) { list[it].asArr[0].asInt.toInt() }, IntArray(list.size) { list[it].asArr[1].asInt.toInt() })
    }

    private val alnum by lazy { ranges("isalnum") }
    private val space by lazy { ranges("isspace") }
    private val digit by lazy { ranges("isdigit") }
    private val decimal by lazy { ranges("isdecimal") }
    private val folding: Map<Int, IntArray> by lazy {
        tables.arr("casefold").associate { entry ->
            val pair = entry.asArr
            pair[0].asInt.toInt() to pair[1].asArr.map { it.asInt.toInt() }.toIntArray()
        }
    }

    val unicodeVersion: String get() = tables.str("unicode_version")

    fun isAlnum(cp: Int) = alnum.contains(cp)
    fun isSpace(cp: Int) = space.contains(cp)
    fun isDigit(cp: Int) = digit.contains(cp)
    fun isDecimal(cp: Int) = decimal.contains(cp)

    /** `str.isalnum()` of a whole string (false when empty). */
    fun isAlnum(s: String) = s.isNotEmpty() && s.codePoints().allMatch { isAlnum(it) }

    /** `len(s)`: code points. */
    fun length(s: String): Int = s.codePointCount(0, s.length)

    /** `s.casefold()`. */
    fun casefold(s: String): String {
        val out = StringBuilder(s.length)
        s.codePoints().forEach { cp ->
            val mapped = folding[cp]
            if (mapped == null) out.appendCodePoint(cp) else mapped.forEach { out.appendCodePoint(it) }
        }
        return out.toString()
    }

    /** `s.strip()` with the reference's whitespace. */
    fun strip(s: String): String {
        var start = 0
        var end = s.length
        while (start < end) { val cp = s.codePointAt(start); if (!isSpace(cp)) break; start += Character.charCount(cp) }
        while (end > start) { val cp = s.codePointBefore(end); if (!isSpace(cp)) break; end -= Character.charCount(cp) }
        return s.substring(start, end)
    }

    /** `unicodedata.normalize("NFC", s)`. */
    fun nfc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC)
}

/**
 * UTF-8 decoding with the reference's error handling: `decode("utf-8", "replace")` puts one U+FFFD per maximal invalid
 * subpart (an invalid lead byte, or a valid lead followed by what is valid of its sequence).
 */
object Utf8Lenient {
    private val REPLACEMENT = 0xFFFD.toChar()

    fun decodeReplace(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size)
        var i = 0
        val n = bytes.size
        fun b(k: Int) = bytes[k].toInt() and 0xFF
        while (i < n) {
            val c = b(i)
            if (c < 0x80) { out.append(c.toChar()); i++; continue }
            val (need, lo, hi) = when (c) {
                in 0xC2..0xDF -> Triple(1, 0x80, 0xBF)
                0xE0 -> Triple(2, 0xA0, 0xBF)
                in 0xE1..0xEC, 0xEE, 0xEF -> Triple(2, 0x80, 0xBF)
                0xED -> Triple(2, 0x80, 0x9F)
                0xF0 -> Triple(3, 0x90, 0xBF)
                in 0xF1..0xF3 -> Triple(3, 0x80, 0xBF)
                0xF4 -> Triple(3, 0x80, 0x8F)
                else -> Triple(0, 0, 0)
            }
            if (need == 0) { out.append(REPLACEMENT); i++; continue }
            var cp = c and (0x3F shr need)
            var j = i + 1
            var ok = true
            for (k in 0 until need) {
                if (j >= n) { ok = false; break }
                val cc = b(j)
                val min = if (k == 0) lo else 0x80
                val max = if (k == 0) hi else 0xBF
                if (cc < min || cc > max) { ok = false; break }
                cp = (cp shl 6) or (cc and 0x3F)
                j++
            }
            if (ok) { out.appendCodePoint(cp); i = j } else { out.append(REPLACEMENT); i = j }
        }
        return out.toString()
    }
}
