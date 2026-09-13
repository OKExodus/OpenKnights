package io.github.okexodus.openknights.protocol

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JNull
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.asInt
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.asStr
import io.github.okexodus.openknights.exact.hexBytes
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.toHexString

/**
 * The subsystem sections of the player record (after the item list), each read and written by the layout of its
 * reader in the client. Integers keep their wire widths; strings are hex without the NUL; counts and order are kept,
 * including the client's consumption caps (20 buildings, 3 message strings).
 */
object PlayerSections {
    private class Fixed(val countFormat: Char, val format: String, val cap: Int?)

    private val FIXED_LISTS = mapOf(
        "gems" to Fixed('B', "II", null),
        "technologies" to Fixed('B', "II", null),
        "hero_collection" to Fixed('H', "I", null),
        "equip_collection" to Fixed('H', "I", null),
        "jewelry_collection" to Fixed('H', "I", null),
        "achievements" to Fixed('B', "BBI", null),
        "buildings" to Fixed('B', "BH", 20),
        "buffs" to Fixed('B', "II", null),
    )
    private val FIXED_VALUES = mapOf("alchemy" to "BBBIBBIBB", "vip" to "BBBBB")

    private fun cstringHex(r: WireReader): JStr = JStr(r.cstringBytes().toHexString())
    private fun packCstring(w: WireWriter, hex: JValue) = w.cstring(hex.asStr.hexBytes(), "subsystem string")

    private fun readList(r: WireReader, countFormat: Char, cap: Int? = null, entry: (WireReader) -> JValue): JObj {
        val count = r.number(countFormat).toInt()
        val entries = JArr()
        repeat(if (cap == null) count else minOf(count, cap)) { entries.add(entry(r)) }
        return jobj("count" to count, "entries" to entries)
    }

    private fun encodeList(w: WireWriter, state: JObj, countFormat: Char, cap: Int? = null, entry: (JValue) -> Unit) {
        val count = state.int("count").toInt()
        val expected = if (cap == null) count else minOf(count, cap)
        val entries = state.arr("entries")
        if (entries.size != expected) throw ProtocolException("Declared subsystem count disagrees with entries")
        w.number(countFormat, count.toLong())
        entries.forEach(entry)
    }

    private fun readFixedList(r: WireReader, countFormat: Char, format: String, cap: Int? = null) =
        readList(r, countFormat, cap) { jobj("wire_values" to it.values(format)) }

    private fun encodeFixedList(w: WireWriter, state: JObj, countFormat: Char, format: String, cap: Int? = null) =
        encodeList(w, state, countFormat, cap) { w.values(format, it.asObj.arr("wire_values")) }

    private fun readFriend(r: WireReader): JObj =
        jobj("wire_u32_1" to r.number('I'), "cstring_hex" to cstringHex(r), "wire_values_after_string" to r.values("IIIIQII"))

    private fun encodeFriend(w: WireWriter, s: JObj) {
        w.number('I', s.getValue("wire_u32_1")); packCstring(w, s.getValue("cstring_hex"))
        w.values("IIIIQII", s.arr("wire_values_after_string"))
    }

    private fun servantFormat(extraTimers: Boolean) = if (extraTimers) "IIIIIIBBI" else "IIIIBBI"

    private fun readServant(r: WireReader, extraTimers: Boolean = false, firstValue: JInt? = null): JObj =
        jobj("wire_u32_1" to (firstValue ?: r.number('I')), "cstring_hex" to cstringHex(r),
            "wire_values_after_string" to r.values(servantFormat(extraTimers)))

    private fun encodeServant(w: WireWriter, s: JObj, extraTimers: Boolean = false) {
        w.number('I', s.getValue("wire_u32_1")); packCstring(w, s.getValue("cstring_hex"))
        w.values(servantFormat(extraTimers), s.arr("wire_values_after_string"))
    }

    private fun readActivityRow(r: WireReader): JObj {
        val state = jobj("cstring_hex" to cstringHex(r), "pairs" to readFixedList(r, 'B', "II"), "wire_u8_flag" to r.number('B'))
        state["conditional_cstring_hex"] = if (state.int("wire_u8_flag").signum() != 0) cstringHex(r) else JNull
        return state
    }

