package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JBool
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JNull
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.asArr
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.protocol.BattleReport
import io.github.okexodus.openknights.protocol.TypedValues
import io.github.okexodus.openknights.protocol.WireReader
import io.github.okexodus.openknights.protocol.WireWriter
import io.github.okexodus.openknights.server.game.HeroDictionaryProgression.unpackHeroId
import java.math.BigInteger

/**
 * Ordinary (C71) and leader (C2083) hero evolution (`hero_evolution.py`): codecs, arithmetic, order. Two separate
 * transactions, never merged.
 *
 * - C71 = u32 owned hero UID; S50 = u32 UID, the hero's complete field map, then a Reward.
 * - C2083 = empty; the reply is S46 (changed fields only), S2240 (u32 A, u32 B = progress), S2242 (a bare Reward).
 * - new grade = old grade + 1 (template = base × 1000 + digit × 100 + grade); growth and stats through the shared
 *   bounded stat model of [HeroStats]; level and EXP unchanged.
 * - materials and Gold from the config row of the CURRENT grade (201/202, 203/204, the third slot 301-303 × 304 under
 *   the labeled test policy; 401 Gold); a stack consumed whole is S66, else S68 with the new count.
 *
 * The planners validate a request against an owned view and return the exact mutation; the store persists it.
 */
object HeroEvolution {
    const val ORDINARY_REQUEST_OPCODE = 71
    const val LEADER_REQUEST_OPCODE = 2083
    const val ORDINARY_RESULT_OPCODE = 50
    const val HERO_UPDATE_OPCODE = 46
    const val LEADER_INFO_OPCODE = 2240
    const val LEADER_SUCCESS_OPCODE = 2242
    const val ITEM_UPDATE_OPCODE = 68
    const val ITEM_REMOVE_OPCODE = 66
    const val ROLE_UPDATE_OPCODE = 128
    const val ACTIVITY_LIST_OPCODE = 1184

    const val UID = 0L
    const val TEMPLATE = 1L
    const val LEVEL = 2L
    const val EXP = 3L
    val STAT_IDS = listOf(4L, 6L, 8L, 10L)
    val GROW_IDS = listOf(5L, 7L, 9L, 11L)
    const val U32 = 0xFFFFFFFFL

    const val ERROR_INVALID = 102
    const val ERROR_LEVEL_REQUIRED = 1009
    const val ERROR_GUIDE_HERO = 1014
    const val ERROR_NO_QUALIFIED = 1010
    const val ERROR_MAX_TIER = 69685
    const val ERROR_REBORN_NO_EVOLVE = 69684
    const val ERROR_INSUFFICIENT = 69686

    /** Reward HeroGrow components 8-11: zero in every retained row. */
    val HERO_GROW_TAIL = listOf(0L, 0L, 0L, 0L)
    const val TEST_POLICY_PROFILE = "evolution_extended_tiers_test_policy_v1"

    open class EvolutionRejected(message: String, val code: Int = ERROR_INVALID) : IllegalArgumentException(message)

    private val REJECT = HeroStats.Reject { m, c -> EvolutionRejected(m, c) }

    private fun uint(value: Long, width: Int, label: String): Long {
        if (value < 0 || value >= (1L shl width)) throw PyValues.ValueError("$label must fit uint$width")
        return value
    }

    // --- wire codecs ---------------------------------------------------------------------------------------------------

    /** C71: u32 owned hero UID. */
    fun decodeOrdinaryRequest(payload: ByteArray): JObj {
        val reader = WireReader(payload)
        val uid = reader.u32()
        if (reader.offset != payload.size) throw PyValues.ValueError("Ordinary evolution request has trailing bytes")
        return jobj("target_uid" to uid)
    }

    fun encodeOrdinaryRequest(value: JObj): ByteArray = WireWriter().u32(uint(value.long("target_uid"), 32, "Target UID")).bytes()

    /** C2083: empty. The client identifies the leader from its own state. */
    fun decodeLeaderRequest(payload: ByteArray): JObj {
        if (payload.isNotEmpty()) throw PyValues.ValueError("Leader evolution request carries no payload")
        return JObj()
    }

