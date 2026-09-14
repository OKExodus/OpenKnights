package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.F32
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
import io.github.okexodus.openknights.protocol.WireReader
import io.github.okexodus.openknights.protocol.WireWriter

/**
 * Gear and jewelry evolve (`equip_evolve.py`, C2049 / C2629): codecs, planners and reply frames, and the client's item
 * property formulas (`Formula::GetEquipPropertyValue` / `GetJewelPropertyValue`, binary32 in the instructions' order)
 * that the displayed value increase and the universal Power use.
 *
 * - Requests: one u32, the selected owned uid. Rows: gear `equipjinhua`, jewelry `jewelry_jinhua` (the grade's row and
 *   the next one); costs three materials (+ Gold for gear, a fourth item for jewelry); gates: item level, role level.
 * - Replies after the commit: gear 68 / 66 per material slot, 106 (record with the grade raised), 2208 (uid, value
 *   increase), 128 (Gold); jewelry 68 / 66 per material slot, 3080, 3084. Level and EXP never change.
 * - Excluded (refused without change): the up-star rows (requests 2593 / 2817), the highest tier, unequipped
 *   (opcode-3072) jewelry and jewelry whose base / ratio is a random range.
 */
object EquipEvolve {
    const val F32_TEN_THOUSAND = 10000.0
    const val GEAR_REQUEST_OPCODE = 2049
    const val JEWEL_REQUEST_OPCODE = 2629
    const val GEAR_RESULT_OPCODE = 2208
    const val JEWEL_RESULT_OPCODE = 3084
    const val GEAR_UPDATE_OPCODE = 106
    const val JEWEL_UPDATE_OPCODE = 3080
    const val ITEM_UPDATE_OPCODE = 68
    const val ITEM_REMOVE_OPCODE = 66
    const val ROLE_UPDATE_OPCODE = 128
    const val UPSTAR_GEAR_OPCODE = 2593
    const val UPSTAR_JEWEL_OPCODE = 2817
    const val E_UID = 0
    const val E_CONFIG = 1
    const val E_LEVEL = 2
    const val E_EXP = 3
    const val E_GRADE = 4
    const val E_FLAG = 5
    const val E_EXTRA = 6
    const val JEWEL_GRADE_OFFSET = 16

    // Local mapping onto existing client texts (8000000 + code).
    const val ERROR_INVALID = 102                // "Invalid Data"
    const val ERROR_GOLD = 4000                  // "Not enough Resources"
    const val ERROR_ROLE_LEVEL = 6002            // "Your level is too low"
    const val ERROR_GEAR_MISSING = 6004          // "Can't find the selected Gear"
    const val ERROR_GEAR_NOT_EVOLVABLE = 6011    // "This Gear cannot be evolved"
    const val ERROR_GEAR_LEVEL = 6012            // "The Gear Level is too low"
    const val ERROR_GEAR_TOP_TIER = 6020         // "This Gear has reached the highest tier"
    const val ERROR_JEWEL_MISSING = 69501        // "Cannot find the specific Jewelry"
    const val ERROR_JEWEL_NOT_EVOLVABLE = 69508  // "The Jewelry cannot be tier up"
    const val ERROR_JEWEL_LEVEL = 69509          // "The Jewelry's Lv is too low"
    const val ERROR_JEWEL_TOP_TIER = 69515       // "This Jewelry has reached the highest tier"
    const val ERROR_MATERIALS = 69686            // "Insufficient to evolve"
    const val EVIDENCE_CLASS = "capture_observed_rule_config_row"

    /** `EquipEvolveRejected(message, code)`: a refusal answered S6 `code`. */
    class EquipEvolveRejected(message: String, val code: Int = ERROR_INVALID) : IllegalArgumentException(message)

    private fun uint(value: Long, width: Int, label: String): Long {
        if (value < 0 || value >= (1L shl width)) throw PyValues.ValueError("$label must fit uint$width")
        return value
    }

    /** `decode_request`: C2049 / C2629 (and the excluded 2593 / 2817): exactly one u32 owned uid. */
    fun decodeRequest(payload: ByteArray): JObj {
        if (payload.size != 4) throw EquipEvolveRejected("Evolve request must be exactly one u32 uid")
        return jobj("target_uid" to WireReader(payload).u32())
    }

    fun encodeRequest(value: JObj): ByteArray = WireWriter().number('I', uint(value.long("target_uid"), 32, "Target uid")).bytes()

    // --- planners -----------------------------------------------------------------------------------------------------

