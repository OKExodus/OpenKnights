package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.gamedata.GameTables
import io.github.okexodus.openknights.protocol.BattleReport
import io.github.okexodus.openknights.protocol.TypedValues
import io.github.okexodus.openknights.protocol.WireReader
import io.github.okexodus.openknights.protocol.WireWriter
import java.math.BigInteger

/**
 * Ordinary existing-instance hero (C69) and gear (C81) Fortify (`hero_fortify.py`): request / reply codecs, the bounded
 * arithmetic and the reply order.
 *
 * - Requests (C69 / C81): u32 target owned UID, u8 count, then each material UID as u32 in the client's ascending set
 *   order. Results (S48 / S108): u8 count, count x (u32 A template, i8 B, u32 C awarded EXP, u8 D), then the Reward.
 * - S46 is a u32 UID plus the changed typed fields; S106 the full 22-byte equipment record; S34 / S40 / S98 / S102 a u8
 *   count and u32 UIDs.
 * - Observed reply orders: hero 46, 1184, 48, 40, 34, 128; gear 106, 108, 102, 98, then 128 only when Gold was charged.
 *
 * No persistence: the planners validate a request against an owned-state view and return the exact mutation; the store
 * transactions apply them. Bonuses are never generated here; levelled materials, cap-reaching hero settlement and
 * guide heroes are refused without change (docs/FORTIFY_CONTRACT.md, docs/GEAR_FORTIFY_CONTRACT.md).
 */
object HeroFortify {
    const val REQUEST_OPCODE = 69
    const val GEAR_REQUEST_OPCODE = 81
    const val RESULT_OPCODE = 48
    const val GEAR_RESULT_OPCODE = 108
    const val HERO_UPDATE_OPCODE = 46
    const val EQUIP_UPDATE_OPCODE = 106
    const val ACTIVITY_LIST_OPCODE = 1184
    const val BENCH_REMOVE_OPCODE = 40
    const val HERO_REMOVE_OPCODE = 34
    const val EQUIP_BAG_REMOVE_OPCODE = 102
    const val EQUIP_REMOVE_OPCODE = 98
    const val ROLE_UPDATE_OPCODE = 128
    const val ERROR_OPCODE = 6

    // Hero dynamic property IDs (docs/HERO_PROPERTIES.md).
    const val UID = 0
    const val TEMPLATE = 1L
    const val LEVEL = 2L
    const val EXP = 3L
    val STAT_IDS = listOf(4L, 6L, 8L, 10L)          // Hp, Atk, Def, Unique
    val GROW_IDS = listOf(5L, 7L, 9L, 11L)          // HpGrow, AtkGrow, DefGrow, UniqueGrow
    const val GROW_TYPE = 13L
    const val IS_GUIDE = 14L
    val DEV_IDS = listOf(15L, 16L, 17L, 18L)
    val REBORN_IDS = listOf(19L, 20L, 21L, 22L, 23L)
    const val AWAKEN = 24L
    val TRANSMITTED_ORDER = listOf(LEVEL, EXP, 4L, 6L, 8L, 10L)
    const val U32 = 0xFFFFFFFFL
    // Equipment wire record: UID, config, level, EXP, grade byte, flag byte, extra u32.
    const val E_UID = 0
    const val E_TEMPLATE = 1
    const val E_LEVEL = 2
    const val E_EXP = 3
    const val E_GRADE = 4
    const val E_FLAG = 5
    const val E_EXTRA = 6
    private val EQUIP_WIDTHS = listOf(32, 32, 32, 32, 8, 8, 32)

    // Native text IDs are 8000000 + code.
    const val ERROR_INVALID = 102            // "Invalid Data"
    const val ERROR_NO_MATERIALS = 1001      // "You do not have enough Heroes to Fortify"
    const val ERROR_MAX_LEVEL = 1008         // "The Hero already reached the top level"
    const val ERROR_GUIDE_HERO = 1013        // "Your Guiding Hero cannot be Fortified"
    const val ERROR_MATERIALS_NOT_FOUND = 1024  // "Can not find the materials"
    const val ERROR_GEAR_NO_MATERIALS = 6005    // "You do not have enough Gear to Fortify"
    const val ERROR_GEAR_MAX_LEVEL = 6010       // "Your Gear Level has reached its limit"

    // No-bonus result prefix values.
    const val NO_BONUS_B = 1L
    const val OBSERVED_PREFIX_D = 1L
    val HERO_GROW_TAIL = listOf(0L, 0L, 0L, 0L)  // components 8-11: zero in every retained row