    /** S50: u32 UID, the full hero field map, Reward (byte-exact reproduction). */
    fun encodeAdvanceResult(value: JObj): ByteArray {
        val w = WireWriter().u32(uint(value.long("uid"), 32, "Owned hero UID"))
        TypedValues.encodeFields(value.arr("fields"), w)
        BattleReport.encodeReward(w, value.obj("reward"))
        return w.bytes()
    }

    /** S46: u32 owned UID then the absolute changed typed fields. */
    fun heroPropertyUpdatePayload(uid: Long, fields: JArr): ByteArray = HeroFortify.heroPropertyUpdatePayload(uid, fields)

    /** S2240 A != 0 branch: u32 A (the leader's owned uid), u32 B (its progress key). Eight bytes. */
    fun leaderInfoPayload(fieldA: Long, fieldB: Long): ByteArray =
        WireWriter().u32(uint(fieldA, 32, "Leader field A")).u32(uint(fieldB, 32, "Leader field B")).bytes()

    /** S2242: a bare Reward. */
    fun leaderSuccessPayload(reward: JObj): ByteArray = BattleReport.encodeReward(reward)

    fun emptyReward(version: Int = 14): JObj = BattleReport.emptyReward(version)

    // --- owned-hero field access -----------------------------------------------------------------------------------

    /**
     * One owned hero through the shared bounded stat model: guiding heroes are refused with 1014, a nonzero GrowType
     * and any hero the model cannot reproduce with 102, before any mutation.
     */
    fun resolveTarget(fields: JArr, inputs: JObj?): JObj = HeroStats.resolveProfile(fields, inputs, REJECT, ERROR_GUIDE_HERO)

    // --- bounded arithmetic ----------------------------------------------------------------------------------------

    /**
     * The packed grade + 1, keeping the packed hundreds digit (525001 → 525002, 621117 → 621118). A digit the
     * client's modulo would change (>= 3) and grade 99 have no successor (69685).
     */
    fun evolveTemplate(template: Long): Long {
        val old = unpackHeroId(template)
        if (old.hundredsDigit % 3 != old.hundredsDigit) throw EvolutionRejected("Packed hundreds digit outside the observed 0..2 range is unsupported", ERROR_MAX_TIER)
        if (old.grade + 1 > 99) throw EvolutionRejected("Packed grade has no successor (grade 99)", ERROR_MAX_TIER)
        val newTemplate = old.baseId * 1000 + old.hundredsDigit * 100 + (old.grade + 1)
        val new = unpackHeroId(newTemplate)
        if (new.hundredsDigit != old.hundredsDigit || new.baseId != old.baseId) {
            throw EvolutionRejected("Packed template increment changed the base or digit; unsupported", ERROR_MAX_TIER)
        }
        return newTemplate
    }

    /** Zero-profile form: new_stat[i] = grow[i] × (level + new_grade + 3), 32-bit. */
    fun evolvedStats(grow: List<Long>, level: Long, newGrade: Long): List<Long> {
        val factor = BigInteger.valueOf(level + newGrade + 3)
        return grow.map { (BigInteger.valueOf(it) * factor).and(BigInteger.valueOf(U32)).toLong() }
    }

    // --- planners ------------------------------------------------------------------------------------------------

    /** One consumed material stack (`_consume_items` entry minus its packet) and its S66 / S68 frame. */
    class ItemChange(val uid: Long, val template: Long, val quantity: Long, val remaining: Long, val packet: Frame) {
        fun toJson(): JObj = jobj("uid" to uid, "template" to template, "quantity" to quantity, "remaining" to remaining)
    }

    /** The S66 (stack emptied) or S68 (new count) frame of one consumed stack. */
    fun stackPacket(uid: Long, remaining: Long): Frame =
        if (remaining == 0L) ITEM_REMOVE_OPCODE to TransactionPackets.itemRemovePayload(uid)
        else ITEM_UPDATE_OPCODE to TransactionPackets.itemCountPayload(uid, remaining)

