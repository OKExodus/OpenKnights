package io.github.okexodus.openknights.exact

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigInteger

class JsonVectorsTest {
    private val doc = Vectors.load("json")

    @Test
    fun `dumps matches the reference for every option set`() {
        val t = Tally("json.dumps")
        for ((i, v) in doc.arr("vectors").withIndex()) {
            val case = v.asObj
            val value = Json.loads(case.str("input"))
            t.check(case.str("canonical"), Json.canonical(value)) { "#$i canonical" }
            t.check(case.str("compact"), Json.compact(value)) { "#$i compact" }
            t.check(case.str("default"), Json.dumps(value)) { "#$i default" }
            t.check(case.str("default_sorted"), Json.dumps(value, sortKeys = true)) { "#$i default sorted" }
            t.check(case.str("indent1_sorted"), Json.dumps(value, sortKeys = true, indent = 1)) { "#$i indent 1" }
            t.check(case.str("indent2"), Json.dumps(value, indent = 2)) { "#$i indent 2" }
            case.strOrNull("unicode_canonical")?.let {
                t.check(it, Json.dumps(value, sortKeys = true, itemSeparator = ",", keySeparator = ":", ensureAscii = false)) { "#$i unicode" }
            }
        }
        t.assertAll()
    }

    @Test
    fun `objects with integer keys sort by number`() {
        val t = Tally("json int keys")
        for (v in doc.arr("int_key_objects")) {
            val case = v.asObj
            val obj = JObj(intKeys = true)
            case.arr("keys").zip(case.arr("values")).forEach { (k, value) -> obj[k.asInt.toString()] = value }
            t.check(case.str("canonical"), Json.canonical(obj)) { "canonical ${case.arr("keys")}" }
            t.check(case.str("compact"), Json.compact(obj)) { "compact ${case.arr("keys")}" }
        }
        t.assertAll()
    }

    @Test
    fun `loads matches the reference`() {
        val t = Tally("json.loads")
        for (v in doc.arr("loads")) {
            val case = v.asObj
            t.check(case.str("compact"), Json.dumps(Json.loads(case.str("text")), itemSeparator = ",", keySeparator = ":")) { case.str("text") }
        }
        t.assertAll()
    }

    @Test
    fun `canonical refuses NaN and keeps big integers`() {
        assertThrows(IllegalArgumentException::class.java) { Json.canonical(JFloat(Double.NaN)) }
        assertEquals("[18446744073709551615]", Json.canonical(jarr(BigInteger("18446744073709551615"))))
        assertEquals("{\"a\":1,\"b\":2}", Json.canonical(jobj("b" to 2, "a" to 1)))
    }

    @Test
    fun `float repr matches the reference`() {
        val t = Tally("repr(float)")
        for (v in Vectors.load("float-repr").arr("vectors")) {
            val pair = v.asArr
            val x = bitsOf(pair[0].asStr)
            t.check(pair[1].asStr, PyFloat.repr(x)) { pair[0].asStr }
        }
        t.assertAll()
    }
}
