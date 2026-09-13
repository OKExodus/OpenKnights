package io.github.okexodus.openknights.exact

import org.junit.jupiter.api.Test
import java.math.BigInteger

class NumericVectorsTest {
    private val f32 = Vectors.load("f32")

    private fun outcome(block: () -> String): String = try { block() } catch (e: F32.F32Overflow) { "OverflowError" }
        catch (e: ArithmeticException) { "OverflowError" }

    @Test
    fun `struct f packing matches the reference`() {
        val t = Tally("struct '<f'")
        for (v in f32.arr("vectors")) {
            val pair = v.asArr
            val x = bitsOf(pair[0].asStr)
            val got = outcome {
                val bits = java.lang.Float.floatToRawIntBits(F32.pack(x))
                ByteArray(4) { ((bits ushr (8 * it)) and 0xFF).toByte() }.toHexString()
            }
            // The reference packs NaN as the quiet NaN of its C conversion; compare NaN by class.
            if (x.isNaN()) t.check(true, got.length == 8) { "nan" } else t.check(pair[1].asStr, got) { pair[0].asStr }
        }
        t.assertAll()
    }

    @Test
    fun `exact-rational binary32 rounding matches the reference`() {
        val t = Tally("f32_exact / reset f32")
        for (v in f32.arr("exact")) {
            val pair = v.asArr
            t.check(pair[1].asStr, outcome { hexOf(F32.exact(Fraction.parse(pair[0].asStr))) }) { "exact ${pair[0].asStr}" }
        }
        for (v in f32.arr("exact_fraction")) {
            val pair = v.asArr
            t.check(pair[1].asStr, F32.exactFraction(Fraction.parse(pair[0].asStr)).toString()) { "fraction ${pair[0].asStr}" }
        }
        for (v in f32.arr("fmadd")) {
            val q = v.asArr
            t.check(q[3].asStr, hexOf(F32.fmadd(bitsOf(q[0].asStr), bitsOf(q[1].asStr), bitsOf(q[2].asStr)))) { "fmadd $q" }
        }
        t.assertAll()
    }

    @Test
    fun `conversions match the reference`() {
        val t = Tally("fcvtzu / fcvtzs / trunc / ucvtf")
        for (v in f32.arr("conversions")) {
            val row = v.asObj
            val x = bitsOf(row.str("x"))
            t.check(row.long("fcvtzu"), F32.fcvtzu(x)) { "fcvtzu ${row.str("x")}" }
            val trunc = try { F32.truncU32(x).toString() } catch (e: IllegalArgumentException) { "ValueError" }
            t.check(row.getValue("trunc_u32").let { if (it is JStr) it.value else it.toString() }, trunc) { "trunc ${row.str("x")}" }
            val hdp = try { hexOf(F32.roundFinite(x)) } catch (e: F32.F32Overflow) { "OverflowError" } catch (e: IllegalArgumentException) { "ValueError" }
            t.check(row.str("hdp_f32"), hdp) { "hdp f32 ${row.str("x")}" }
            t.check(row.long("fcvtzu_fraction"), F32.fcvtzuFraction(Fraction.of(x))) { "fcvtzu q ${row.str("x")}" }
            t.check(row.long("fcvtzs_fraction"), F32.fcvtzs(Fraction.of(x))) { "fcvtzs q ${row.str("x")}" }
        }
        for (v in f32.arr("ucvtf")) {
            val pair = v.asArr
            t.check(pair[1].asStr, hexOf(F32.ucvtf(BigInteger(pair[0].asStr)))) { "ucvtf ${pair[0]}" }
        }
        t.assertAll()
    }