    /**
     * `_consume_items(materials, items)`: per material (template, quantity > 0) its one owned stack; a missing or split
     * stack or a short count is refused with 69686. `items` is the store's view {uid: {"wire_values": [uid, template,
     * count]}}.
     */
    fun consumeItems(materials: List<Pair<Long, Long>>, items: Map<Long, JObj>): List<ItemChange> {
        val byTemplate = LinkedHashMap<Long, MutableList<Long>>()
        for ((uid, record) in items) byTemplate.getOrPut(record.arr("wire_values")[1].long) { ArrayList() }.add(uid)
        val changes = ArrayList<ItemChange>()
        for ((template, quantity) in materials) {
            if (quantity <= 0) continue
            val uids = byTemplate[template]
            if (uids == null || uids.size != 1) throw EvolutionRejected("Evolution material item is missing or not a single stack", ERROR_INSUFFICIENT)
            val uid = uids[0]
            val owned = items.getValue(uid).arr("wire_values")[2].long
            if (owned < quantity) throw EvolutionRejected("Insufficient evolution materials", ERROR_INSUFFICIENT)
            val remaining = owned - quantity
            changes.add(ItemChange(uid, template, quantity, remaining, stackPacket(uid, remaining)))
        }
        return changes
    }

    /**
     * `check_test_policy(test_policy)`: only the explicitly labeled local TEST policy document (or null) is accepted;
     * the loaded policy is returned unchanged. The service checks the loaded `evolution` policy with it at start.
     */
    fun checkTestPolicy(testPolicy: JValue?): JValue? {
        if (testPolicy == null || testPolicy == JNull) return null
        val doc = if (testPolicy is JObj) (testPolicy["document"] ?: testPolicy) else null
        if (doc !is JObj || (doc["profile"] as? JStr)?.value != TEST_POLICY_PROFILE || (doc["class"] as? JStr)?.value != "preservation_policy_test") {
            throw EvolutionRejected("Evolution test policy document is not the labeled extended-tiers profile")
        }
        return testPolicy
    }

    private fun bitsOf(value: JObj): BigInteger = (value.getValue("bits") as JInt).value

    private fun width(tag: Long): Int = when (TypedValues.WIDTH_FORMAT.getValue(tag.toInt())) { 'B' -> 8; 'H' -> 16; 'I' -> 32; else -> 64 }

    private fun longs(value: JValue?): List<Long> = (value as JArr).map { it.long }

    private fun bigs(value: JValue?): List<BigInteger> = (value as JArr).map { (it as JInt).value }

    /** `d.get(key) or 0` of an integer member. */
    private fun intOr0(d: JObj, key: String): BigInteger = (d[key] as? JInt)?.value ?: BigInteger.ZERO