    /** `FortifyRejected(message, code)`: a refusal answered S6 `code`. */
    class FortifyRejected(message: String, val code: Int = ERROR_INVALID) : IllegalArgumentException(message)

    /** The [HeroStats.Reject] form of [FortifyRejected]. */
    val REJECT = HeroStats.Reject { m, c -> FortifyRejected(m, c) }

    private fun uint(value: Long, width: Int, label: String): Long {
        if (value < 0 || (width < 64 && value >= (1L shl width))) throw PyValues.ValueError("$label must fit uint$width")
        return value
    }

    // --- wire codecs ---------------------------------------------------------------------------------------------

    /** `decode_fortify_request`: the lossless C69 / C81 layout (u32 target, u8 count, count x u32 material). */
    fun decodeFortifyRequest(payload: ByteArray): JObj {
        val reader = WireReader(payload)
        val target = reader.u32()
        val count = reader.u8()
        val materials = (0 until count).map { reader.u32() }
        if (reader.offset != payload.size) throw PyValues.ValueError("Fortify request has trailing bytes")
        return jobj("target_uid" to target, "material_uids" to materials)
    }

    /** `encode_fortify_request`. */
    fun encodeFortifyRequest(value: JObj): ByteArray {
        val materials = value.arr("material_uids").map { it.long }
        if (materials.size > 255) throw PyValues.ValueError("Fortify request count must fit uint8")
        val w = WireWriter().number('I', uint(value.long("target_uid"), 32, "Target UID")).number('B', materials.size.toLong())
        for (uid in materials) w.number('I', uint(uid, 32, "Material UID"))
        return w.bytes()
    }

    /** `decode_upgrade_result`: S48 / S108 (u8 count; count x (u32 A, i8 B, u32 C, u8 D); Reward). */
    fun decodeUpgradeResult(payload: ByteArray): JObj {
        val reader = WireReader(payload)
        val records = JArr()
        repeat(reader.u8()) {
            val a = reader.u32(); val b = reader.i8(); val c = reader.u32(); val d = reader.u8()
            records.add(jobj("field_a_u32_raw" to a, "field_b_i8_raw" to b, "field_c_u32_raw" to c, "field_d_u8_raw" to d))
        }
        val reward = BattleReport.readReward(reader)
        if (reader.offset != payload.size) throw PyValues.ValueError("Upgrade result has trailing bytes")
        return jobj("records" to records, "reward" to reward)
    }

    /** `encode_upgrade_result`. */
    fun encodeUpgradeResult(value: JObj): ByteArray {
        val records = value.arr("records")
        if (records.size > 255) throw PyValues.ValueError("Upgrade result count must fit uint8")
        val w = WireWriter().number('B', records.size.toLong())
        for (r in records) {
            val record = r.asObj
            val b = record["field_b_i8_raw"] as? JInt
            if (b == null || b.value < BigInteger.valueOf(-128) || b.value > BigInteger.valueOf(127)) throw PyValues.ValueError("Result field B must fit int8")
            w.number('I', uint(record.long("field_a_u32_raw"), 32, "Result field A")).number('b', b.value)
                .number('I', uint(record.long("field_c_u32_raw"), 32, "Result field C"))
                .number('B', uint(record.long("field_d_u8_raw"), 8, "Result field D"))
        }
        BattleReport.encodeReward(w, value.obj("reward"))
        return w.bytes()
    }

    /** Opcode 46: the u32 owned UID, then the absolute typed fields to apply (`hero_property_update_payload`). */
    fun heroPropertyUpdatePayload(uid: Long, fields: JArr): ByteArray {
        if (fields.isEmpty()) throw PyValues.ValueError("A hero property update carries at least one field")
        return WireWriter().number('I', uint(uid, 32, "Owned hero UID")).also { TypedValues.encodeFields(fields, it) }.bytes()
    }

    /** Opcode 106 and the opcode-18 / 96 record: the exact 22-byte tuple (`equipment_record_payload`). */
    fun equipmentRecordPayload(wireValues: List<Long>): ByteArray {
        if (wireValues.size != 7) throw PyValues.ValueError("Equipment record needs seven wire values")
        wireValues.forEachIndexed { index, v -> uint(v, EQUIP_WIDTHS[index], "Equipment wire value $index") }
        return WireWriter().number('I', wireValues[0]).number('I', wireValues[1]).number('I', wireValues[2]).number('I', wireValues[3])
            .number('B', wireValues[4]).number('B', wireValues[5]).number('I', wireValues[6]).bytes()
    }

