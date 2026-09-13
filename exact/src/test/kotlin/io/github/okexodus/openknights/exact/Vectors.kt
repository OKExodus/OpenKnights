package io.github.okexodus.openknights.exact

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail

/** Loads a vector file from the test resources (made-up inputs with the reference's exact outputs). */
object Vectors {
    fun load(name: String): JObj {
        val stream = Vectors::class.java.getResourceAsStream("/vectors/$name.json") ?: error("vector file $name.json is missing")
        val document = stream.use { Json.loads(it.readBytes()).asObj }
        assertEquals("openknights_vectors_v1", document.str("profile"))
        return document
    }
}

/** Collects mismatches so a test reports how many vectors matched and shows the first differences. */
class Tally(private val what: String) {
    var checked = 0
        private set
    private val failures = ArrayList<String>()

    fun check(expected: Any?, actual: Any?, label: () -> String) {
        checked++
        if (expected != actual) failures.add("${label()}: expected <$expected> but was <$actual>")
    }

    fun fail(label: String) {
        checked++
        failures.add(label)
    }

    fun ok() {
        checked++
    }

    fun assertAll() {
        println("$what: ${checked - failures.size}/$checked match")
        if (failures.isNotEmpty()) fail<Unit>("$what: ${failures.size} of $checked differ:\n  " + failures.take(15).joinToString("\n  "))
    }
}

fun bitsOf(hex: String): Double = java.lang.Double.longBitsToDouble(java.lang.Long.parseUnsignedLong(hex, 16))
fun hexOf(x: Double): String = "%016x".format(java.lang.Double.doubleToRawLongBits(x))