    /**
     * Shared arithmetic and validation of both evolution branches (`_plan_common`). Without the test policy only the
     * evidenced branch (tier 1→2 shape, two material slots) is planned; with it the third material slot, higher tiers,
     * the star-up / super-up branches and the displayed level gates apply as local policy. `resolved` is the stat
     * model's resolution of the target. [Plan.packets] holds the material frames, in material order.
     */
    fun planCommon(targetUid: Long, resolved: JObj, gold: BigInteger?, items: Map<Long, JObj>, inputs: JObj?, leader: Boolean,
                   testPolicy: JValue? = null, roleLevel: BigInteger? = null): Plan {
        if (inputs == null) throw EvolutionRejected("Catalog inputs are unavailable for the requested hero")
        val extended = checkTestPolicy(testPolicy) != null
        val values = resolved.obj("values")
        val template = bitsOf(values.obj(TEMPLATE.toString()))
        val level = bitsOf(values.obj(LEVEL.toString()))
        if (JInt(template) != inputs["template"]) throw EvolutionRejected("Catalog inputs do not belong to the requested hero template")
        if (inputs["is_leader"] != JBool(leader)) throw EvolutionRejected("Hero leader class disagrees with the requested evolution path")
        val rowValue = inputs["current_grade_row"]
        if (rowValue == null || rowValue == JNull) throw EvolutionRejected("Hero is already at its maximum configured tier", ERROR_MAX_TIER)
        val row = rowValue as JObj
        val materials = row.arr("materials").map { it.asArr[0].long to it.asArr[1].long }.toMutableList()
        if (row.long("third_slot_quantity") != 0L) {
            if (!extended) throw EvolutionRejected("Evolution requires the unobserved third material slot", ERROR_INSUFFICIENT)
            val thirdItem = intOr0(row, "third_slot_item").toLong()
            if (thirdItem == 0L) throw EvolutionRejected("Third material slot has no class-selected item", ERROR_INSUFFICIENT)
            materials.add(thirdItem to row.long("third_slot_quantity"))
        }
        var starUp: JObj? = null
        if (extended) {
            // The new grade must have its own config row (cap); otherwise the hero is at the last configured tier —
            // unless the star-up branch applies (hero field 144 names the next-star base). Level gates mirror the
            // client display.
            if (!Py.truthy(inputs["next_grade_row_exists"] ?: JBool(true))) {
                starUp = inputs["star_up"].takeIf { Py.truthy(it) } as JObj?
                    ?: throw EvolutionRejected("No configured row for the next tier", ERROR_MAX_TIER)
                if (starUp["is_leader"] != JBool(leader)) throw EvolutionRejected("Next-star base changes the leader class; unsupported", ERROR_MAX_TIER)
            }
            val needHero = intOr0(row, "hero_level_requirement_501")
            if (level < needHero) throw EvolutionRejected("Hero level below the configured evolution requirement", ERROR_LEVEL_REQUIRED)
            val needRole = intOr0(row, "role_level_requirement")
            if (needRole.signum() != 0 && (roleLevel == null || roleLevel < needRole)) {
                throw EvolutionRejected("Role level below the configured evolution requirement", ERROR_LEVEL_REQUIRED)
            }
        }
        val superUp = if (leader) (inputs["super_up"].takeIf { Py.truthy(it) } as JObj?) else null
        if (superUp != null && !extended) throw EvolutionRejected("Leader super-evolution requires the labeled extended-tiers policy", ERROR_MAX_TIER)
        val awaken = resolved.obj("profile").int("awaken")
        val newTemplate: Long
        val newGrade: Long
        val grow: List<Long>
        val permille: List<Long>
        if (superUp != null) {
            // SUPER-EVOLUTION (leader, TEST policy): the packed hundreds digit rises and the grade stays; growth keeps
            // the current grade's potential, the new digit adds the property-533 factor.
            newTemplate = superUp.long("template")
            newGrade = superUp.long("new_grade")
            grow = HeroStats.recomputeGrow(longs(inputs["raw_511_514"]), superUp.long("potential_rate"))
            permille = HeroStats.statPermille(newTemplate, superUp.obj("stat_inputs"), awaken, REJECT)
        } else if (starUp != null) {
            // STAR-UP (TEST policy): new base at grade 1, digit 0; growth is the new base's raw 511-514; the stat factor
            // takes the NEW template's digit and the NEW base's awaken slots at the unchanged awaken level.
            newTemplate = starUp.long("template")
            newGrade = 1
            grow = HeroStats.recomputeGrow(longs(starUp["raw_511_514"]), 0)
            permille = HeroStats.statPermille(newTemplate, starUp.obj("stat_inputs"), awaken, REJECT)
        } else {
            newTemplate = evolveTemplate(template.longValueExact())
            newGrade = unpackHeroId(newTemplate).grade
            grow = HeroStats.recomputeGrow(longs(inputs["raw_511_514"]), inputs.long("potential_rate"))
            permille = longs(resolved["permille"])
        }
        // Full-width growth at the new grade; the u16 wire field keeps the low bits.
        val growBig = grow.map { BigInteger.valueOf(it) }
        val wireGrow = HeroStats.wireGrowBits(growBig, resolved.arr("grow_widths").map { it.long.toInt() })
        val stats = HeroStats.baseStats(growBig, level, newGrade, permille, bigs(resolved["dev"]))
        val cost = row.int("gold")
        if (gold == null || gold < cost) throw EvolutionRejected("Insufficient Gold for the evolution cost", ERROR_INSUFFICIENT)
        val itemChanges = consumeItems(materials, items)
        // The updated absolute fields, preserving tags and order.
        val after = LinkedHashMap<Long, BigInteger>()
        after[TEMPLATE] = BigInteger.valueOf(newTemplate)
        for (i in STAT_IDS.indices) {
            if (i >= wireGrow.size || i >= stats.size) break
            after[GROW_IDS[i]] = wireGrow[i]
            after[STAT_IDS[i]] = stats[i]
        }
        val changed = JArr()
        for (fieldId in listOf(TEMPLATE) + (STAT_IDS + GROW_IDS).toSortedSet()) {
            val newValue = after.getValue(fieldId)
            val value = values.obj(fieldId.toString())
            if (newValue == bitsOf(value)) continue
            val tag = value.long("tag")
            if (newValue >= BigInteger.ONE.shiftLeft(width(tag))) throw EvolutionRejected("Hero field $fieldId would overflow its wire width")
            changed.add(jobj("id" to fieldId, "value" to jobj("tag" to tag, "bits" to newValue)))
        }
        if (changed.isEmpty()) throw EvolutionRejected("Evolution would change nothing")
        val statDeltas = STAT_IDS.map { after.getValue(it) - bitsOf(values.obj(it.toString())) }
        // HeroGrow components are u32 on the wire; a star-up can lower a stat, so a negative delta is carried as its
        // two's complement (the signed values are kept in the plan / history).
        val growRow = jarr(targetUid, 0, 0, *statDeltas.map { it.and(BigInteger.valueOf(U32)) }.toTypedArray(), *HERO_GROW_TAIL.toTypedArray())
        val reward = emptyReward(14)
        reward["hero_grow"] = jarr(growRow)
        val afterById = JObj().also { o -> after.forEach { (k, v) -> o[k.toString()] = JInt(v) } }
        val plan = Plan(jobj("target_uid" to targetUid, "old_template" to template, "new_template" to newTemplate,
            "new_grade" to newGrade, "level" to level, "grow" to grow, "wire_grow" to wireGrow, "stats" to stats,
            "changed_fields" to changed, "after_by_id" to afterById, "gold_cost" to cost, "gold_after" to gold - cost,
            "item_changes" to itemChanges.map { it.toJson() }, "reward" to reward, "stat_deltas_signed" to statDeltas,
            "super_up" to superUp?.let { jobj("template" to it["template"], "marker_604" to it["marker_604"], "progress_key" to it["leader_progress_key"]) },
            "evidence_class" to (if (superUp != null) "preservation_policy_test_super_up" else if (starUp != null) "preservation_policy_test_star_up"
                else if (extended) "preservation_policy_test" else "capture_observed_tier_1_to_2"),
            "star_up" to starUp?.let { jobj("next_base" to it["next_base"], "marker_604" to it["marker_604"], "new_cap_502" to it["cap_502"]) },
            "profile" to resolved["profile"], "profile_components" to resolved["components"],
            "profile_evidence_class" to resolved["evidence_class"], "stat_permille" to permille,
            "grow_recomputed_from_catalog" to resolved["grow_recomputed"],
            "third_slot_consumed" to (row.long("third_slot_quantity") != 0L && extended)),
            itemChanges.map { it.packet })
        return plan
    }

