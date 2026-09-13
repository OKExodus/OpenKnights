package io.github.okexodus.openknights.protocol

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JBool
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.asArr
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.hexBytes
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.toHexString

/**
 * The player record (opcode 18): the header (reset flag, login mode), then for a full record the role properties,
 * equipment, heroes, bag, formation, captain, capacities, items and every subsystem section, and four final flags.
 * Login modes 1 and 2 are short status replies. The decoded tree has the same keys in the same order as the reference
 * (it is what a save stores), and [encode] writes the exact bytes back, any undecoded tail included.
 */
object PlayerState {
    val MIDDLE_SECTIONS = listOf("gems", "technologies", "stages", "friends", "hero_collection", "equip_collection",
        "jewelry_collection", "achievements", "buildings", "alchemy", "servants")
    val END_SECTIONS = listOf("vip", "buffs", "xinggong", "game_activities")

    private fun countedNumbers(r: WireReader, countFormat: Char = 'B', itemFormat: Char = 'I'): JArr {
        val values = JArr()
        repeat(r.number(countFormat).toInt()) { values.add(r.number(itemFormat)) }
        return values
    }

    private fun packNumbers(w: WireWriter, values: JArr, countFormat: Char = 'B', itemFormat: Char = 'I') {
        w.number(countFormat, values.size.toLong())
        values.forEach { w.number(itemFormat, it) }
    }

    private fun readFormation(r: WireReader): JArr {
        val slots = JArr()
        repeat(r.u8()) {
            val slot = jobj("slot_id" to r.number('B'), "hero_uid" to r.number('I'))
            val assignments = JArr()
            repeat(r.u8()) { assignments.add(jarr(r.number('B'), r.number('I'))) }
            slot["assignments"] = assignments
            val blocks = JArr()
            repeat(r.u8()) { blocks.add(jobj("id" to r.number('B'), "raw_hex" to r.take(40).toHexString())) }
            slot["blocks_40"] = blocks
            val groups = JArr()
            repeat(r.u8()) { groups.add(jobj("id" to r.number('B'), "values" to countedNumbers(r))) }
            slot["groups"] = groups
            slot["flag"] = r.number('B')
            slots.add(slot)
        }
        return slots
    }

    private fun encodeFormation(w: WireWriter, slots: JArr) {
        w.number('B', slots.size.toLong())
        for (s in slots) {
            val slot = s.asObj
            val assignments = slot.arr("assignments")
            w.number('B', slot.getValue("slot_id")).number('I', slot.getValue("hero_uid")).number('B', assignments.size.toLong())
            assignments.forEach { entry -> w.values("BI", entry.asArr) }
            val blocks = slot.arr("blocks_40")
            w.number('B', blocks.size.toLong())
            for (b in blocks) {
                val raw = b.asObj.str("raw_hex").hexBytes()
                if (raw.size != 40) throw ProtocolException("Formation block must be 40 bytes")
                w.number('B', b.asObj.getValue("id")).raw(raw)
            }
            val groups = slot.arr("groups")
            w.number('B', groups.size.toLong())
            groups.forEach { g -> w.number('B', g.asObj.getValue("id")); packNumbers(w, g.asObj.arr("values")) }
            w.number('B', slot.getValue("flag"))
        }
    }

    private fun positiveSignedByte(v: Long) = v in 1..127