    /** Opcodes 34 / 40 / 98 / 102: a u8 count, then the u32 owned UIDs (`counted_uid_payload`). */
    fun countedUidPayload(uids: List<Long>): ByteArray {
        if (uids.size !in 1..255) throw PyValues.ValueError("Counted UID list must hold 1..255 references")
        val w = WireWriter().number('B', uids.size.toLong())
        for (uid in uids) w.number('I', uint(uid, 32, "Owned UID"))
        return w.bytes()
    }

    /** `empty_reward(version)`: every scalar zero and every list empty. */
    fun emptyReward(version: Int = 14): JObj = BattleReport.emptyReward(version)

    // --- owned-hero field access -----------------------------------------------------------------------------------

    /** `hero_uid_of(fields, field_id)`: the one scalar field of this id (its `bits`); anything else is refused. */
    fun heroUidOf(fields: JArr, fieldId: Int = UID): BigInteger {
        val values = fields.map { it.asObj }.filter { (it["id"] as? JInt)?.value?.toInt() == fieldId }.map { it.obj("value") }
        if (values.size != 1 || (values[0]["tag"] as? JInt)?.value?.toInt() !in TypedValues.WIDTH_FORMAT) {
            throw PyValues.ValueError("Expected one scalar hero field $fieldId")
        }
        return (values[0].getValue("bits") as JInt).value
    }

    /** `field_map(fields)`: {id: value}, refusing a repeated id (the native map replaces by key). */
    fun fieldMap(fields: JArr): LinkedHashMap<Long, JObj> = HeroStats.fieldMap(fields)

    /** `_bits(values, field_id, default)`: a scalar field's bits; absent is refused unless a default is given. */
    private fun bits(values: Map<Long, JObj>, fieldId: Long, default: BigInteger? = null): BigInteger {
        val value = values[fieldId] ?: return default ?: throw FortifyRejected("Hero field $fieldId is absent")
        if ("bits" !in value || (value["tag"] as? JInt)?.value?.toInt() !in TypedValues.WIDTH_FORMAT) throw FortifyRejected("Hero field $fieldId is not a scalar")
        return (value["bits"] as JInt).value
    }

    private fun tagOf(value: JObj): Int = (value["tag"] as JInt).value.toInt()

    private fun widthOf(tag: Int): Int = when (TypedValues.WIDTH_FORMAT.getValue(tag).uppercaseChar()) { 'B' -> 8; 'H' -> 16; 'I' -> 32; else -> 64 }

    /**
     * `resolve_target_profile(fields, inputs)`: a Fortify TARGET through the shared bounded stat model (developed / reborn /
     * awakened / super-class targets are accepted when the model reproduces their wire stats); guiding heroes → 1013.
     */
    fun resolveTargetProfile(fields: JArr, inputs: JObj?): JObj = HeroStats.resolveProfile(fields, inputs, REJECT, ERROR_GUIDE_HERO)

    /**
     * `check_supported_profile(fields)`: the bounded zero profile still applied to consumed MATERIAL heroes (level tag
     * 4, EXP / stat tag 6, growth tag 4; GrowType, IsGuide, development, reborn and awaken zero or absent).
     */
    fun checkSupportedProfile(fields: JArr): LinkedHashMap<Long, JObj> {
        val values = fieldMap(fields)
        for (fieldId in listOf(UID.toLong(), TEMPLATE, LEVEL, EXP) + STAT_IDS + GROW_IDS) bits(values, fieldId)
        if (tagOf(values.getValue(LEVEL)) != 4 || tagOf(values.getValue(EXP)) != 6) throw FortifyRejected("Hero level/EXP tags are outside the evidenced profile")
        if (STAT_IDS.any { tagOf(values.getValue(it)) != 6 } || GROW_IDS.any { tagOf(values.getValue(it)) != 4 }) {
            throw FortifyRejected("Hero stat/growth tags are outside the evidenced profile")
        }
        for (fieldId in listOf(GROW_TYPE, IS_GUIDE) + DEV_IDS + REBORN_IDS + AWAKEN) {
            if (bits(values, fieldId, BigInteger.ZERO).signum() != 0) {
                if (fieldId == IS_GUIDE) throw FortifyRejected("Guiding hero is outside the supported Fortify profile", ERROR_GUIDE_HERO)
                throw FortifyRejected("Hero field $fieldId is nonzero; profile not evidenced")
            }
        }
        if (bits(values, UID.toLong()).signum() == 0 || bits(values, TEMPLATE).signum() == 0) throw FortifyRejected("Hero identity fields must be nonzero")
        return values
    }