    /** The hero's field list with the changed fields' `bits` replaced (each entry rebuilt as `{"id", "value"}`). */
    fun afterFields(fields: JArr, changed: JArr): JArr {
        val byId = LinkedHashMap<JValue, JValue>()
        for (c in changed) byId[c.asObj.getValue("id")] = c.asObj.obj("value").getValue("bits")
        return JArr(fields.mapTo(ArrayList()) { f ->
            val field = f.asObj
            val value = JObj(LinkedHashMap(field.obj("value").map))
            byId[field.getValue("id")]?.let { value["bits"] = it }
            jobj("id" to field["id"], "value" to value)
        })
    }

    /** Validate one decoded C71 request against an owned view; return the mutation. */
    fun planOrdinaryEvolution(request: JObj, heroes: Map<Long, JArr>, gold: BigInteger?, items: Map<Long, JObj>, inputs: JObj?,
                              testPolicy: JValue? = null, roleLevel: BigInteger? = null): Plan {
        val targetUid = request.long("target_uid")
        val fields = heroes[targetUid] ?: throw EvolutionRejected("Target hero is not owned")
        val resolved = resolveTarget(fields, inputs)
        val plan = planCommon(targetUid, resolved, gold, items, inputs, leader = false, testPolicy = testPolicy, roleLevel = roleLevel)
        // The ordinary result S50 re-transmits the hero's complete field list.
        val afterFields = afterFields(fields, plan.data.arr("changed_fields"))
        plan["kind"] = "ordinary"
        plan["after_target"] = afterFields
        plan["result"] = jobj("uid" to targetUid, "fields" to afterFields, "reward" to plan["reward"])
        return plan
    }