    /** `_consume(materials, items)`: items {uid: [uid, template, count]}; one owned stack per template, else refused. */
    private fun consume(materials: JArr, items: Map<Long, JArr>): JArr {
        val byTemplate = LinkedHashMap<Long, MutableList<Long>>()
        for ((uid, wire) in items) byTemplate.getOrPut(wire[1].long) { ArrayList() }.add(uid)
        val changes = JArr()
        materials.forEachIndexed { slot, m ->
            val template = m.asArr[0].long
            val quantity = m.asArr[1].long
            if (template == 0L || quantity <= 0) return@forEachIndexed
            val uids = byTemplate[template] ?: emptyList<Long>()
            if (uids.size != 1) throw EquipEvolveRejected("Evolve material $template is missing or not a single stack", ERROR_MATERIALS)
            val owned = items.getValue(uids[0])[2].long
            if (owned < quantity) throw EquipEvolveRejected("Insufficient evolve material $template", ERROR_MATERIALS)
            val remaining = owned - quantity
            val packet: Frame = if (remaining == 0L) ITEM_REMOVE_OPCODE to TransactionPackets.itemRemovePayload(uids[0])
                else ITEM_UPDATE_OPCODE to TransactionPackets.itemCountPayload(uids[0], remaining)
            changes.add(jobj("slot" to slot, "uid" to uids[0], "template" to template, "quantity" to quantity,
                "remaining" to remaining, "packet" to jarr(packet.first, packet.second.toHexString())))
        }
        return changes
    }

    private fun checkRow(inputs: JObj, notEvolvable: Int, topTier: Int): JObj {
        val row = inputs["row"] as? JObj ?: throw EquipEvolveRejected("No configured evolve row for this item and grade", notEvolvable)
        if (row.long("evolvable_115") == 0L) {
            if (row.long("upstar_117") == 1L) {
                throw EquipEvolveRejected("This grade continues with Super-evolution (up-star), an excluded branch", notEvolvable)
            }
            throw EquipEvolveRejected("Highest configured tier", topTier)
        }
        if (inputs["next_row"] !is JObj) throw EquipEvolveRejected("The next grade has no configured row", topTier)
        return row
    }

    private fun gates(row: JObj, level: Long, roleLevel: Long?, levelCode: Int) {
        if (level < row.long("item_level_112")) throw EquipEvolveRejected("Item level below the evolve requirement", levelCode)
        if (roleLevel == null || roleLevel < row.long("role_level_116")) throw EquipEvolveRejected("Role level below the evolve requirement", ERROR_ROLE_LEVEL)
    }

    private fun materialsDetail(changes: JArr): JArr = JArr(changes.mapTo(ArrayList()) { c ->
        val o = c.asObj
        jobj("slot" to o["slot"], "uid" to o["uid"], "template" to o["template"], "quantity" to o["quantity"], "remaining" to o["remaining"])
    })

    /** `plan_gear_evolve`: one decoded C2049 against an owned view → the exact mutation. */
    fun planGearEvolve(request: JObj, equipment: Map<Long, List<Long>>, items: Map<Long, JArr>, gold: java.math.BigInteger,
                       roleLevel: Long?, inputs: JObj?, equippedUids: Collection<Long> = emptyList()): JObj {
        val uid = request.long("target_uid")
        val owned = equipment[uid] ?: throw EquipEvolveRejected("Target gear is not owned", ERROR_GEAR_MISSING)
        if (inputs == null) throw EquipEvolveRejected("Catalog inputs are unavailable for the requested gear", ERROR_GEAR_NOT_EVOLVABLE)
        val before = owned.toList()
        if (before[E_CONFIG] != inputs.long("template") || before[E_GRADE] != inputs.long("grade")) {
            throw EquipEvolveRejected("Catalog inputs do not match the owned record")
        }
        val row = checkRow(inputs, ERROR_GEAR_NOT_EVOLVABLE, ERROR_GEAR_TOP_TIER)
        if (before[E_GRADE] + 1 > 0xFF) throw EquipEvolveRejected("Grade would overflow its wire byte", ERROR_GEAR_TOP_TIER)
        gates(row, before[E_LEVEL], roleLevel, ERROR_GEAR_LEVEL)
        val cost = row.long("gold_111")
        if (gold < java.math.BigInteger.valueOf(cost)) throw EquipEvolveRejected("Insufficient Gold for the evolve", ERROR_GOLD)
        val itemChanges = consume(row.arr("materials"), items)
        val after = before.toMutableList().also { it[E_GRADE] = before[E_GRADE] + 1 }
        fun value(potential: Long) = equipPropertyValue(before[E_LEVEL], before[E_FLAG], before[E_EXTRA], inputs.long("base_108"),
            inputs.long("growth_109"), potential, inputs.long("property_912"))
        val valueBefore = value(inputs.long("potential_before"))
        val valueAfter = value(inputs.long("potential_after"))
        val next = inputs.obj("next_row")
        return jobj("kind" to "gear", "target_uid" to uid, "before_record" to before, "after_record" to after,
            "target_was_equipped" to (uid in equippedUids.toSet()),
            "row_key" to row["key_101"], "next_row_key" to next["key_101"],
            "gates" to jobj("item_level" to row["item_level_112"], "role_level" to row["role_level_116"]),
            "cap_before" to row["cap_113"], "cap_after" to next["cap_113"],
            "potential_before" to inputs["potential_before"], "potential_after" to inputs["potential_after"],
            "value_before" to valueBefore, "value_after" to valueAfter, "value_increase" to ((valueAfter - valueBefore) and 0xFFFFFFFFL),
            "super_flag_popup" to (before[E_FLAG] != 0L),
            "item_changes" to itemChanges, "gold_cost" to cost, "gold_after" to (gold - java.math.BigInteger.valueOf(cost)),
            "observed_row" to (inputs["observed_row"] ?: JBool(false)), "evidence_class" to EVIDENCE_CLASS)
    }

