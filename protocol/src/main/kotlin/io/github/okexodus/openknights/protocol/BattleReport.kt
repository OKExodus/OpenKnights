package io.github.okexodus.openknights.protocol

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.asArr
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.asStr
import io.github.okexodus.openknights.exact.hexBytes
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.toHexString

/**
 * The single battle report (opcode 4, versions 0..6) with its nested Reward (versions 0..14), lossless. A structural
 * codec only: it does not compute battles. Absent historical fields stay absent; the team wrapper (1798) is separate.
 */
object BattleReport {
    private fun version(value: JValue, maximum: Int, label: String): Int {
        val v = (value as? JInt)?.value?.toInt()
        if (v == null || v !in 0..maximum) throw ProtocolException("Unsupported $label version $value; expected 0..$maximum")
        return v
    }

    private fun readFields(r: WireReader, fields: List<Pair<String, Char>>, into: JObj = JObj()): JObj {
        fields.forEach { (name, fmt) -> into[name] = r.number(fmt) }
        return into
    }

    private fun encodeFields(w: WireWriter, value: JObj, fields: List<Pair<String, Char>>) =
        fields.forEach { (name, fmt) -> w.number(fmt, value.getValue(name)) }

    private fun readList(r: WireReader, item: (WireReader) -> JValue): JArr {
        val list = JArr()
        repeat(r.u8()) { list.add(item(r)) }
        return list
    }

    private fun encodeList(w: WireWriter, values: JArr, item: (JValue) -> Unit) {
        w.number('B', values.size.toLong())
        values.forEach(item)
    }

    /** A C string as hex, so bytes that are not UTF-8 survive unchanged. */
    private fun readString(r: WireReader): JStr = JStr(r.cstringBytes().toHexString())

    private fun encodeString(w: WireWriter, hex: JValue) {
        val raw = hex.asStr.hexBytes()
        if (raw.contains(0.toByte())) throw ProtocolException("C string contains an embedded NUL")
        w.raw(raw).raw(byteArrayOf(0))
    }

    val TARGET_FIELDS = listOf("position" to 'B', "delta" to 'i', "dispatcher" to 'I', "outcome_raw" to 'B',
        "presentation_or_helper" to 'I', "flag_raw" to 'B')
    val ACTOR_FIELDS = listOf("position" to 'B', "identifier_raw" to 'I', "packed_hero_id" to 'I', "max_hp" to 'I',
        "current_hp" to 'I', "sp" to 'I', "value_18_raw" to 'H')
    private val SIDE_FIELDS = listOf("value_250_raw" to 'I', "value_258_raw" to 'B', "value_25c_raw" to 'I')
    private val REPORT_END_FIELDS = listOf("flag_230_raw" to 'B', "value_231_raw" to 'B', "value_232_raw" to 'B')

    fun readTarget(r: WireReader): JObj = readFields(r, TARGET_FIELDS)
    fun encodeTarget(w: WireWriter, t: JObj) = encodeFields(w, t, TARGET_FIELDS)

    private fun readAttack(r: WireReader): JObj {
        val v = readFields(r, listOf("position" to 'B', "skill_id" to 'I'))
        v["targets"] = readList(r) { readTarget(it) }
        return v
    }

    private fun encodeAttack(w: WireWriter, v: JObj) {
        encodeFields(w, v, listOf("position" to 'B', "skill_id" to 'I'))
        encodeList(w, v.arr("targets")) { encodeTarget(w, it.asObj) }
    }

    private fun readInitialEffect(r: WireReader): JObj {
        val v = readFields(r, listOf("position" to 'B', "gift_id" to 'I'))
        v["targets"] = readList(r) { readTarget(it) }
        return v
    }

    private fun encodeInitialEffect(w: WireWriter, v: JObj) {
        encodeFields(w, v, listOf("position" to 'B', "gift_id" to 'I'))
        encodeList(w, v.arr("targets")) { encodeTarget(w, it.asObj) }
    }

    private fun readTotem(r: WireReader): JObj {
        val v = readFields(r, listOf("selector_raw" to 'B', "identifier_raw" to 'I'))
        v["targets"] = readList(r) { readTarget(it) }
        v["value_20_raw"] = r.number('I')
        return v
    }

    private fun encodeTotem(w: WireWriter, v: JObj) {
        encodeFields(w, v, listOf("selector_raw" to 'B', "identifier_raw" to 'I'))
        encodeList(w, v.arr("targets")) { encodeTarget(w, it.asObj) }
        w.number('I', v.getValue("value_20_raw"))
    }

