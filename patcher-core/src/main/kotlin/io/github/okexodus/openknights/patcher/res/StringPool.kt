package io.github.okexodus.openknights.patcher.res

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** An Android binary string pool (`ResStringPool`), used by binary XML and resource tables. */
class StringPool private constructor(
    strings: List<String>,
    val utf8: Boolean,
    private val sortedFlag: Boolean,
    /** Style span offsets (relative to the style data) and the raw style data; styles cover the first strings. */
    private val styleOffsets: IntArray,
    private val styleData: ByteArray,
    /** The exact bytes this pool was read from, reused while the pool is unchanged. */
    private var original: ByteArray?,
) {
    private val list = ArrayList(strings)
    private val index = HashMap<String, Int>().also { map -> list.forEachIndexed { i, s -> map.putIfAbsent(s, i) } }

    val size: Int get() = list.size
    val strings: List<String> get() = list
    val styleCount: Int get() = styleOffsets.size

    operator fun get(i: Int): String = list[i]

    fun indexOf(value: String): Int = index[value] ?: -1

    /** The index of [value], appending it when it is not in the pool yet. */
    fun intern(value: String): Int {
        index[value]?.let { return it }
        list += value
        index[value] = list.size - 1
        original = null
        return list.size - 1
    }

    fun encode(): ByteArray {
        original?.let { return it }
        val data = ByteArrayOutputStream()
        val offsets = IntArray(list.size)
        for ((i, s) in list.withIndex()) {
            offsets[i] = data.size()
            if (utf8) writeUtf8(data, s) else writeUtf16(data, s)
        }
        while (data.size() % 4 != 0) data.write(0)
        val headerSize = 28
        val stringsStart = headerSize + 4 * list.size + 4 * styleOffsets.size
        val stylesStart = if (styleOffsets.isEmpty()) 0 else stringsStart + data.size()
        val total = stringsStart + data.size() + styleData.size
        val buffer = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(TYPE.toShort()).putShort(headerSize.toShort()).putInt(total)
        buffer.putInt(list.size).putInt(styleOffsets.size)
        buffer.putInt((if (utf8) UTF8_FLAG else 0) or (if (sortedFlag) SORTED_FLAG else 0))
        buffer.putInt(if (list.isEmpty()) 0 else stringsStart).putInt(stylesStart)
        offsets.forEach { buffer.putInt(it) }
        styleOffsets.forEach { buffer.putInt(it) }
        buffer.put(data.toByteArray())
        buffer.put(styleData)
        return buffer.array()
    }

    companion object {
        const val TYPE = 0x0001
        private const val SORTED_FLAG = 0x1
        private const val UTF8_FLAG = 0x100

        fun create(strings: List<String>, utf8: Boolean): StringPool =
            StringPool(strings, utf8, false, IntArray(0), ByteArray(0), null)

        /** Reads the pool chunk that starts at [offset] of [data]. */
        fun read(data: ByteArray, offset: Int): StringPool {
            val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val type = b.getShort(offset).toInt() and 0xFFFF
            val headerSize = b.getShort(offset + 2).toInt() and 0xFFFF
            val size = b.getInt(offset + 4)
            if (type != TYPE || headerSize < 28 || size < headerSize || offset + size > data.size) {
                throw ResourceFormatException("damaged string pool at $offset")
            }
            val count = b.getInt(offset + 8)
            val styleCount = b.getInt(offset + 12)
            val flags = b.getInt(offset + 16)
            val stringsStart = b.getInt(offset + 20)
            val stylesStart = b.getInt(offset + 24)
            val utf8 = flags and UTF8_FLAG != 0
            if (count < 0 || styleCount < 0 || headerSize + 4L * (count + styleCount) > size) {
                throw ResourceFormatException("damaged string pool counts at $offset")
            }
            val strings = ArrayList<String>(count)
            val dataEnd = offset + (if (styleCount > 0) stylesStart else size)
            for (i in 0 until count) {
                val at = offset + stringsStart + b.getInt(offset + headerSize + 4 * i)
                if (at < offset || at >= dataEnd) throw ResourceFormatException("string $i is outside its pool")
                strings += if (utf8) readUtf8(data, at) else readUtf16(b, at)
            }
            val styleOffsets = IntArray(styleCount) { b.getInt(offset + headerSize + 4 * count + 4 * it) }
            val styleData = if (styleCount > 0) data.copyOfRange(offset + stylesStart, offset + size) else ByteArray(0)
            return StringPool(strings, utf8, flags and SORTED_FLAG != 0, styleOffsets, styleData, data.copyOfRange(offset, offset + size))
        }

        private fun readUtf8(data: ByteArray, start: Int): String {
            var at = start
            // UTF-16 length (skipped), then the UTF-8 byte length.
            at += if (data[at].toInt() and 0x80 != 0) 2 else 1
            var length = data[at].toInt() and 0xFF
            at++
            if (length and 0x80 != 0) {
                length = ((length and 0x7F) shl 8) or (data[at].toInt() and 0xFF)
                at++
            }
            if (at + length > data.size) throw ResourceFormatException("a UTF-8 string runs past its pool")
            return String(data, at, length, Charsets.UTF_8)
        }

        private fun readUtf16(b: ByteBuffer, start: Int): String {
            var at = start
            var length = b.getShort(at).toInt() and 0xFFFF
            at += 2
            if (length and 0x8000 != 0) {
                length = ((length and 0x7FFF) shl 16) or (b.getShort(at).toInt() and 0xFFFF)
                at += 2
            }
            val chars = CharArray(length) { b.getChar(at + 2 * it) }
            return String(chars)
        }

        private fun writeUtf8(out: ByteArrayOutputStream, s: String) {
            val bytes = s.toByteArray(Charsets.UTF_8)
            writeLength8(out, s.length)
            writeLength8(out, bytes.size)
            out.write(bytes)
            out.write(0)
        }

        private fun writeLength8(out: ByteArrayOutputStream, length: Int) {
            require(length <= 0x7FFF) { "string too long for a UTF-8 pool" }
            if (length > 0x7F) out.write(0x80 or (length shr 8))
            out.write(length and 0xFF)
        }

        private fun writeUtf16(out: ByteArrayOutputStream, s: String) {
            val length = s.length
            if (length > 0x7FFF) {
                val high = 0x8000 or (length ushr 16)
                out.write(high and 0xFF); out.write(high ushr 8)
            }
            out.write(length and 0xFF); out.write((length ushr 8) and 0xFF)
            for (c in s) { out.write(c.code and 0xFF); out.write(c.code ushr 8) }
            out.write(0); out.write(0)
        }
    }

    /** True when rebuilding this pool from its strings gives back the exact bytes it was read from. */
    fun encodedMatches(): Boolean {
        val kept = original ?: return true
        original = null
        val rebuilt = try { encode() } finally { original = kept }
        return rebuilt.contentEquals(kept)
    }
}

class ResourceFormatException(message: String) : RuntimeException(message)
