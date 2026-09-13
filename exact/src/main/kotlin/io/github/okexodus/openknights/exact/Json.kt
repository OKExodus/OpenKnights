package io.github.okexodus.openknights.exact

import java.math.BigInteger

/**
 * A JSON value with the semantics of the reference server's documents: objects keep insertion order (like its
 * dictionaries), integers are arbitrary-size, floats are binary64. Documents are compared and hashed through [Json],
 * which writes them byte for byte as the reference does.
 */
sealed interface JValue

data object JNull : JValue

@JvmInline
value class JBool(val value: Boolean) : JValue

/** An integer of any size (Gold is an unsigned 64-bit amount; some sums exceed it before they are checked). */
class JInt(val value: BigInteger) : JValue {
    constructor(value: Long) : this(BigInteger.valueOf(value))
    constructor(value: Int) : this(BigInteger.valueOf(value.toLong()))

    fun toLong(): Long = value.longValueExact()
    fun toInt(): Int = value.intValueExact()
    override fun equals(other: Any?): Boolean = other is JInt && other.value == value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = value.toString()
}

class JFloat(val value: Double) : JValue {
    /** Bitwise equality, so NaN payloads and the sign of zero are kept apart. */
    override fun equals(other: Any?): Boolean =
        other is JFloat && java.lang.Double.doubleToRawLongBits(other.value) == java.lang.Double.doubleToRawLongBits(value)
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = PyFloat.repr(value)
}

@JvmInline
value class JStr(val value: String) : JValue

class JArr(val items: MutableList<JValue> = ArrayList()) : JValue, MutableList<JValue> by items {
    override fun equals(other: Any?): Boolean = other is JArr && other.items == items
    override fun hashCode(): Int = items.hashCode()
    override fun toString(): String = Json.dumps(this)
}

/**
 * An object in insertion order. Keys are strings; the reference also writes objects built with integer keys, which
 * `sort_keys` orders by number: [intKeys] marks such an object so [Json] sorts it the same way.
 */
class JObj(val map: LinkedHashMap<String, JValue> = LinkedHashMap(), val intKeys: Boolean = false) :
    JValue, MutableMap<String, JValue> by map {
    override fun equals(other: Any?): Boolean = other is JObj && other.map == map
    override fun hashCode(): Int = map.hashCode()
    override fun toString(): String = Json.dumps(this)

    fun obj(key: String): JObj = map[key] as? JObj ?: throw IllegalArgumentException("$key is not an object")
    fun arr(key: String): JArr = map[key] as? JArr ?: throw IllegalArgumentException("$key is not a list")
    fun int(key: String): BigInteger = (map[key] as? JInt)?.value ?: throw IllegalArgumentException("$key is not an integer")
    fun long(key: String): Long = int(key).longValueExact()
    fun str(key: String): String = (map[key] as? JStr)?.value ?: throw IllegalArgumentException("$key is not text")
    fun strOrNull(key: String): String? = (map[key] as? JStr)?.value
    fun bool(key: String): Boolean = (map[key] as? JBool)?.value ?: throw IllegalArgumentException("$key is not a boolean")
    fun isNull(key: String): Boolean = map[key] == null || map[key] == JNull
}

/** Builders for readable ports: `jobj("a" to 1, "b" to jarr(2, 3))`. */
fun jvalue(value: Any?): JValue = when (value) {
    null -> JNull
    is JValue -> value
    is Boolean -> JBool(value)
    is Int -> JInt(value)
    is Long -> JInt(value)
    is UInt -> JInt(value.toLong())
    is ULong -> JInt(BigInteger(value.toString()))
    is BigInteger -> JInt(value)
    is Double -> JFloat(value)
    is Float -> JFloat(value.toDouble())
    is String -> JStr(value)
    is Map<*, *> -> JObj(LinkedHashMap<String, JValue>().apply { value.forEach { (k, v) -> put(k as String, jvalue(v)) } })
    is Iterable<*> -> JArr(value.mapTo(ArrayList()) { jvalue(it) })
    is Array<*> -> JArr(value.mapTo(ArrayList()) { jvalue(it) })
    else -> throw IllegalArgumentException("No JSON form for ${value::class.simpleName}")
}

fun jobj(vararg entries: Pair<String, Any?>): JObj =
    JObj(LinkedHashMap<String, JValue>().apply { entries.forEach { (k, v) -> put(k, jvalue(v)) } })

fun jarr(vararg items: Any?): JArr = JArr(items.mapTo(ArrayList()) { jvalue(it) })

val JValue.asObj: JObj get() = this as? JObj ?: throw IllegalArgumentException("not an object")
val JValue.asArr: JArr get() = this as? JArr ?: throw IllegalArgumentException("not a list")
val JValue.asInt: BigInteger get() = (this as? JInt)?.value ?: throw IllegalArgumentException("not an integer")
val JValue.asLong: Long get() = asInt.longValueExact()
val JValue.asStr: String get() = (this as? JStr)?.value ?: throw IllegalArgumentException("not text")
