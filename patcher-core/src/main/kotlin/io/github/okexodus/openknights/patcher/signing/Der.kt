package io.github.okexodus.openknights.patcher.signing

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** A minimal DER encoder, enough to write a self-signed X.509 certificate. */
internal object Der {
    fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = content.size
        when {
            n < 0x80 -> out.write(n)
            n < 0x100 -> { out.write(0x81); out.write(n) }
            n < 0x10000 -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xFF) }
            else -> { out.write(0x83); out.write(n shr 16); out.write((n shr 8) and 0xFF); out.write(n and 0xFF) }
        }
        out.write(content)
        return out.toByteArray()
    }

    fun sequence(vararg parts: ByteArray): ByteArray = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })
    fun set(vararg parts: ByteArray): ByteArray = tlv(0x31, parts.fold(ByteArray(0)) { a, b -> a + b })
    fun integer(value: BigInteger): ByteArray = tlv(0x02, value.toByteArray())
    fun explicit(number: Int, content: ByteArray): ByteArray = tlv(0xA0 or number, content)
    fun nullValue(): ByteArray = byteArrayOf(0x05, 0x00)
    fun utf8(text: String): ByteArray = tlv(0x0C, text.toByteArray(Charsets.UTF_8))
    fun bitString(content: ByteArray): ByteArray = tlv(0x03, byteArrayOf(0) + content)

    fun oid(dotted: String): ByteArray {
        val parts = dotted.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((parts[0] * 40 + parts[1]).toInt())
        for (part in parts.drop(2)) {
            val bytes = ArrayList<Int>()
            var v = part
            bytes += (v and 0x7F).toInt()
            v = v shr 7
            while (v > 0) { bytes += ((v and 0x7F) or 0x80).toInt(); v = v shr 7 }
            bytes.asReversed().forEach { out.write(it) }
        }
        return tlv(0x06, out.toByteArray())
    }

    /** UTCTime before 2050, GeneralizedTime from 2050 on (RFC 5280). */
    fun time(at: ZonedDateTime): ByteArray {
        val utc = at.withZoneSameInstant(ZoneOffset.UTC)
        return if (utc.year < 2050) tlv(0x17, utc.format(DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'")).toByteArray(Charsets.US_ASCII))
        else tlv(0x18, utc.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'")).toByteArray(Charsets.US_ASCII))
    }
}