    // --- bounded arithmetic --------------------------------------------------------------------------------------

    /** A level-indexed EXP curve (`{level: exp}`, JSON keys) lookup. */
    private fun curveAt(curve: JObj, level: Long): Long? = (curve[level.toString()] as? JInt)?.value?.toLong()

    /**
     * `settle_hero_exp(level, residual, awarded, cap, base_exp_by_level, hero137)`: consume awarded EXP level by level
     * below the cap (GetExpOfHeroLevel(level) is the requirement to leave `level`); a result at the cap is reported.
     */
    fun settleHeroExp(level: Long, residual: Long, awarded: Long, cap: Long, baseExpByLevel: JObj, hero137: Long): JObj {
        if (level !in 1..0xFFFF) throw FortifyRejected("Hero level must be a positive uint16")
        if (cap !in 1..0xFFFF) throw FortifyRejected("Configured cap must be a positive uint16")
        if (level >= cap) throw FortifyRejected("The hero already reached its configured level cap", ERROR_MAX_LEVEL)
        var remaining = uint(residual, 32, "Residual EXP") + uint(awarded, 32, "Awarded EXP")
        val consumed = JArr()
        var lv = level
        while (lv < cap) {
            val base = curveAt(baseExpByLevel, lv) ?: throw FortifyRejected("No heroexp row for level $lv")
            val requirement = HeroDictionaryProgression.expForLevel(base, hero137)
            if (requirement <= 0) throw FortifyRejected("Non-positive EXP requirement at level $lv")
            if (remaining < requirement) break
            remaining -= requirement
            consumed.add(jobj("level" to lv, "requirement" to requirement))
            lv += 1
        }
        if (remaining > U32) throw FortifyRejected("Residual EXP would exceed uint32")
        return jobj("level" to lv, "residual" to remaining, "levels_gained" to consumed.size, "consumed" to consumed,
            "cap" to cap, "reached_cap" to (lv >= cap))
    }

    /** `exp_for_equip_level(base_exp, equip113)` (GetExpOfEquipLevel): binary64 raw x scale / 10000, truncated. */
    fun expForEquipLevel(baseExp: Long, equip113: Long): Long =
        (((baseExp and U32).toDouble() * (equip113 and U32).toDouble()) / 10000.0).toLong()

    /** `settle_equipment_exp(...)`: the same loop for gear; reaching the cap leaves residual 0 (the excess is discarded). */
    fun settleEquipmentExp(level: Long, residual: Long, awarded: Long, cap: Long, baseExpByLevel: JObj, equip113: Long): JObj {
        if (level < 1 || level > U32) throw FortifyRejected("Equipment level must be a positive uint32")
        if (cap < 1 || cap > U32) throw FortifyRejected("Configured equipment cap must be positive")
        if (level >= cap) throw FortifyRejected("The gear already reached its configured level cap", ERROR_GEAR_MAX_LEVEL)
        var remaining = uint(residual, 32, "Residual EXP") + uint(awarded, 32, "Awarded EXP")
        val consumed = JArr()
        var lv = level
        while (lv < cap) {
            val base = curveAt(baseExpByLevel, lv) ?: throw FortifyRejected("No equipexp row for level $lv")
            val requirement = expForEquipLevel(base, equip113)
            if (requirement <= 0) throw FortifyRejected("Non-positive EXP requirement at equipment level $lv")
            if (remaining < requirement) break
            remaining -= requirement
            consumed.add(jobj("level" to lv, "requirement" to requirement))
            lv += 1
        }
        val reachedCap = lv >= cap
        val discarded = if (reachedCap) remaining else 0L
        if (reachedCap) remaining = 0
        if (remaining > U32) throw FortifyRejected("Residual EXP would exceed uint32")
        return jobj("level" to lv, "residual" to remaining, "levels_gained" to consumed.size, "consumed" to consumed,
            "cap" to cap, "reached_cap" to reachedCap, "discarded_at_cap" to discarded)
    }