    /** `plan_jewel_evolve`: one decoded C2629 against the equipped-jewelry view → the exact mutation. */
    fun planJewelEvolve(request: JObj, jewelry: Map<Long, List<Long>>, items: Map<Long, JArr>, roleLevel: Long?, inputs: JObj?,
                        unequippedUids: Collection<Long> = emptyList()): JObj {
        val uid = request.long("target_uid")
        val owned = jewelry[uid]
        if (owned == null) {
            if (uid in unequippedUids.toSet()) throw EquipEvolveRejected("Unequipped (opcode-3072) jewelry is not modelled; excluded", ERROR_JEWEL_MISSING)
            throw EquipEvolveRejected("Target jewelry is not owned", ERROR_JEWEL_MISSING)
        }
        if (inputs == null) throw EquipEvolveRejected("Catalog inputs are unavailable for the requested jewelry", ERROR_JEWEL_NOT_EVOLVABLE)
        val before = owned.toList()
        if (before[E_CONFIG] != inputs.long("template") || before[E_GRADE] != inputs.long("grade")) {
            throw EquipEvolveRejected("Catalog inputs do not match the owned jewelry")
        }
        val row = checkRow(inputs, ERROR_JEWEL_NOT_EVOLVABLE, ERROR_JEWEL_TOP_TIER)
        if (before[E_GRADE] + 1 > 0xFF) throw EquipEvolveRejected("Grade would overflow its byte", ERROR_JEWEL_TOP_TIER)
        gates(row, before[E_LEVEL], roleLevel, ERROR_JEWEL_LEVEL)
        val itemChanges = consume(row.arr("materials"), items)
        val after = before.toMutableList().also { it[E_GRADE] = before[E_GRADE] + 1 }
        fun value(potential: Long) = jewelPropertyValue(before[E_LEVEL], before[E_FLAG], before[E_EXTRA], inputs.long("base_108"),
            inputs.long("ratio_110"), potential, inputs.long("property_956"))
        val valueBefore = value(inputs.long("potential_before"))
        val valueAfter = value(inputs.long("potential_after"))
        val next = inputs.obj("next_row")
        return jobj("kind" to "jewelry", "target_uid" to uid, "before_record" to before, "after_record" to after,
            "row_key" to row["key_101"], "next_row_key" to next["key_101"],
            "gates" to jobj("item_level" to row["item_level_112"], "role_level" to row["role_level_116"]),
            "cap_before" to row["cap_113"], "cap_after" to next["cap_113"],
            "potential_before" to inputs["potential_before"], "potential_after" to inputs["potential_after"],
            "value_before" to valueBefore, "value_after" to valueAfter, "value_increase" to ((valueAfter - valueBefore) and 0xFFFFFFFFL),
            "super_flag_popup" to (before[E_FLAG] != 0L),
            "item_changes" to itemChanges, "gold_cost" to 0,
            "observed_row" to (inputs["observed_row"] ?: JBool(false)), "evidence_class" to EVIDENCE_CLASS)
    }

    /** The `materials` history entries of a plan (`{slot, uid, template, quantity, remaining}` per change). */
    fun materials(plan: JObj): JArr = materialsDetail(plan.arr("item_changes"))

    // --- frames ---------------------------------------------------------------------------------------------------------

    /** `decode_jewel_list`: S3072, u32 n then n × 22-byte records. */
    fun decodeJewelList(payload: ByteArray): List<List<Long>> {
        if (payload.size < 4) throw PyValues.ValueError("Jewelry list is truncated")
        val r = WireReader(payload)
        val count = r.u32()
        if (payload.size.toLong() != 4 + 22 * count) throw PyValues.ValueError("Jewelry list length does not match its count")
        return (0 until count).map { r.values("IIIIBBI").map { v -> v.long } }
    }

    /** `record_payload`: the 22-byte opcode 106 / 3080 record. */
    fun recordPayload(record: List<Long>): ByteArray {
        if (record.size != 7) throw PyValues.ValueError("Record needs seven wire values")
        val widths = listOf(32, 32, 32, 32, 8, 8, 32)
        record.forEachIndexed { i, v -> uint(v, widths[i], "Record value $i") }
        return WireWriter().values("IIIIBBI", record.map { JInt(it) }).bytes()
    }