    private fun encodeActivityRow(w: WireWriter, s: JObj) {
        val conditional = s.getValue("conditional_cstring_hex")
        if ((s.int("wire_u8_flag").signum() != 0) != (conditional != JNull)) throw ProtocolException("Activity row string disagrees with presence flag")
        packCstring(w, s.getValue("cstring_hex"))
        encodeFixedList(w, s.obj("pairs"), 'B', "II")
        w.number('B', s.getValue("wire_u8_flag"))
        if (conditional != JNull) packCstring(w, conditional)
    }

    private fun readActivity(r: WireReader, secondList: Boolean = false): JObj {
        val state = if (secondList) JObj() else jobj("wire_u32_1" to r.number('I'))
        state["cstring_hex"] = JArr(MutableList<JValue>(4) { cstringHex(r) })
        state["wire_u32_after_strings"] = r.number('I')
        if (!secondList) state["rows"] = readList(r, 'B') { readActivityRow(it) }
        return state
    }

    private fun encodeActivity(w: WireWriter, s: JObj, secondList: Boolean = false) {
        val strings = s.arr("cstring_hex")
        if (strings.size != 4) throw ProtocolException("Activity requires four wire strings")
        if (!secondList) w.number('I', s.getValue("wire_u32_1"))
        strings.forEach { packCstring(w, it) }
        w.number('I', s.getValue("wire_u32_after_strings"))
        if (!secondList) encodeList(w, s.obj("rows"), 'B') { encodeActivityRow(w, it.asObj) }
    }

    /** Native `char > 0`: a flag byte counts as set only for 1..127. */
    private fun positiveSignedByte(value: JValue): Boolean { val v = value.asInt.toInt(); return v in 1..127 }

    private fun readRoulette(r: WireReader): JObj {
        val state = jobj("wire_values" to r.values("BIIIIB"))
        state["entries"] = if (positiveSignedByte(state.arr("wire_values").last())) readFixedList(r, 'B', "IIIIIIIII") else JNull
        return state
    }

    private fun encodeRoulette(w: WireWriter, s: JObj) {
        val values = s.arr("wire_values")
        val active = positiveSignedByte(values.last())
        if (active != (s.getValue("entries") != JNull)) throw ProtocolException("Roulette entries disagree with signed presence flag")
        w.values("BIIIIB", values)
        if (active) encodeFixedList(w, s.obj("entries"), 'B', "IIIIIIIII")
    }

    private fun readGameActivities(r: WireReader): JObj {
        val state = jobj("wire_u8_prefix" to r.number('B'))
        state["first_list"] = readList(r, 'B') { readActivity(it) }
        state["second_list"] = readList(r, 'B') { readActivity(it, secondList = true) }
        state["wire_u8_after_lists"] = r.number('B')
        state["update_u32"] = r.number('I')
        state["update_conditional_u8"] = if (state.int("update_u32").signum() != 0) r.number('B') else JNull
        val flag = state.int("wire_u8_prefix").toInt()
        state["roulette"] = if (flag == 1 || flag == 2) readRoulette(r) else JNull
        state["roulette_items"] = if (flag == 1) readFixedList(r, 'B', "I") else JNull
        return state
    }

    private fun encodeGameActivities(w: WireWriter, s: JObj) {
        val flag = s.int("wire_u8_prefix").toInt()
        val conditional = s.getValue("update_conditional_u8")
        if ((s.int("update_u32").signum() != 0) != (conditional != JNull)) throw ProtocolException("Activity update flag disagrees with presence value")
        if ((flag == 1 || flag == 2) != (s.getValue("roulette") != JNull)) throw ProtocolException("Roulette section disagrees with activity prefix")
        if ((flag == 1) != (s.getValue("roulette_items") != JNull)) throw ProtocolException("Roulette items disagree with activity prefix")
        w.number('B', flag.toLong())
        encodeList(w, s.obj("first_list"), 'B') { encodeActivity(w, it.asObj) }
        encodeList(w, s.obj("second_list"), 'B') { encodeActivity(w, it.asObj, secondList = true) }
        w.number('B', s.getValue("wire_u8_after_lists"))
        w.number('I', s.getValue("update_u32"))
        if (conditional != JNull) w.number('B', conditional)
        if (s.getValue("roulette") != JNull) encodeRoulette(w, s.obj("roulette"))
        if (s.getValue("roulette_items") != JNull) encodeFixedList(w, s.obj("roulette_items"), 'B', "I")
    }