    /**
     * `grown_fields(before, settlement, model)`: the absolute changed fields in native ascending id order, tags kept.
     * Without a model each stat rises by its Grow times the levels gained; with a resolved model the stats are
     * recomputed at the new level. Only changed fields are transmitted.
     */
    fun grownFields(before: Map<Long, JObj>, settlement: JObj, model: JObj? = null): JArr {
        val gained = settlement.long("levels_gained")
        val after = LinkedHashMap<Long, BigInteger>()
        after[LEVEL] = bitsOf(before.getValue(LEVEL)) + BigInteger.valueOf(gained)
        after[EXP] = settlement.int("residual")
        if (model == null) {
            for ((statId, growId) in STAT_IDS.zip(GROW_IDS)) {
                after[statId] = bitsOf(before.getValue(statId)) + bitsOf(before.getValue(growId)) * BigInteger.valueOf(gained)
            }
        } else {
            for ((statId, value) in STAT_IDS.zip(HeroStats.statsAt(model, after.getValue(LEVEL)))) after[statId] = value
        }
        val changed = JArr()
        for (fieldId in TRANSMITTED_ORDER) {
            val value = after.getValue(fieldId)
            if (value == bitsOf(before.getValue(fieldId))) continue
            val tag = tagOf(before.getValue(fieldId))
            if (value >= BigInteger.ONE.shiftLeft(widthOf(tag))) throw FortifyRejected("Hero field $fieldId would overflow its wire width")
            changed.add(jobj("id" to fieldId, "value" to jobj("tag" to tag, "bits" to value)))
        }
        return changed
    }

    private fun bitsOf(value: JObj): BigInteger = (value["bits"] as JInt).value

    /** `hero_grow_row(uid, awarded, before, changed)`: UID, total awarded EXP, level delta, four stat deltas, tail. */
    fun heroGrowRow(uid: Long, awarded: Long, before: Map<Long, JObj>, changed: JArr): JArr {
        val after = changed.associate { it.asObj.long("id") to it.asObj.obj("value").int("bits") }
        val deltas = (listOf(LEVEL) + STAT_IDS).map { (after[it] ?: bitsOf(before.getValue(it))) - bitsOf(before.getValue(it)) }
        return jarr(uid, awarded, *deltas.toTypedArray(), *HERO_GROW_TAIL.toTypedArray())
    }

    // --- hero planner --------------------------------------------------------------------------------------------

    private fun checkMaterials(materials: List<Long>, owned: Set<Long>, benchOrBag: List<Long>, deployedUids: Collection<Long>,
                               excludedUids: Collection<Long>, notFoundCode: Int) {
        if (materials.isEmpty()) {
            throw FortifyRejected("Fortify request names no material", if (notFoundCode == ERROR_MATERIALS_NOT_FOUND) ERROR_NO_MATERIALS else ERROR_GEAR_NO_MATERIALS)
        }
        if (materials.size > 255) throw FortifyRejected("Fortify request names more than 255 materials")
        if (materials.toSet().size != materials.size) throw FortifyRejected("Duplicate material in Fortify request", notFoundCode)
        val deployed = deployedUids.toSet()
        val excluded = excludedUids.toSet()
        for (uid in materials) {
            if (uid !in owned) throw FortifyRejected("Material is not owned", notFoundCode)
            if (uid !in benchOrBag || uid in deployed || uid in excluded) {
                throw FortifyRejected("Material must be offline/in the bag and outside deployment/assignment", notFoundCode)
            }
        }
    }

    private fun valuesOf(resolved: JObj): LinkedHashMap<Long, JObj> =
        LinkedHashMap<Long, JObj>().also { m -> resolved.obj("values").forEach { (k, v) -> m[k.toLong()] = v.asObj } }