    /** S2208 / S3084: u32 uid, u32 displayed value increase. */
    fun resultPayload(uid: Long, increase: Long): ByteArray =
        WireWriter().number('I', uint(uid, 32, "Uid")).number('I', uint(increase, 32, "Value increase")).bytes()

    /** The same 40-byte formation block with only the grade byte replaced. */
    fun jewelBlockWithGrade(raw: ByteArray, grade: Long): ByteArray {
        if (raw.size != 40) throw PyValues.ValueError("Jewelry block must be 40 bytes")
        return raw.copyOfRange(0, JEWEL_GRADE_OFFSET) + byteArrayOf(uint(grade, 8, "Jewelry grade").toByte()) +
            raw.copyOfRange(JEWEL_GRADE_OFFSET + 1, raw.size)
    }

    /** `evolve_packets`: gear 68 / 66 per slot, 106, 2208, 128; jewelry 68 / 66, 3080, 3084. */
    fun evolvePackets(plan: JObj, goldPayload: ByteArray? = null): List<Frame> {
        val packets = plan.arr("item_changes").mapTo(ArrayList<Frame>()) { c ->
            val p = c.asObj.arr("packet")
            p[0].long.toInt() to (p[1] as JStr).value.hexBytes()
        }
        val after = plan.arr("after_record").map { it.long }
        if (plan.str("kind") == "gear") {
            if (goldPayload == null) throw PyValues.ValueError("Gear evolve needs the Gold payload")
            packets.add(GEAR_UPDATE_OPCODE to recordPayload(after))
            packets.add(GEAR_RESULT_OPCODE to resultPayload(plan.long("target_uid"), plan.long("value_increase")))
            packets.add(ROLE_UPDATE_OPCODE to goldPayload)
        } else {
            packets.add(JEWEL_UPDATE_OPCODE to recordPayload(after))
            packets.add(JEWEL_RESULT_OPCODE to resultPayload(plan.long("target_uid"), plan.long("value_increase")))
        }
        return packets
    }

    /** `f32(value)`: binary32 rounding; a non-finite result is refused (a finite overflow raises like `struct.pack`). */
    fun f32(value: Double): Double {
        val result = F32.round(value)
        if (!result.isFinite()) throw PyValues.ValueError("Non-finite binary32 result")
        return result
    }

    /** ARM64 FCVTZU to 32 bits: truncate toward zero, saturate to [0, 2^32 - 1]. */
    fun fcvtzu(value: Double): Long = F32.fcvtzu(value)

    /** `sub w9,level,#1; cmp level,#0; scvtf; fcsel` → 0.0 at level 0. */
    fun levelFactor(level: Long): Double = if (level == 0L) 0.0 else f32((level - 1).toDouble())

    fun superFactor(value: Long, propertyRaw: Long): Long {
        val factor = f32(f32(f32(propertyRaw.toDouble()) / F32_TEN_THOUSAND) + 1.0)
        return fcvtzu(f32(factor * f32(value.toDouble())))
    }

    /** `Formula::GetEquipPropertyValue` 0x00b4e650. */
    fun equipPropertyValue(level: Long, superFlag: Long, extra: Long, base108: Long, growth109: Long, potential: Long, property912: Long): Long {
        val rate = f32(f32(potential.toDouble()) / F32_TEN_THOUSAND)
        var s0 = f32(rate + 1.0)
        s0 = f32(s0 * f32(growth109.toDouble()))
        s0 = f32(s0 * levelFactor(level))
        var value = (base108 + fcvtzu(s0)) and 0xFFFFFFFFL
        if (superFlag != 0L) value = superFactor(value, property912)
        return (extra + value) and 0xFFFFFFFFL
    }

    /** `Formula::GetJewelPropertyValue` 0x00b4ec84 for a fixed (non-random) base / ratio. */
    fun jewelPropertyValue(level: Long, superFlag: Long, extra: Long, base108: Long, ratio110: Long, potential: Long, property956: Long): Long {
        val perLevel = fcvtzu(f32(f32(f32(ratio110.toDouble()) / F32_TEN_THOUSAND) * f32(base108.toDouble())))
        val rate = f32(f32(potential.toDouble()) / F32_TEN_THOUSAND)
        var s0 = f32(rate + 1.0)
        s0 = f32(s0 * f32(perLevel.toDouble()))
        s0 = f32(s0 * levelFactor(level))
        var value = (base108 + fcvtzu(s0)) and 0xFFFFFFFFL
        if (superFlag != 0L) value = superFactor(value, property956)
        return (extra + value) and 0xFFFFFFFFL
    }
}
