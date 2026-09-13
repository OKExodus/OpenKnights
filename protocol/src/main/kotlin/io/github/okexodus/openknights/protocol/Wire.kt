package io.github.okexodus.openknights.protocol

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.asInt
import java.io.ByteArrayOutputStream
import java.math.BigInteger

/**
 * Little-endian payload reader with the reference's struct format letters: B/b u8/i8, H/h u16/i16, I/i u32/i32,
 * Q/q u64/i64. Reading past the end is a [ProtocolException] ("Payload underflow").
 */
class WireReader(val data: ByteArray) {
    var offset = 0

    val remaining: Int get() = data.size - offset

    fun take(count: Int): ByteArray {
        if (count < 0 || offset + count > data.size) throw ProtocolException("Payload underflow")
        val value = data.copyOfRange(offset, offset + count)
        offset += count
        return value
    }

    private fun raw(count: Int): Long {
        if (offset + count > data.size) throw ProtocolException("Payload underflow")
        var v = 0L
        for (i in count - 1 downTo 0) v = (v shl 8) or (data[offset + i].toLong() and 0xFF)
        offset += count
        return v
    }

    fun u8(): Int = raw(1).toInt()
    fun i8(): Int = raw(1).toByte().toInt()
    fun u16(): Int = raw(2).toInt()
    fun i16(): Int = raw(2).toShort().toInt()
    fun u32(): Long = raw(4)
    fun i32(): Int = raw(4).toInt()
    fun u64(): BigInteger { val v = raw(8); return if (v >= 0) BigInteger.valueOf(v) else BigInteger.valueOf(v).add(TWO_64) }
    fun i64(): Long = raw(8)

    /** One value of a struct format letter, as the reference's integer. */
    fun number(letter: Char): JInt = when (letter) {
        'B' -> JInt(u8()); 'b' -> JInt(i8())
        'H' -> JInt(u16()); 'h' -> JInt(i16())
        'I' -> JInt(u32()); 'i' -> JInt(i32())
        'Q' -> JInt(u64()); 'q' -> JInt(i64())
        else -> throw IllegalArgumentException("unknown format letter $letter")
    }

    /** `struct.unpack("<" + fmt, ...)` as a list. */
    fun values(format: String): JArr = JArr(format.mapTo(ArrayList<JValue>()) { number(it) })

    /** A NUL-terminated byte string (the NUL is consumed, not returned). */
    fun cstringBytes(): ByteArray {
        var end = offset
        while (end < data.size && data[end] != 0.toByte()) end++
        if (end >= data.size) throw ProtocolException("Unterminated string")
        val value = data.copyOfRange(offset, end)
        offset = end + 1
        return value
    }

    /** A NUL-terminated UTF-8 string (strict decoding). */
    fun cstring(): String = Utf8.decodeStrict(cstringBytes()) ?: throw ProtocolException("Invalid UTF-8 string")

    companion object {
        val TWO_64: BigInteger = BigInteger.ONE.shiftLeft(64)
    }
}

class WireWriter {
    private val out = ByteArrayOutputStream()

    fun bytes(): ByteArray = out.toByteArray()
    val size: Int get() = out.size()

    fun raw(bytes: ByteArray): WireWriter { out.write(bytes); return this }

    private fun le(value: Long, count: Int) { for (i in 0 until count) out.write(((value ushr (8 * i)) and 0xFF).toInt()) }

    /** `struct.pack("<" + letter, value)` with the reference's range checks. */
    fun number(letter: Char, value: BigInteger): WireWriter {
        val (bits, signed) = when (letter) {
            'B' -> 8 to false; 'b' -> 8 to true; 'H' -> 16 to false; 'h' -> 16 to true
            'I' -> 32 to false; 'i' -> 32 to true; 'Q' -> 64 to false; 'q' -> 64 to true
            else -> throw IllegalArgumentException("unknown format letter $letter")
        }
        val min = if (signed) BigInteger.ONE.shiftLeft(bits - 1).negate() else BigInteger.ZERO
        val max = if (signed) BigInteger.ONE.shiftLeft(bits - 1).subtract(BigInteger.ONE) else BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE)
        if (value < min || value > max) throw ProtocolException("'$letter' format requires $min <= number <= $max")
        le(value.toLong(), bits / 8)
        return this
    }

    fun number(letter: Char, value: Long): WireWriter = number(letter, BigInteger.valueOf(value))
    fun number(letter: Char, value: JValue): WireWriter = number(letter, value.asInt)

    fun u8(v: Int) = number('B', v.toLong())
    fun u16(v: Int) = number('H', v.toLong())
    fun u32(v: Long) = number('I', v)
    fun i32(v: Int) = number('i', v.toLong())

    /** `struct.pack("<" + fmt, *values)`. */
    fun values(format: String, values: List<JValue>): WireWriter {
        if (values.size != format.length) throw ProtocolException("pack expected ${format.length} items for packing (got ${values.size})")
        format.forEachIndexed { i, letter -> number(letter, values[i]) }
        return this
    }

    /** A byte string with its terminating NUL; an embedded NUL is refused. */
    fun cstring(raw: ByteArray, what: String = "string"): WireWriter {
        if (raw.contains(0.toByte())) throw ProtocolException("Embedded NUL in $what")
        out.write(raw)
        out.write(0)
        return this
    }
}

object Utf8 {
    /** Strict UTF-8 as the reference decodes it; null when the bytes are not valid UTF-8. */
    fun decodeStrict(bytes: ByteArray): String? {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return try { decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString() } catch (_: java.nio.charset.CharacterCodingException) { null }
    }
}

fun structSize(format: String): Int = format.sumOf {
    when (it) { 'B', 'b' -> 1; 'H', 'h' -> 2; 'I', 'i' -> 4; 'Q', 'q' -> 8; else -> throw IllegalArgumentException("unknown format letter $it") }.toInt()
}
