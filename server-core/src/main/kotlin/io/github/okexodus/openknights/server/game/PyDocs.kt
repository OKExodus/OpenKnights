package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JBool
import io.github.okexodus.openknights.exact.JFloat
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JNull
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.PyFloat
import io.github.okexodus.openknights.server.store.StateStore
import java.math.BigInteger

/**
 * Dictionary-document idioms of the reference's daily systems, kept in one place so the ported functions read like
 * the originals: `current.get(key)`, `d.get(key)` (an explicit JSON null reads as absent), `str()`,
 * the reference's list ordering and negative indexing, and its `json.dumps(sort_keys=True)` comparisons. Truthiness is
 * [Py.truthy], the text-to-number rules are [PyValues].
 */
object PyDocs {
    /** `current.get(key)`: a field of the save read or a per-character document; null when absent or JSON null. */
    fun get(current: StateStore.Current, key: String): JValue? = when (key) {
        "state" -> current.state
        "character_profile" -> current.characterProfile
        "inventory_items" -> current.inventoryItems
        "acquired_items" -> current.acquiredItems
        "secondary_team" -> current.secondaryTeam
        "god_skills" -> current.godSkills
        "jewelry_list" -> current.jewelryList
        "jewel_entries_view" -> current.jewelEntriesView
        else -> current.document(key)
    }?.takeIf { it != JNull }

    /** `current.get(key)` of a document that must be an object when present. */
    fun obj(current: StateStore.Current, key: String): JObj? = get(current, key)?.let { it as JObj }

    /** `d.get(key)` (null for absent or JSON null). */
    fun get(d: JObj?, key: String): JValue? = d?.get(key)?.takeIf { it != JNull }

    /** `str(v)` / an f-string field of a document value. */
    fun str(v: JValue?): String = when (v) {
        null, JNull -> "None"
        is JBool -> if (v.value) "True" else "False"
        is JInt -> v.value.toString()
        is JFloat -> PyFloat.repr(v.value)
        is JStr -> v.value
        else -> Json.dumps(v)
    }

    /** `int(v)` of a document value (integers, whole floats truncated, decimal text, booleans). */
    fun int(v: JValue?): BigInteger = when (v) {
        is JInt -> v.value
        is JBool -> if (v.value) BigInteger.ONE else BigInteger.ZERO
        is JFloat -> {
            if (v.value.isNaN() || v.value.isInfinite()) throw PyValues.ValueError("cannot convert float to integer")
            java.math.BigDecimal(v.value).toBigInteger()
        }
        is JStr -> PyValues.parseInt(v.value)
        null, JNull -> throw TypeError("int() argument must be a string, a bytes-like object or a real number, not 'NoneType'")
        else -> throw TypeError("int() argument must be a string, a bytes-like object or a real number")
    }

    /** The reference's TypeError (a wrong value type; never a refusal code). */
    class TypeError(message: String) : RuntimeException(message)

    /** The reference's KeyError. */
    class KeyError(key: Any?) : NoSuchElementException(key.toString())

    /** `d[key]` (KeyError when absent). */
    fun at(d: JObj, key: String): JValue = d[key] ?: throw KeyError("'$key'")

    fun long(v: JValue?): Long = (v as? JInt)?.value?.longValueExact() ?: int(v).longValueExact()

    /** The reference's ordering of numbers, texts and lists of them (`sorted()` of document lists). */
    fun compare(a: JValue, b: JValue): Int {
        if (a is JInt && b is JInt) return a.value.compareTo(b.value)
        if (a is JStr && b is JStr) return Json.CodePointOrder.compare(a.value, b.value)
        if (a is JArr && b is JArr) {
            for (i in 0 until minOf(a.size, b.size)) {
                if (a[i] == b[i]) continue
                val c = compare(a[i], b[i])
                if (c != 0) return c
            }
            return a.size.compareTo(b.size)
        }
        if (a is JBool || b is JBool || a is JFloat || b is JFloat) {
            val x = number(a)
            val y = number(b)
            return x.compareTo(y)
        }
        throw TypeError("'<' not supported between instances")
    }

    private fun number(v: JValue): java.math.BigDecimal = when (v) {
        is JInt -> java.math.BigDecimal(v.value)
        is JBool -> if (v.value) java.math.BigDecimal.ONE else java.math.BigDecimal.ZERO
        is JFloat -> java.math.BigDecimal(v.value)
        else -> throw TypeError("'<' not supported between instances")
    }

    val ORDER: Comparator<JValue> = Comparator { a, b -> compare(a, b) }

    /** `sorted(values)` (stable). */
    fun sorted(values: Iterable<JValue>): JArr = JArr(values.sortedWith(ORDER).toMutableList())

    /** `seq[i]` with the reference's negative indexing (from the end). */
    fun <T> index(seq: List<T>, i: Int): T {
        val at = if (i < 0) i + seq.size else i
        if (at < 0 || at >= seq.size) throw IndexOutOfBoundsException("list index out of range")
        return seq[at]
    }

    /** `json.dumps(v, sort_keys=True)` (default separators), the reference's change test. */
    fun sortedDump(v: JValue?): String = Json.dumps(v ?: JNull, sortKeys = true)

    /** `bytes([...])`: every value 0..255, else ValueError. */
    fun bytes(values: List<Long>): ByteArray = ByteArray(values.size) { i ->
        val v = values[i]
        if (v < 0 || v > 255) throw PyValues.ValueError("bytes must be in range(0, 256)")
        v.toByte()
    }

    /** `copy.deepcopy(current)`: the read's documents and trees copied (the dry runs work on it). */
    fun deepCopy(current: StateStore.Current): StateStore.Current {
        val documents = LinkedHashMap<String, JValue?>()
        for ((k, v) in current.documents) documents[k] = v?.deepCopy()
        return StateStore.Current(current.revision, current.sourceSha256, current.payloadSha256, current.state.deepCopy(),
            current.payload.copyOf(), current.inventorySchemaVersion, current.inventorySha256, current.inventoryItems.deepCopy(),
            current.inventoryPayloads.map { it.copyOf() }, current.acquiredItems.deepCopy(), current.acquiredPayloads.map { it.copyOf() },
            current.acquiredSha256, current.secondaryTeam?.deepCopy(), current.godSkills?.deepCopy(), current.characterProfile?.deepCopy(),
            current.jewelryList?.deepCopy(), documents, current.fixtureInjectedHeroUids, current.acquiredHeroUids,
        ).also { it.jewelEntriesView = current.jewelEntriesView?.deepCopy() }
    }

    /** A shallow `dict(d)` copy. */
    fun shallow(d: JObj): JObj = JObj(LinkedHashMap(d.map))

    /** The `bits` of a role property (`f["value"].get("bits", default)` of the first field with that id), or default. */
    fun role(state: JObj, fieldId: Long, default: JValue? = JInt(0)): JValue? {
        for (f in state.arr("role_properties")) {
            val field = f as JObj
            if (field["id"] == JInt(fieldId)) return field.obj("value").let { if (it.containsKey("bits")) it["bits"] else default }
        }
        return default
    }

    /** `next(f["value"]["bits"] for f in role_properties if f["id"] == id)` with a default when no field has the id. */
    fun roleStrict(state: JObj, fieldId: Long, default: Long = 0): JValue {
        for (f in state.arr("role_properties")) {
            val field = f as JObj
            if (field["id"] == JInt(fieldId)) return at(field.obj("value"), "bits")
        }
        return JInt(default)
    }
}