    /** S1188 (activity update): u32 activity id, then the activity's row list. */
    fun encodeActivityUpdate(activityId: Long, rows: JObj): ByteArray {
        val w = WireWriter().number('I', activityId)
        encodeList(w, rows, 'B') { encodeActivityRow(w, it.asObj) }
        return w.bytes()
    }

    fun readActivityUpdate(payload: ByteArray): JObj {
        val r = WireReader(payload)
        val update = jobj("activity_id" to r.number('I'), "rows" to readList(r, 'B') { readActivityRow(it) })
        if (r.offset != payload.size) throw ProtocolException("S1188 has trailing bytes")
        return update
    }

    fun readSection(name: String, r: WireReader): JObj {
        FIXED_LISTS[name]?.let { return readFixedList(r, it.countFormat, it.format, it.cap) }
        FIXED_VALUES[name]?.let { return jobj("wire_values" to r.values(it)) }
        return when (name) {
            "stages" -> jobj("stages" to readFixedList(r, 'H', "IBBB"), "tail" to readFixedList(r, 'B', "I"))
            "friends" -> readList(r, 'B') { readFriend(it) }
            "servants" -> {
                val state = jobj("wire_u8_prefix" to r.values("BB"), "servants" to readList(r, 'B') { readServant(it) })
                val optionalId = r.number('I')
                state["optional_servant"] = if (optionalId.value.signum() != 0) readServant(r, extraTimers = true, firstValue = optionalId) else JNull
                state
            }
            "servant_message" -> jobj("wire_u32_1" to r.number('I'), "strings" to readList(r, 'B', 3) { cstringHex(it) })
            "xinggong" -> jobj("wire_u32_prefix" to r.values("IIII"), "entries" to readFixedList(r, 'B', "III"))
            "game_activities" -> readGameActivities(r)
            else -> throw ProtocolException("Unsupported player section: $name")
        }
    }

    fun encodeSection(name: String, s: JObj, w: WireWriter) {
        FIXED_LISTS[name]?.let { encodeFixedList(w, s, it.countFormat, it.format, it.cap); return }
        FIXED_VALUES[name]?.let { w.values(it, s.arr("wire_values")); return }
        when (name) {
            "stages" -> { encodeFixedList(w, s.obj("stages"), 'H', "IBBB"); encodeFixedList(w, s.obj("tail"), 'B', "I") }
            "friends" -> encodeList(w, s, 'B') { encodeFriend(w, it.asObj) }
            "servants" -> {
                val optional = s.getValue("optional_servant")
                if (optional != JNull && optional.asObj.int("wire_u32_1").signum() == 0) throw ProtocolException("Optional servant cannot have zero presence value")
                w.values("BB", s.arr("wire_u8_prefix"))
                encodeList(w, s.obj("servants"), 'B') { encodeServant(w, it.asObj) }
                if (optional == JNull) w.raw(ByteArray(4)) else encodeServant(w, optional.asObj, extraTimers = true)
            }
            "servant_message" -> { w.number('I', s.getValue("wire_u32_1")); encodeList(w, s.obj("strings"), 'B', 3) { packCstring(w, it) } }
            "xinggong" -> { w.values("IIII", s.arr("wire_u32_prefix")); encodeFixedList(w, s.obj("entries"), 'B', "III") }
            "game_activities" -> encodeGameActivities(w, s)
            else -> throw ProtocolException("Unsupported player section: $name")
        }
    }

    fun encodeSection(name: String, s: JObj): ByteArray = WireWriter().also { encodeSection(name, s, it) }.bytes()
}
