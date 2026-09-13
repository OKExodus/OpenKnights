package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.Json
import java.math.BigInteger

/**
 * Small helpers that keep ported code close to the reference's Python: documents are [JObj] trees (insertion order
 * kept, like dictionaries), copies go through JSON like the reference's `json.loads(json.dumps(x))`, and frames are
 * `(opcode, payload)` pairs.
 */
typealias Frame = Pair<Int, ByteArray>

/** `copy.deepcopy` / `json.loads(json.dumps(x))` of a document tree. */
@Suppress("UNCHECKED_CAST")
fun <T : JValue> T.deepCopy(): T = Json.loads(Json.dumps(this, allowNan = true)) as T

/** `d.get(key)` as a Long (null when absent or not an integer). */
fun JObj.longOrNull(key: String): Long? = (this[key] as? JInt)?.value?.toLong()

/** `d.get(key, default)` as a Long. */
fun JObj.long(key: String, default: Long): Long = longOrNull(key) ?: default

/** `d.get(key)` as an object (null when absent or not an object). */
fun JObj.objOrNull(key: String): JObj? = this[key] as? JObj

/** `d.get(key)` as a list (null when absent or not a list). */
fun JObj.arrOrNull(key: String): JArr? = this[key] as? JArr

val JValue.long: Long get() = (this as JInt).value.longValueExact()
val JValue.big: BigInteger get() = (this as JInt).value

/** The `bits` of a typed value (`{"tag", "bits"}`). */
val JObj.bits: Long get() = (this["bits"] as JInt).value.toLong()

/**
 * A planner's result (the reference's plan dictionary): its recorded members in insertion order ([data]; they become
 * the history detail) and the reply frames ([packets], never stored).
 */
class Plan(val data: JObj = JObj(), var packets: List<Frame> = emptyList()) {
    operator fun get(key: String): JValue? = data[key]
    operator fun set(key: String, value: Any?) { data[key] = io.github.okexodus.openknights.exact.jvalue(value) }
}

/** Python semantics the ported code needs beyond the value helpers. */
object Py {
    /** Python truthiness of a JSON value: None, False, 0, 0.0, "", [] and {} are false. */
    fun truthy(v: JValue?): Boolean = when (v) {
        null, io.github.okexodus.openknights.exact.JNull -> false
        is io.github.okexodus.openknights.exact.JBool -> v.value
        is JInt -> v.value.signum() != 0
        is io.github.okexodus.openknights.exact.JFloat -> v.value != 0.0
        is io.github.okexodus.openknights.exact.JStr -> v.value.isNotEmpty()
        is JArr -> v.isNotEmpty()
        is JObj -> v.isNotEmpty()
        else -> true
    }
}