    /**
     * Validate one decoded C2083 request against an owned view; return the mutation. The caller supplies the owned
     * leader (`leaderUid`); S2240 field B is the leader config key of the new grade (or the super-up / star-up key).
     */
    fun planLeaderEvolution(request: JObj, heroes: Map<Long, JArr>, leaderUid: Long, gold: BigInteger?, items: Map<Long, JObj>,
                            inputs: JObj?, testPolicy: JValue? = null, roleLevel: BigInteger? = null): Plan {
        val fields = heroes[leaderUid] ?: throw EvolutionRejected("Leader hero is not owned")
        val resolved = resolveTarget(fields, inputs)
        val plan = planCommon(leaderUid, resolved, gold, items, inputs, leader = true, testPolicy = testPolicy, roleLevel = roleLevel)
        var progress: JValue? = inputs!!["leader_next_progress"]
        if (Py.truthy(plan["super_up"])) progress = plan.data.obj("super_up")["progress_key"]
        else if (Py.truthy(plan["star_up"])) progress = (inputs["star_up"] as? JObj)?.get("leader_progress_key")
        if (progress == null || progress == JNull) throw EvolutionRejected("Leader next-tier progress is unknown; A==0 branch unsupported", ERROR_MAX_TIER)
        val afterFields = afterFields(fields, plan.data.arr("changed_fields"))
        plan["kind"] = "leader"
        plan["after_target"] = afterFields
        plan["leader_field_a"] = 1
        plan["leader_field_b"] = progress
        plan["leader_success_reward"] = plan["reward"]
        return plan
    }

    // --- packet builders -----------------------------------------------------------------------------------------

    /** Observed core order: 68/66 item changes, 50, 128, 1184 (the live side-effect frames are not generated). */
    fun ordinaryEvolutionPackets(plan: Plan, activityPayload: ByteArray, goldPayload: ByteArray): List<Frame> {
        val packets = ArrayList(plan.packets)
        packets.add(ORDINARY_RESULT_OPCODE to encodeAdvanceResult(plan.data.obj("result")))
        packets.add(ROLE_UPDATE_OPCODE to goldPayload)
        packets.add(ACTIVITY_LIST_OPCODE to activityPayload)
        return packets
    }

    /** Observed core order: 66/68 item changes, 46, 2240, 2242, 128, 1184. */
    fun leaderEvolutionPackets(plan: Plan, activityPayload: ByteArray, goldPayload: ByteArray): List<Frame> {
        val packets = ArrayList(plan.packets)
        packets.add(HERO_UPDATE_OPCODE to heroPropertyUpdatePayload(plan.data.long("target_uid"), plan.data.arr("changed_fields")))
        packets.add(LEADER_INFO_OPCODE to leaderInfoPayload(plan.data.long("leader_field_a"), plan.data.long("leader_field_b")))
        packets.add(LEADER_SUCCESS_OPCODE to leaderSuccessPayload(plan.data.obj("leader_success_reward")))
        packets.add(ROLE_UPDATE_OPCODE to goldPayload)
        packets.add(ACTIVITY_LIST_OPCODE to activityPayload)
        return packets
    }
}