    /**
     * `plan_hero_fortify(request, heroes, bench, deployed_uids, excluded_uids, gold, inputs)`: validate one decoded C69
     * request against an owned view; return the mutation. Materials: one or more distinct bench heroes at level 1 with
     * EXP 0. Awarded EXP is the sum of the per-material client EXP (no bonus), the Gold cost uses that pre-bonus sum.
     * Raises [FortifyRejected] without side effects.
     */
    fun planHeroFortify(request: JObj, heroes: Map<Long, JArr>, bench: List<Long>, deployedUids: Collection<Long>,
                        excludedUids: Collection<Long>, gold: BigInteger, inputs: JObj?): JObj {
        val targetUid = request.long("target_uid")
        val materials = request.arr("material_uids").map { it.long }
        if (materials.isEmpty()) throw FortifyRejected("Fortify request names no material", ERROR_NO_MATERIALS)
        if (targetUid in materials) throw FortifyRejected("Target cannot also be a material", ERROR_MATERIALS_NOT_FOUND)
        if (targetUid !in heroes) throw FortifyRejected("Target hero is not owned")
        checkMaterials(materials, heroes.keys, bench, deployedUids, excludedUids, ERROR_MATERIALS_NOT_FOUND)
        if (targetUid in excludedUids.toSet()) throw FortifyRejected("Target is assigned to exploration/mining")
        if (inputs == null) throw FortifyRejected("Catalog inputs are unavailable for the requested templates")
        val resolved = resolveTargetProfile(heroes.getValue(targetUid), inputs.obj("target"))
        val target = valuesOf(resolved)
        val materialValues = LinkedHashMap<Long, LinkedHashMap<Long, JObj>>()
        for (uid in materials) materialValues[uid] = checkSupportedProfile(heroes.getValue(uid))
        for ((_, values) in materialValues) {
            if (bitsOf(values.getValue(LEVEL)).toLong() != 1L || bitsOf(values.getValue(EXP)).signum() != 0) {
                // Retention of a levelled material's invested EXP is native loading only; that branch stays excluded.
                throw FortifyRejected("Only level-1 materials with no residual EXP are evidenced", ERROR_MATERIALS_NOT_FOUND)
            }
        }
        val targetConfig = inputs.obj("target")
        if (JInt(bitsOf(target.getValue(TEMPLATE))) != targetConfig["template"]) {
            throw FortifyRejected("Catalog inputs do not belong to the requested target template")
        }
        val byTemplate = LinkedHashMap<Long, JObj>()
        for (m in inputs.arr("materials")) byTemplate[m.asObj.long("template")] = m.asObj
        val awardedRecords = JArr()
        for (uid in materials) {
            val template = bitsOf(materialValues.getValue(uid).getValue(TEMPLATE)).toLong()
            val config = byTemplate[template] ?: throw FortifyRejected("Catalog inputs do not belong to a requested material template")
            val awarded = HeroDictionaryProgression.consumedHeroExp(config.long("base_exp_134"), 0, 1, config.long("cap"),
                config.long("retention_404"), config.long("scale_137"), emptyList())
            awardedRecords.add(jobj("uid" to uid, "template" to template, "awarded" to awarded["value"], "multiplier" to awarded["multiplier"]))
        }
        val totalAwarded = awardedRecords.sumOf { it.asObj.long("awarded") }
        if (totalAwarded > U32) throw FortifyRejected("Summed material EXP exceeds uint32")
        val targetLevel = bitsOf(target.getValue(LEVEL)).toLong()
        val cost = HeroDictionaryProgression.upgradeInstanceCost(totalAwarded, targetLevel, targetConfig.str("property_410_raw"))
        if (cost < 0) throw FortifyRejected("Negative Gold cost is outside the evidenced domain")
        if (gold < BigInteger.valueOf(cost)) throw FortifyRejected("Insufficient Gold for the Fortify cost")
        val settlement = settleHeroExp(targetLevel, bitsOf(target.getValue(EXP)).toLong(), totalAwarded,
            targetConfig.long("cap"), targetConfig.obj("heroexp"), targetConfig.long("scale_137"))
        if (settlement.bool("reached_cap")) throw FortifyRejected("Settlement would reach the configured cap; hero cap-excess behavior is unresolved")
        val changed = grownFields(target, settlement, resolved)
        if (changed.isEmpty()) throw FortifyRejected("Fortify would change nothing")
        val changedById = changed.associate { it.asObj.long("id") to it.asObj.obj("value")["bits"]!! }
        val afterFields = JArr(heroes.getValue(targetUid).mapTo(ArrayList<JValue>()) { f ->
            val field = f.asObj
            val value = JObj(LinkedHashMap(field.obj("value").map))
            changedById[field.long("id")]?.let { value["bits"] = it }
            jobj("id" to field["id"], "value" to value)
        })
        val reward = emptyReward(14)
        reward["hero_grow"] = jarr(heroGrowRow(targetUid, totalAwarded, target, changed))
        val result = jobj("records" to JArr(awardedRecords.mapTo(ArrayList<JValue>()) { r ->
            jobj("field_a_u32_raw" to r.asObj["template"], "field_b_i8_raw" to NO_BONUS_B, "field_c_u32_raw" to r.asObj["awarded"],
                "field_d_u8_raw" to OBSERVED_PREFIX_D)
        }), "reward" to reward)
        val removed = materials.toSet()
        val newBench = bench.filter { it !in removed }
        val removedMaterials = JObj()
        for (uid in materials) removedMaterials[uid.toString()] = heroes.getValue(uid)
        return jobj("kind" to "hero", "target_uid" to targetUid, "material_uids" to materials, "material_uid" to materials[0],
            "profile" to resolved["profile"], "profile_components" to resolved["components"],
            "profile_evidence_class" to resolved["evidence_class"], "stat_permille" to resolved["permille"],
            "full_grow" to resolved["full_grow"],
            "before_target" to heroes.getValue(targetUid), "after_target" to afterFields, "changed_fields" to changed,
            "removed_materials" to removedMaterials, "removed_material" to heroes.getValue(materials[0]),
            "awarded_exp" to totalAwarded, "awarded_records" to awardedRecords,
            "material_multiplier" to awardedRecords[0].asObj["multiplier"], "gold_cost" to cost,
            "gold_after" to (gold - BigInteger.valueOf(cost)), "settlement" to settlement, "new_bench" to newBench, "result" to result)
    }