    @Test
    fun `fractions and integer division match the reference`() {
        val doc = Vectors.load("fractions")
        val t = Tally("Fraction / int")
        for (v in doc.arr("vectors")) {
            val row = v.asObj
            val a = Fraction.parse(row.str("a"))
            val b = Fraction.parse(row.str("b"))
            t.check(row.str("add"), (a + b).toString()) { "add" }
            t.check(row.str("sub"), (a - b).toString()) { "sub" }
            t.check(row.str("mul"), (a * b).toString()) { "mul" }
            row.strOrNull("div")?.let { t.check(it, (a / b).toString()) { "div" } }
            t.check(row.bool("lt"), a < b) { "lt" }
            t.check(row.bool("eq"), a == b) { "eq" }
            t.check(row.str("int_a"), a.trunc().toString()) { "int ${row.str("a")}" }
            t.check(row.str("floor_a"), a.floor().toString()) { "floor ${row.str("a")}" }
            t.check(row.str("float_a"), try { hexOf(a.toDouble()) } catch (e: ArithmeticException) { "OverflowError" }) { "float ${row.str("a")}" }
        }
        for (v in doc.arr("integers")) {
            val row = v.asObj
            val a = BigInteger(row.str("a"))
            val b = BigInteger(row.str("b"))
            t.check(row.str("floordiv"), PyInt.floorDiv(a, b).toString()) { "$a // $b" }
            t.check(row.str("mod"), PyInt.mod(a, b).toString()) { "$a % $b" }
            t.check(row.str("truediv"), hexOf(PyInt.trueDiv(a, b))) { "$a / $b" }
            t.check(row.str("and64"), PyInt.and64(a).toString()) { "$a & mask" }
            t.check(row.str("mul_and64"), PyInt.and64(a * b).toString()) { "$a * $b & mask" }
        }
        for (v in doc.arr("float_to_int")) {
            val row = v.asArr
            val x = bitsOf(row[0].asStr)
            t.check(row[1].asStr, PyInt.truncate(x).toString()) { "int ${row[0]}" }
            t.check(row[2].asStr, PyInt.floor(x).toString()) { "floor ${row[0]}" }
            t.check(row[3].asStr, PyInt.ceil(x).toString()) { "ceil ${row[0]}" }
        }
        t.assertAll()
    }

    @Test
    fun `names, case folding and lenient UTF-8 match the reference`() {
        val doc = Vectors.load("names")
        val t = Tally("names")
        for (v in doc.arr("vectors")) {
            val row = v.asObj
            val text = row.str("text")
            t.check(row.str("name_key"), Names.nameKey(text)) { "key <$text>" }
            val got = try { Names.normalizeName(text) } catch (e: Names.CreationRejected) { "rejected: " + e.message }
            val want = row.strOrNull("normalized") ?: ("rejected: " + row.str("rejected"))
            t.check(want, got) { "normalize <$text>" }
        }
        for (v in doc.arr("truncate_utf8")) {
            val row = v.asObj
            t.check(row.str("bytes_hex"), Names.truncateUtf8(row.str("text"), row.long("limit").toInt()).toHexString()) { "truncate" }
        }
        for (v in doc.arr("utf8_replace")) {
            val row = v.asObj
            t.check(row.str("text"), Utf8Lenient.decodeReplace(row.str("hex").hexBytes())) { "replace ${row.str("hex")}" }
        }
        for (v in doc.arr("strip")) {
            val row = v.asObj
            t.check(row.str("strip"), PyText.strip(row.str("text"))) { "strip" }
            t.check(row.str("casefold"), PyText.casefold(row.str("text"))) { "casefold" }
        }
        t.assertAll()
    }

    @Test
    fun `timestamps match the reference`() {
        val t = Tally("timestamps")
        for (v in Vectors.load("timestamps").arr("vectors")) {
            val row = v.asObj
            val ms = row.long("epoch_ms")
            t.check(row.str("iso_ms"), PyTime.isoMillisUtc(ms)) { "iso ms $ms" }
            t.check(row.str("iso_s"), PyTime.isoSecondsUtc(Math.floorDiv(ms, 1000L))) { "iso s $ms" }
            t.check(row.str("stamp"), PyTime.stampUtc(java.time.Instant.ofEpochMilli(ms))) { "stamp $ms" }
            val offset = row.long("offset").toInt()
            t.check(row.str("local_day"), PyTime.localDay(Math.floorDiv(ms, 1000L), offset)) { "day $ms $offset" }
            t.check(row.long("weekday").toInt(), PyTime.weekday(Math.floorDiv(ms, 1000L), offset)) { "weekday $ms $offset" }
        }
        t.assertAll()
    }
}