    private fun readCombo(r: WireReader): JObj {
        val v = jobj("positions" to readList(r) { it.number('B') }, "hero_group_id" to r.number('I'))
        v["targets"] = readList(r) { readTarget(it) }
        return v
    }

    private fun encodeCombo(w: WireWriter, v: JObj) {
        encodeList(w, v.arr("positions")) { w.number('B', it) }
        w.number('I', v.getValue("hero_group_id"))
        encodeList(w, v.arr("targets")) { encodeTarget(w, it.asObj) }
    }

    private class RoundField(val name: String, val read: (WireReader) -> JValue, val write: (WireWriter, JValue) -> Unit)

    private fun roundFields(version: Int): List<RoundField> {
        val fields = mutableListOf(
            RoundField("attacks", { readAttack(it) }, { w, v -> encodeAttack(w, v.asObj) }),
            RoundField("targets", { readTarget(it) }, { w, v -> encodeTarget(w, v.asObj) }))
        if (version >= 3) fields.add(RoundField("totems", { readTotem(it) }, { w, v -> encodeTotem(w, v.asObj) }))
        if (version >= 4) fields.add(RoundField("combos", { readCombo(it) }, { w, v -> encodeCombo(w, v.asObj) }))
        if (version >= 6) fields.add(RoundField("changes", { it.values("BB") }, { w, v -> w.values("BB", v.asArr) }))
        return fields
    }

    private fun readRound(r: WireReader, version: Int): JObj {
        val round = JObj()
        roundFields(version).forEach { f -> round[f.name] = readList(r, f.read) }
        return round
    }

    private fun encodeRound(w: WireWriter, v: JObj, version: Int) =
        roundFields(version).forEach { f -> encodeList(w, v.arr(f.name)) { f.write(w, it) } }

    /** Reward fields by version; a format starting with '[' is a u8-counted list of that tuple. */
    private fun rewardFields(version: Int): List<Pair<String, String>> {
        val fields = mutableListOf("exp" to (if (version >= 6) "q" else "i"), "exploit" to "I", "gold" to (if (version >= 14) "Q" else "I"))
        listOf("diamond", "stamina", "energy", "friend_point", "reputation", "arena_chance").forEach { fields.add(it to "I") }
        fields += listOf("items" to "[II", "heroes" to "[I", "equips" to "[I",
            "hero_grow" to "[" + "I".repeat(if (version >= 10) 11 else 7), "equip_grow" to "[III",
            "partner_friend_point" to "I", "vip_exp" to "I", "buffs" to "[II")
        if (version >= 2) fields.add("gems" to "[II")
        if (version >= 3) fields.add("courage" to "I")
        if (version >= 4) fields += listOf("hero_levels" to "[H", "equip_levels" to "[H")
        if (version >= 5) fields += listOf("double_charge_raw" to "B", "flag_17d_raw" to "B")
        if (version >= 7) fields.add("donation" to "I")
        if (version >= 8) fields.add("equip_grades" to "[H")
        if (version >= 9) fields.add("jewels" to "[I")
        if (version >= 11) fields.add("jewel_grow" to "[III")
        if (version >= 12) fields.add("kind_door_score" to "I")
        if (version >= 13) listOf("soul_hero", "soul_equip", "soul_jewel", "vip_pt").forEach { fields.add(it to "I") }
        return fields
    }

    fun readReward(r: WireReader): JObj {
        val v = version(r.number('I'), 14, "Reward")
        val result = jobj("version" to v)
        for ((name, fmt) in rewardFields(v)) {
            result[name] = if (fmt.startsWith("[")) readList(r) { it.values(fmt.substring(1)) } else r.number(fmt[0])
        }
        return result
    }

    fun encodeReward(w: WireWriter, value: JObj) {
        val v = version(value.getValue("version"), 14, "Reward")
        w.number('I', v.toLong())
        for ((name, fmt) in rewardFields(v)) {
            if (fmt.startsWith("[")) encodeList(w, value.arr(name)) { w.values(fmt.substring(1), it.asArr) }
            else w.number(fmt[0], value.getValue(name))
        }
    }

    fun encodeReward(value: JObj): ByteArray = WireWriter().also { encodeReward(it, value) }.bytes()

    private fun readActor(r: WireReader, version: Int): JObj {
        val result = if (version >= 6) jobj("lineup_raw" to r.number('B')) else JObj()
        readFields(r, ACTOR_FIELDS, result)
        if (version >= 2) result["ability_rate"] = r.number('I')
        result["value_1a_raw"] = r.number('H')
        return result
    }