    /**
     * `hero_fortify_packets(plan, activity_payload, gold_payload)`: 46, 1184, 48, 40, 34, 128. The activity list is
     * regenerated from owned state by the caller; removal lists follow the request order.
     */
    fun heroFortifyPackets(plan: JObj, activityPayload: ByteArray, goldPayload: ByteArray): List<Frame> {
        val materials = plan.arr("material_uids").map { it.long }
        return listOf(HERO_UPDATE_OPCODE to heroPropertyUpdatePayload(plan.long("target_uid"), plan.arr("changed_fields")),
            ACTIVITY_LIST_OPCODE to activityPayload,
            RESULT_OPCODE to encodeUpgradeResult(plan.obj("result")),
            BENCH_REMOVE_OPCODE to countedUidPayload(materials),
            HERO_REMOVE_OPCODE to countedUidPayload(materials),
            ROLE_UPDATE_OPCODE to goldPayload)
    }

    // --- equipment planner -----------------------------------------------------------------------------------------

    /** `check_equipment_profile(wire_values)`: the flag byte and extra u32 zero (every observation). */
    fun checkEquipmentProfile(wireValues: JArr): List<Long> {
        if (wireValues.size != 7 || wireValues.any { it !is JInt }) throw FortifyRejected("Equipment record is malformed")
        val values = wireValues.map { it.long }
        if (values[E_UID] == 0L || values[E_TEMPLATE] == 0L) throw FortifyRejected("Equipment identity fields must be nonzero")
        if (values[E_LEVEL] < 1) throw FortifyRejected("Equipment level must be positive")
        if (values[E_FLAG] != 0L || values[E_EXTRA] != 0L) throw FortifyRejected("Equipment flag/extra fields are nonzero; profile not evidenced")
        return values
    }