    fun parse(payload: ByteArray, prefixOnly: Boolean = false): JObj {
        val r = WireReader(payload)
        val result = jobj("reset_player" to r.number('B'), "login_mode" to r.number('I'))
        val loginMode = result.long("login_mode")
        if (loginMode != 1L && loginMode != 2L) {
            val start = r.offset
            result["role_properties"] = TypedValues.readFields(r)
            val end = r.offset
            if (!TypedValues.encodeFieldsBytes(result.arr("role_properties")).contentEquals(payload.copyOfRange(start, end))) {
                throw ProtocolException("Role property re-encoding differs from source")
            }
            result["role_properties_range"] = jarr(start, end)
            val equipment = JArr()
            repeat(r.u8()) {
                val offset = r.offset
                equipment.add(jobj("offset" to offset, "wire_values" to r.values("IIIIBBI")))
            }
            result["equipment"] = equipment
            val heroes = JArr()
            repeat(r.u8()) { heroes.add(TypedValues.readFields(r)) }
            result["heroes"] = heroes
            result["offline_hero_uids"] = countedNumbers(r)
            result["bag_equipment_uids"] = countedNumbers(r)
            result["formation"] = readFormation(r)
            result["captain_slot"] = r.number('B')
            result["item_capacity_values"] = jarr(r.number('H'), r.number('H'), r.number('H'))
            result["items"] = readItems(r, r.u8())
            if (prefixOnly) {
                result["next_section"] = JStr("GemSystem::HandleGemList")   // where a prefix-only decode stopped
            } else {
                val subsystems = JObj()
                val ranges = JObj()
                result["subsystems"] = subsystems
                result["subsystem_ranges"] = ranges
                fun readNamed(name: String) {
                    val s = r.offset
                    val section = PlayerSections.readSection(name, r)
                    if (!PlayerSections.encodeSection(name, section).contentEquals(payload.copyOfRange(s, r.offset))) {
                        throw ProtocolException("Re-encoding differs for $name")
                    }
                    subsystems[name] = section
                    ranges[name] = jarr(s, r.offset)
                }
                MIDDLE_SECTIONS.forEach(::readNamed)
                val messages = JArr()
                repeat(r.u8()) { messages.add(PlayerSections.readSection("servant_message", r)) }
                result["servant_messages"] = messages
                result["title_reward_flag"] = r.number('B')
                END_SECTIONS.forEach(::readNamed)
                result["final_flags"] = jarr(r.number('B'), r.number('B'), r.number('B'), r.number('B'))
            }
        } else if (loginMode == 2L) {
            result["mode_2_values"] = jarr(r.number('I'), r.number('I'), r.number('I'))
        }
        result["decoded_bytes"] = JInt(r.offset)
        result["payload_bytes"] = JInt(payload.size)
        result["unparsed_tail_hex"] = JStr(payload.copyOfRange(r.offset, payload.size).toHexString())
        result["complete"] = JBool(r.offset == payload.size)
        return result
    }

    /** The item list of the record and of opcode 64: three u32, a flag byte, and two timing values when the flag is 1..127. */
    fun readItems(r: WireReader, count: Int): JArr {
        val items = JArr()
        repeat(count) {
            val item = jobj("wire_values" to jarr(r.number('I'), r.number('I'), r.number('I')), "timed_flag" to r.number('B'))
            if (positiveSignedByte(item.long("timed_flag"))) item["timed_values"] = jarr(r.number('i'), r.number('I'))
            items.add(item)
        }
        return items
    }

    fun encode(state: JObj): ByteArray {
        val w = WireWriter()
        w.number('B', state.getValue("reset_player")).number('I', state.getValue("login_mode"))
        val loginMode = state.long("login_mode")
        if (loginMode != 1L && loginMode != 2L) {
            TypedValues.encodeFields(state.arr("role_properties"), w)
            val equipment = state.arr("equipment")
            w.number('B', equipment.size.toLong())
            equipment.forEach { w.values("IIIIBBI", it.asObj.arr("wire_values")) }
            val heroes = state.arr("heroes")
            w.number('B', heroes.size.toLong())
            heroes.forEach { TypedValues.encodeFields(it.asArr, w) }
            packNumbers(w, state.arr("offline_hero_uids"))
            packNumbers(w, state.arr("bag_equipment_uids"))
            encodeFormation(w, state.arr("formation"))
            val capacities = state.arr("item_capacity_values")
            w.number('B', state.getValue("captain_slot"))
            if (capacities.size != 3) throw ProtocolException("pack expected 4 items for packing (got ${capacities.size + 1})")
            capacities.forEach { w.number('H', it) }
            val items = state.arr("items")
            w.number('B', items.size.toLong())
            for (it in items) {
                val item = it.asObj
                val values = item.arr("wire_values")
                if (values.size != 3) throw ProtocolException("pack expected 4 items for packing (got ${values.size + 1})")
                w.values("III", values).number('B', item.getValue("timed_flag"))
                if (positiveSignedByte(item.long("timed_flag"))) w.values("iI", item.arr("timed_values"))
            }
            if (state.containsKey("subsystems")) {
                val subsystems = state.obj("subsystems")
                MIDDLE_SECTIONS.forEach { PlayerSections.encodeSection(it, subsystems.obj(it), w) }
                val messages = state.arr("servant_messages")
                w.number('B', messages.size.toLong())
                messages.forEach { PlayerSections.encodeSection("servant_message", it.asObj, w) }
                w.number('B', state.getValue("title_reward_flag"))
                END_SECTIONS.forEach { PlayerSections.encodeSection(it, subsystems.obj(it), w) }
                val flags = state.arr("final_flags")
                if (flags.size != 4) throw ProtocolException("pack expected 4 items for packing (got ${flags.size})")
                flags.forEach { w.number('B', it) }
            }
        } else if (loginMode == 2L) {
            w.values("III", state.arr("mode_2_values"))
        }
        w.raw(state.str("unparsed_tail_hex").hexBytes())
        return w.bytes()
    }

    /** Role property `id` of a full record (exactly one occurrence). */
    fun role(state: JObj, id: Int): JObj = TypedValues.field(state.arr("role_properties"), id)
}