    private fun encodeActor(w: WireWriter, actor: JObj, version: Int) {
        if (version >= 6) w.number('B', actor.getValue("lineup_raw"))
        encodeFields(w, actor, ACTOR_FIELDS)
        if (version >= 2) w.number('I', actor.getValue("ability_rate"))
        w.number('H', actor.getValue("value_1a_raw"))
    }

    /** Decode one opcode-4 payload; trailing or missing bytes are refused. */
    fun parse(payload: ByteArray): JObj {
        val r = WireReader(payload)
        val v = version(r.number('H'), 6, "battle report")
        val result = jobj("version" to v, "battle_type" to r.number(if (v >= 5) 'I' else 'B'),
            "identifier_80_raw" to r.number('I'), "sides" to JArr())
        repeat(2) {
            val side = jobj("name_hex" to readString(r), "actors" to readList(r) { readActor(it, v) })
            if (v >= 3) readFields(r, SIDE_FIELDS, side)
            result.arr("sides").add(side)
        }
        readFields(r, listOf("result_raw" to 'B', "display_stars" to 'B'), result)
        result["initial_effects"] = readList(r) { readInitialEffect(it) }
        result["rounds"] = readList(r) { readRound(it, v) }
        result["flag_208_raw"] = r.number('B')
        if (result.long("flag_208_raw") in 1..127) result["value_20c_raw"] = r.number('I')
        result["value_210_raw"] = r.number('I')
        result["string_218_hex"] = readString(r)
        readFields(r, REPORT_END_FIELDS, result)
        if (v != 0 && result.int("battle_type").mod(java.math.BigInteger.valueOf(100)).toInt() == 6) {
            result["base_type_6_value"] = r.number('I')
        }
        result["reward"] = readReward(r)
        if (r.offset != payload.size) throw ProtocolException("Unparsed battle-report tail at ${r.offset}: ${payload.size - r.offset} bytes")
        return result
    }

    fun encode(value: JObj): ByteArray {
        val v = version(value.getValue("version"), 6, "battle report")
        val sides = value.arr("sides")
        if (sides.size != 2) throw ProtocolException("Native report contains exactly two sides")
        val w = WireWriter()
        w.number('H', v.toLong()).number(if (v >= 5) 'I' else 'B', value.getValue("battle_type")).number('I', value.getValue("identifier_80_raw"))
        for (s in sides) {
            val side = s.asObj
            encodeString(w, side.getValue("name_hex"))
            encodeList(w, side.arr("actors")) { encodeActor(w, it.asObj, v) }
            if (v >= 3) encodeFields(w, side, SIDE_FIELDS)
        }
        encodeFields(w, value, listOf("result_raw" to 'B', "display_stars" to 'B'))
        encodeList(w, value.arr("initial_effects")) { encodeInitialEffect(w, it.asObj) }
        encodeList(w, value.arr("rounds")) { encodeRound(w, it.asObj, v) }
        w.number('B', value.getValue("flag_208_raw"))
        if (value.long("flag_208_raw") in 1..127) w.number('I', value.getValue("value_20c_raw"))
        w.number('I', value.getValue("value_210_raw"))
        encodeString(w, value.getValue("string_218_hex"))
        encodeFields(w, value, REPORT_END_FIELDS)
        if (v != 0 && value.int("battle_type").mod(java.math.BigInteger.valueOf(100)).toInt() == 6) {
            w.number('I', value.getValue("base_type_6_value"))
        }
        encodeReward(w, value.obj("reward"))
        return w.bytes()
    }

    /** Campaign packets beside the report: C129 request, S160 stars, S162 first kill, S164 star reward. */
    fun parseCampaignPacket(opcode: Int, payload: ByteArray): JObj {
        val r = WireReader(payload)
        val value = when (opcode) {
            129 -> readFields(r, listOf("stage_id" to 'I', "summoned_friend_role_id" to 'I', "hero_position" to 'B'))
            160 -> readFields(r, listOf("stage_id" to 'I', "stars" to 'B', "stage_value_2c_raw" to 'B', "stage_value_30_raw" to 'B'))
            162 -> jobj("stage_id" to r.number('I'), "first_kill_name_hex" to readString(r), "first_kill_value_raw" to r.number('I'))
            164 -> jobj("stage_reward_id_raw" to r.number('I'), "reward" to readReward(r))
            else -> throw ProtocolException("Unsupported campaign opcode $opcode")
        }
        if (r.offset != payload.size) throw ProtocolException("Unparsed campaign packet tail")
        return value
    }
}