    /**
     * `plan_equipment_fortify(request, equipment, bag, equipped_uids, gold, inputs)`: validate one decoded C81 request.
     * Materials are distinct bag items at level 1 with EXP 0; the target may be in the bag or equipped. The Gold cost
     * uses the summed material EXP; S128 only when the cost is positive.
     */
    fun planEquipmentFortify(request: JObj, equipment: Map<Long, JArr>, bag: List<Long>, equippedUids: Collection<Long>,
                             gold: BigInteger, inputs: JObj?): JObj {
        val targetUid = request.long("target_uid")
        val materials = request.arr("material_uids").map { it.long }
        if (materials.isEmpty()) throw FortifyRejected("Gear Fortify request names no material", ERROR_GEAR_NO_MATERIALS)
        if (targetUid in materials) throw FortifyRejected("Target cannot also be a material", ERROR_MATERIALS_NOT_FOUND)
        if (targetUid !in equipment) throw FortifyRejected("Target equipment is not owned")
        val target = checkEquipmentProfile(equipment.getValue(targetUid))
        checkMaterials(materials, equipment.keys, bag, equippedUids, emptyList(), ERROR_MATERIALS_NOT_FOUND)
        if (inputs == null) throw FortifyRejected("Catalog inputs are unavailable for the requested templates")
        val materialValues = LinkedHashMap<Long, List<Long>>()
        for (uid in materials) materialValues[uid] = checkEquipmentProfile(equipment.getValue(uid))
        for ((_, values) in materialValues) {
            if (values[E_LEVEL] != 1L || values[E_EXP] != 0L) throw FortifyRejected("Only level-1 gear materials with no residual EXP are evidenced", ERROR_MATERIALS_NOT_FOUND)
        }
        val targetConfig = inputs.obj("target")
        if (target[E_TEMPLATE] != targetConfig.long("template") || target[E_GRADE] != targetConfig.long("grade")) {
            throw FortifyRejected("Catalog inputs do not belong to the requested target template/grade")
        }
        val byKey = LinkedHashMap<Pair<Long, Long>, JObj>()
        for (m in inputs.arr("materials")) byKey[m.asObj.long("template") to m.asObj.long("grade")] = m.asObj
        val awardedRecords = JArr()
        for (uid in materials) {
            val values = materialValues.getValue(uid)
            val config = byKey[values[E_TEMPLATE] to values[E_GRADE]]
                ?: throw FortifyRejected("Catalog inputs do not belong to a requested material template/grade")
            val awarded = HeroDictionaryProgression.consumedEquipmentExp(config.long("base_exp_112"), 0, 1, config.long("cap"),
                config.long("retention_304"), emptyList())
            awardedRecords.add(jobj("uid" to uid, "template" to values[E_TEMPLATE], "awarded" to awarded["value"], "multiplier" to awarded["multiplier"]))
        }
        val totalAwarded = awardedRecords.sumOf { it.asObj.long("awarded") }
        if (totalAwarded > U32) throw FortifyRejected("Summed material EXP exceeds uint32")
        val cost = HeroDictionaryProgression.upgradeInstanceCost(totalAwarded, target[E_LEVEL], targetConfig.str("property_411_raw"))
        if (cost < 0) throw FortifyRejected("Negative Gold cost is outside the evidenced domain")
        if (gold < BigInteger.valueOf(cost)) throw FortifyRejected("Insufficient Gold for the gear Fortify cost")
        val settlement = settleEquipmentExp(target[E_LEVEL], target[E_EXP], totalAwarded, targetConfig.long("cap"),
            targetConfig.obj("equipexp"), targetConfig.long("scale_113"))
        if (settlement.long("levels_gained") == 0L && settlement.long("residual") == target[E_EXP]) throw FortifyRejected("Gear Fortify would change nothing")
        val after = target.toMutableList()
        after[E_LEVEL] = settlement.long("level")
        after[E_EXP] = settlement.long("residual")
        val reward = emptyReward(14)
        reward["equip_grow"] = jarr(jarr(targetUid, totalAwarded, settlement["levels_gained"]))
        val result = jobj("records" to JArr(awardedRecords.mapTo(ArrayList<JValue>()) { r ->
            jobj("field_a_u32_raw" to r.asObj["template"], "field_b_i8_raw" to NO_BONUS_B, "field_c_u32_raw" to r.asObj["awarded"],
                "field_d_u8_raw" to OBSERVED_PREFIX_D)
        }), "reward" to reward)
        val removed = materials.toSet()
        val removedMaterials = JObj()
        for (uid in materials) removedMaterials[uid.toString()] = equipment.getValue(uid)
        return jobj("kind" to "equipment", "target_uid" to targetUid, "material_uids" to materials,
            "before_target" to target, "after_target" to after, "removed_materials" to removedMaterials,
            "awarded_exp" to totalAwarded, "awarded_records" to awardedRecords, "gold_cost" to cost,
            "gold_after" to (gold - BigInteger.valueOf(cost)), "settlement" to settlement,
            "new_bag" to bag.filter { it !in removed }, "result" to result)
    }

    /** `equipment_fortify_packets(plan, gold_payload)`: 106, 108, 102, 98, then 128 only when Gold changed. */
    fun equipmentFortifyPackets(plan: JObj, goldPayload: ByteArray): List<Frame> {
        val materials = plan.arr("material_uids").map { it.long }
        val packets = mutableListOf(EQUIP_UPDATE_OPCODE to equipmentRecordPayload(plan.arr("after_target").map { it.long }),
            GEAR_RESULT_OPCODE to encodeUpgradeResult(plan.obj("result")),
            EQUIP_BAG_REMOVE_OPCODE to countedUidPayload(materials),
            EQUIP_REMOVE_OPCODE to countedUidPayload(materials))
        if (plan.long("gold_cost") > 0) packets.add(ROLE_UPDATE_OPCODE to goldPayload)
        return packets
    }
}

/**
 * The lazy read-only catalog loader of hero / gear Fortify (`FortifyInputs`): `invoke(target, materials)` gives
 * `pk_fortify_contract.catalog_inputs`, [equipment] gives `equipment_inputs`; results are cached per key.
 */
class FortifyInputs(val tables: GameTables) {
    private val cache = HashMap<List<Any>, JObj>()

    @Synchronized
    operator fun invoke(targetTemplate: Long, materialTemplates: List<Long>): JObj =
        cache.getOrPut(listOf("hero", targetTemplate, materialTemplates)) { PkFortifyContract.catalogInputs(tables, targetTemplate, materialTemplates) }

    /** Keys are (template, grade) pairs, as read from the equipment records. */
    @Synchronized
    fun equipment(target: Pair<Long, Long>, materials: List<Pair<Long, Long>>): JObj =
        cache.getOrPut(listOf("equipment", target, materials)) { PkFortifyContract.equipmentInputs(tables, target, materials) }
}
