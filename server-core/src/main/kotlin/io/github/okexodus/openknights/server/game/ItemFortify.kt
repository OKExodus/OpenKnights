package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JNull
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.PyRandom
import io.github.okexodus.openknights.exact.asArr
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.hexBytes
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.toHexString
import io.github.okexodus.openknights.protocol.BattleReport
import io.github.okexodus.openknights.protocol.TypedValues
import io.github.okexodus.openknights.protocol.WireReader
import io.github.okexodus.openknights.protocol.WireWriter
import io.github.okexodus.openknights.server.Entropy
import java.math.BigInteger

/**
 * EXP-item Fortify (`item_fortify.py`): hero (C91), gear (C93) and jewelry (C2641). The "Items Fortification" button
 * consumes stack EXP items to raise a target's level (docs/ITEM_FORTIFY_CONTRACT.md).
 *
 * Request: u32 target_uid, u32 n_types, n x (u32 request template, u32 quantity); the template is a qianghua_itemexp
 * key (102 class 1/2/3, 103 the consumed item, 104 the EXP per item). Staged items are consumed in request order until
 * the level cap (level pinned, EXP 0, leftovers untouched); Gold = trunc(property / 10000 x staged base EXP x level
 * before) (GetUpgradeCost). Hero growth is unchanged; gear / jewelry frames carry only level + EXP.
 *
 * By default no bonus is generated. With the labeled local policy (`item_fortify_bonus_local_rng_policy_v1`,
 * `preservation_policy_local`) every consumed item rolls x1 / x2 / x4 / x10 from the document's odds with a
 * per-transaction seed recorded in the history, and — when `fill_to_cap` is set — a staged quantity equal to the
 * client's maximum may be topped up from the owned stack until the cap (odds and top-up are NOT recovered behaviour).
 *
 * A plan's frames are kept as `[opcode, payload hex]` (the reference's `(opcode, bytes)` tuples as its JSON shows them).
 */
object ItemFortify {
    // Request opcodes.
    const val HERO_REQUEST_OPCODE = 91
    const val GEAR_REQUEST_OPCODE = 93
    const val JEWELRY_REQUEST_OPCODE = 2641
    // State update opcodes.
    const val HERO_UPDATE_OPCODE = 46
    const val EQUIP_UPDATE_OPCODE = 106
    const val JEWELRY_UPDATE_OPCODE = 3080
    // Result-popup opcodes (the client awaits these to close the "Fortifying" dialog).
    const val HERO_RESULT_OPCODE = 52
    const val GEAR_RESULT_OPCODE = 116
    const val JEWELRY_RESULT_OPCODE = 3112
    const val ITEM_UPDATE_OPCODE = 68      // partial stack: count, uid, remaining
    const val ITEM_REMOVE_OPCODE = 66      // emptied stack: count, uid
    const val ROLE_UPDATE_OPCODE = 128
    const val ERROR_OPCODE = 6

    const val ERROR_INVALID = 102
    const val ERROR_MAX_LEVEL = 1008           // "The Hero already reached the top level"
    const val ERROR_GEAR_MAX_LEVEL = 6010
    const val ERROR_ITEM_MISSING = 2000
    const val ERROR_ITEM_WRONG_TYPE = 2001
    const val ERROR_ITEM_INSUFFICIENT = 2002

    /** Hero op-46 re-transmits the full field set 1..11 (template, level, EXP, four stat / grow pairs). */
    val HERO_FULL_FIELDS = (1L..11L).toList()
    private const val U32 = 0xFFFFFFFFL

    class ItemFortifyRejected(message: String, val code: Int = ERROR_INVALID) : IllegalArgumentException(message)

    private fun uint(value: Long, width: Int, label: String): Long {
        if (value < 0 || (width < 64 && value >= (1L shl width))) throw PyValues.ValueError("$label must fit uint$width")
        return value
    }

    /** A frame in a plan (`(opcode, bytes)` → `[opcode, hex]`) and back. */
    fun frameJson(frame: Frame): JArr = jarr(frame.first, frame.second.toHexString())
    fun frameOf(value: JValue): Frame = value.asArr[0].long.toInt() to (value.asArr[1] as JStr).value.hexBytes()

    // --- request codec -------------------------------------------------------------------------------------------

    /** `decode_item_fortify_request`: u32 target_uid, u32 n_types, n x (u32 request template, u32 quantity). */
    fun decodeItemFortifyRequest(payload: ByteArray): JObj {
        val reader = WireReader(payload)
        val target = reader.u32()
        val count = reader.u32()
        val staged = JArr()
        var i = 0L
        while (i < count) {
            val template = reader.u32()
            val quantity = reader.u32()
            staged.add(jarr(template, quantity))
            i++
        }
        if (reader.offset != payload.size) throw PyValues.ValueError("Item-Fortify request has trailing bytes")
        return jobj("target_uid" to target, "staged" to staged)
    }

    /** `encode_item_fortify_request`. */
    fun encodeItemFortifyRequest(value: JObj): ByteArray {
        val staged = value.arr("staged")
        val w = WireWriter().number('I', uint(value.long("target_uid"), 32, "Target UID")).number('I', staged.size.toLong())
        for (s in staged) w.number('I', uint(s.asArr[0].long, 32, "Request template")).number('I', uint(s.asArr[1].long, 32, "Quantity"))
        return w.bytes()
    }

    // --- labeled local bonus RNG policy ----------------------------------------------------------------------------

    const val BONUS_POLICY_PROFILE = "item_fortify_bonus_local_rng_policy_v1"
    const val BONUS_POLICY_CLASS = "preservation_policy_local"
    val BONUS_MULTIPLIERS = listOf(1L, 2L, 4L, 10L)

    /**
     * `check_bonus_policy(bonus_policy)`: only the explicitly labeled local RNG policy document (or its loaded shape
     * `{document, path, sha256, ...}`), or null. The service checks the loaded policy with this at start.
     */
    fun checkBonusPolicy(bonusPolicy: JObj?): JObj? {
        if (bonusPolicy == null) return null
        val doc = bonusPolicy["document"] ?: bonusPolicy
        if (doc !is JObj || doc.strOrNull("profile") != BONUS_POLICY_PROFILE || doc.strOrNull("class") != BONUS_POLICY_CLASS) {
            throw ItemFortifyRejected("Item-Fortify bonus policy document is not the labeled local RNG profile")
        }
        val odds = doc["odds_per_10000"] as? JObj ?: throw ItemFortifyRejected("Item-Fortify bonus policy needs odds_per_10000 {x2, x4, x10}")
        val values = listOf("x2", "x4", "x10").map { odds[it] ?: JInt(0) }
        if (values.any { it !is JInt || it.value.signum() < 0 } || values.sumOf { (it as JInt).value } > BigInteger.valueOf(10000)) {
            throw ItemFortifyRejected("Item-Fortify bonus odds must be non-negative integers per 10000 summing to at most 10000")
        }
        val fill = doc["fill_to_cap"]
        if (fill != null && fill != JNull && (fill !is JObj || fill["client_item_maximum"] !is JInt || (fill["client_item_maximum"] as JInt).value.signum() <= 0)) {
            throw ItemFortifyRejected("Item-Fortify fill_to_cap needs a positive integer client_item_maximum")
        }
        return bonusPolicy
    }

    private fun policyDocument(bonusPolicy: JObj): JObj = (bonusPolicy["document"] ?: bonusPolicy) as JObj

    /**
     * `bonus_roller(bonus_policy, seed)`: `random.Random(seed).randrange(10000)` per consumed item against the x10, x4 and
     * x2 bands in that order; everything else is x1. The same seed over the same request replays the committed tallies.
     */
    fun bonusRoller(bonusPolicy: JObj, seed: BigInteger): () -> Long {
        val odds = policyDocument(bonusPolicy).obj("odds_per_10000")
        val (x2, x4, x10) = listOf("x2", "x4", "x10").map { (odds[it] as? JInt)?.value?.toLong() ?: 0L }
        val rng = PyRandom.seeded(seed)
        return {
            val value = rng.randrange(10000)
            when {
                value < x10 -> 10L
                value < x10 + x4 -> 4L
                value < x10 + x4 + x2 -> 2L
                else -> 1L
            }
        }
    }

    private class BonusSetup(val roll: (() -> Long)?, val fillToCap: JObj?, val seed: BigInteger?)

    /** `_bonus_setup(bonus_policy, rng_seed)`: (roll, fill_to_cap, seed); the seed is drawn from entropy when none is given. */
    private fun bonusSetup(bonusPolicy: JObj?, rngSeed: BigInteger?): BonusSetup {
        if (checkBonusPolicy(bonusPolicy) == null) return BonusSetup(null, null, null)
        val seed = rngSeed ?: Entropy.current.randbits(64)
        return BonusSetup(bonusRoller(bonusPolicy!!, seed), policyDocument(bonusPolicy)["fill_to_cap"] as? JObj, seed)
    }

    // --- shared settlement ---------------------------------------------------------------------------------------

    /** `_exp_to_cap(level, exp, cap, requirement)`: the EXP needed to move (level, exp) up to `cap` (≤ 0 at / over it). */
    private fun expToCap(level: Long, exp: Long, cap: Long, requirement: (Long) -> Long): Long {
        var total = -exp
        var lvl = level
        while (lvl < cap) {
            val req = requirement(lvl)
            if (req <= 0) throw ItemFortifyRejected("Non-positive EXP requirement at level $lvl")
            total += req
            lvl++
        }
        return total
    }

    /** `_settle_below_cap(level, exp, cap, awarded, requirement)`: add EXP and level up without reaching the cap. */
    fun settleBelowCap(level: Long, exp: Long, cap: Long, awarded: Long, requirement: (Long) -> Long): Pair<Long, Long> {
        var remaining = exp + awarded
        var lvl = level
        while (lvl < cap) {
            val req = requirement(lvl)
            if (remaining < req) break
            remaining -= req
            lvl++
        }
        return lvl to remaining
    }

    /**
     * `_plan_consumption(staged_resolved, level, exp, cap, requirement, roll, fill_to_cap)`: consume the staged items in
     * order until the cap. Without `roll` every item grants its base EXP; with it each consumed item rolls a multiplier.
     * With `fill_to_cap` a staged quantity equal to the client's maximum may be topped up from the owned stack (at most
     * `max_items_per_action` items of a type); the Gold-cost base then covers the items taken beyond the staged count.
     * One action awards at most u32 EXP in total (the result frame's width). A target already at its cap is refused
     * with `maxLevelCode`: the hero text 1008 for heroes, the gear text 6010 for gear and jewelry records.
     */
    fun planConsumption(stagedResolved: List<JObj>, level: Long, exp: Long, cap: Long, requirement: (Long) -> Long,
                        roll: (() -> Long)? = null, fillToCap: JObj? = null, maxLevelCode: Int = ERROR_MAX_LEVEL): JObj {
        val need = expToCap(level, exp, cap, requirement)
        if (need <= 0) throw ItemFortifyRejected("The target already reached its configured level cap", maxLevelCode)
        val totalStagedBase = stagedResolved.fold(0L) { s, r -> Math.addExact(s, Math.multiplyExact(r.long("item_exp"), r.long("quantity"))) }
        var totalCostBase = totalStagedBase
        val consumed = ArrayList<JObj>()
        var totalAwarded = 0L
        var remainingNeed = need
        var reachedCap = false
        val clientMaximum = if (fillToCap != null && Py.truthy(fillToCap)) (fillToCap["client_item_maximum"] as? JInt)?.value?.toLong() else null
        val topUpLimit = if (fillToCap != null && Py.truthy(fillToCap)) ((fillToCap["max_items_per_action"] as? JInt)?.value?.toLong() ?: 50_000L) else null
        var wireBoundHit = false
        for (s in stagedResolved) {
            if (remainingNeed <= 0) break
            val budget = U32 - totalAwarded
            if (budget <= 0) { wireBoundHit = true; break }
            val itemExp = s.long("item_exp")
            val qty = s.long("quantity")
            val owned = s.long("owned_count")
            var available = qty
            val fillEligible = clientMaximum != null && qty == clientMaximum && owned > qty
            if (fillEligible) available = minOf(owned, maxOf(qty, topUpLimit!!))
            val tally = linkedMapOf(1L to 0L, 2L to 0L, 4L to 0L, 10L to 0L)
            var take: Long
            val awarded: Long
            if (roll == null) {
                if (itemExp == 0L) throw ArithmeticException("division by zero")
                val nToCap = Math.ceil(remainingNeed.toDouble() / itemExp.toDouble()).toLong()
                take = minOf(nToCap, available)
                if (take * itemExp > budget) {
                    take = Math.floorDiv(budget, itemExp)
                    wireBoundHit = true
                }
                reachedCap = nToCap <= take
                awarded = take * itemExp
                tally[1L] = take
            } else {
                val multipliers = ArrayList<Long>()
                var sum = 0L
                while (multipliers.size < available && sum < remainingNeed) {
                    val multiplier = roll()
                    if (sum + itemExp * multiplier > budget) { wireBoundHit = true; break }
                    multipliers.add(multiplier)
                    sum += itemExp * multiplier
                }
                awarded = sum
                reachedCap = awarded >= remainingNeed
                take = multipliers.size.toLong()
                for (m in multipliers) tally[m] = tally.getValue(m) + 1
            }
            if (take <= 0) { wireBoundHit = true; break }
            totalAwarded += awarded
            remainingNeed -= awarded
            val remainingStack = owned - take
            val toppedUp = maxOf(0L, take - qty)
            // The recovered cost base is every staged item (pre-bonus) even when the cap stops consumption early; the
            // fill-to-cap policy adds only the items actually taken beyond the staged count.
            totalCostBase += itemExp * toppedUp
            consumed.add(jobj("request_template" to s["request_template"], "item_id" to s["item_id"], "item_exp" to itemExp,
                "taken" to take, "owned_uid" to s["owned_uid"], "remaining_stack" to remainingStack, "awarded" to awarded,
                "tally" to JObj().also { t -> tally.forEach { (m, c) -> t[m.toString()] = JInt(c) } },
                "staged_quantity" to qty, "topped_up" to toppedUp))
            if (reachedCap) break
        }
        val (newLevel, newExp) = if (reachedCap) cap to 0L else settleBelowCap(level, exp, cap, totalAwarded, requirement)
        if (newLevel == level && newExp == exp) throw ItemFortifyRejected("Item Fortify would change nothing")
        val bonusTallies = JObj()
        for (c in consumed) bonusTallies[c.long("item_id").toString()] = c.obj("tally").deepCopy()
        val toppedUp = JObj()
        for (c in consumed) if (c.long("topped_up") != 0L) toppedUp[c.long("item_id").toString()] = c["topped_up"]!!
        return jobj("consumed" to consumed, "total_awarded" to totalAwarded, "total_staged_base" to totalStagedBase,
            "total_cost_base" to totalCostBase, "new_level" to newLevel, "new_exp" to newExp, "levels_gained" to (newLevel - level),
            "reached_cap" to reachedCap, "exp_to_cap" to need, "wire_bound_hit" to wireBoundHit,
            "bonus_tallies" to bonusTallies, "topped_up" to toppedUp)
    }

    /**
     * `_resolve_staged(staged, item_map, owned_by_item, expected_class)`: the request templates through qianghua_itemexp
     * (`item_map`) and the owned stacks (`owned_by_item[item id] = {uid, count}`, one stack per item id).
     */
    fun resolveStaged(staged: JArr, itemMap: Map<Long, JObj>, ownedByItem: Map<Long, JObj>, expectedClass: Long): List<JObj> {
        if (staged.isEmpty()) throw ItemFortifyRejected("Item Fortify request names no item", ERROR_ITEM_MISSING)
        val seen = HashSet<Long>()
        val resolved = ArrayList<JObj>()
        for (s in staged) {
            val template = s.asArr[0].long
            val quantity = s.asArr[1].long
            if (quantity <= 0) throw ItemFortifyRejected("Item quantity must be positive", ERROR_ITEM_INSUFFICIENT)
            if (template in seen) throw ItemFortifyRejected("Duplicate item type in request", ERROR_INVALID)
            seen.add(template)
            val info = itemMap[template] ?: throw ItemFortifyRejected("Unknown Fortify item template", ERROR_ITEM_MISSING)
            if (info.long("category") != expectedClass) throw ItemFortifyRejected("Item class does not match the Fortify target", ERROR_ITEM_WRONG_TYPE)
            val stack = ownedByItem[info.long("item_id")] ?: throw ItemFortifyRejected("Fortify item is not owned", ERROR_ITEM_MISSING)
            if (stack.long("count") < quantity) throw ItemFortifyRejected("Insufficient Fortify items", ERROR_ITEM_INSUFFICIENT)
            resolved.add(jobj("request_template" to template, "item_id" to info["item_id"], "item_exp" to info["exp"],
                "owned_uid" to stack["uid"], "owned_count" to stack["count"], "quantity" to quantity))
        }
        return resolved
    }

    /** `_item_changes(consumed)`: per consumed type, the S66 / S68 frame and the remaining count. */
    private fun itemChanges(consumed: JArr): JArr = JArr(consumed.mapTo(ArrayList<JValue>()) { cv ->
        val c = cv.asObj
        val remaining = c.long("remaining_stack")
        val uid = c.long("owned_uid")
        val packet: Frame = if (remaining == 0L) ITEM_REMOVE_OPCODE to TransactionPackets.itemRemovePayload(uid)
            else ITEM_UPDATE_OPCODE to TransactionPackets.itemCountPayload(uid, remaining)
        jobj("uid" to uid, "item_id" to c["item_id"], "taken" to c["taken"], "remaining" to remaining, "packet" to frameJson(packet))
    })

    private fun goldCost(totalStagedBase: Long, levelBefore: Long, propertyRaw: String): Long {
        val cost = HeroDictionaryProgression.upgradeInstanceCost(totalStagedBase, levelBefore, propertyRaw)
        if (cost < 0) throw ItemFortifyRejected("Negative Gold cost is outside the evidenced domain")
        return cost
    }

    /**
     * `item_fortify_result_payload(consumed, grow_key, grow_rows)`: the result popup (S52 / S116 / S3112 and the Rebirth
     * S56): u32 n, n x 25-byte records (u32 item id, u32 x1, x2, x4, x10 counts, u32 EXP, u8 flag), a v14 Reward with the
     * grow row(s).
     */
    fun itemFortifyResultPayload(consumed: JArr, growKey: String, growRows: JArr): ByteArray {
        val w = WireWriter().number('I', consumed.size.toLong())
        for (cv in consumed) {
            val c = cv.asObj
            val tally = (c["tally"] as? JObj)?.takeIf { Py.truthy(it) } ?: jobj("1" to c["taken"])
            fun count(m: String) = (tally[m] as? JInt)?.value?.toLong() ?: 0L
            w.number('I', uint(c.long("item_id"), 32, "Item id")).number('I', uint(count("1"), 32, "x1 count"))
                .number('I', uint(count("2"), 32, "x2 count")).number('I', uint(count("4"), 32, "x4 count"))
                .number('I', uint(count("10"), 32, "x10 count")).number('I', uint(c.long("awarded"), 32, "Awarded EXP")).number('B', 0)
        }
        val reward = BattleReport.emptyReward(14)
        reward[growKey] = growRows
        BattleReport.encodeReward(w, reward)
        return w.bytes()
    }

    private fun widthOf(tag: Int): Int = when (TypedValues.WIDTH_FORMAT.getValue(tag).uppercaseChar()) { 'B' -> 8; 'H' -> 16; 'I' -> 32; else -> 64 }

    // --- hero planner ----------------------------------------------------------------------------------------------

    /**
     * `plan_hero_item_fortify(request, heroes, item_map, owned_by_item, gold, inputs, bonus_policy, rng_seed)`: validate a
     * decoded C91 request against an owned view; return the mutation. The bonus seed is drawn first (when the policy
     * applies), before any check.
     */
    fun planHeroItemFortify(request: JObj, heroes: Map<Long, JArr>, itemMap: Map<Long, JObj>, ownedByItem: Map<Long, JObj>,
                            gold: BigInteger, inputs: JObj?, bonusPolicy: JObj? = null, rngSeed: BigInteger? = null): JObj {
        val setup = bonusSetup(bonusPolicy, rngSeed)
        val targetUid = request.long("target_uid")
        if (targetUid !in heroes) throw ItemFortifyRejected("Target hero is not owned")
        if (inputs == null) throw ItemFortifyRejected("Catalog inputs are unavailable for the requested hero")
        val model = try {
            HeroFortify.resolveTargetProfile(heroes.getValue(targetUid), inputs)
        } catch (e: IllegalArgumentException) {
            throw ItemFortifyRejected(e.message ?: "", codeOf(e))
        }
        val values = LinkedHashMap<Long, JObj>().also { m -> model.obj("values").forEach { (k, v) -> m[k.toLong()] = v.asObj } }
        fun bitsOf(id: Long): Long = (values.getValue(id)["bits"] as JInt).value.toLong()
        val template = bitsOf(1)
        if (JInt(template) != inputs["template"]) throw ItemFortifyRejected("Catalog inputs do not belong to the requested hero template")
        val levelBefore = bitsOf(2)
        val cap = inputs.long("cap")
        val scale = inputs.long("scale_137")
        val curve = inputs.obj("heroexp")
        val requirement = { lvl: Long ->
            val base = (curve[lvl.toString()] as? JInt)?.value?.toLong() ?: throw ItemFortifyRejected("No heroexp row for level $lvl")
            HeroDictionaryProgression.expForLevel(base, scale)
        }
        val resolved = resolveStaged(request.arr("staged"), itemMap, ownedByItem, 1)
        val settle = planConsumption(resolved, levelBefore, bitsOf(3), cap, requirement, setup.roll, setup.fillToCap)
        val cost = goldCost(settle.long("total_cost_base"), levelBefore, inputs.str("property_410_raw"))
        if (gold < BigInteger.valueOf(cost)) throw ItemFortifyRejected("Insufficient Gold for the Fortify cost")
        // Stats recomputed through the shared model (grow unchanged): grow x (level + grade + 3) for the zero profile.
        val grade = HeroDictionaryProgression.unpackHeroId(template).grade
        val grows = HeroFortify.GROW_IDS.associateWith { values.getValue(it).int("bits") }
        val newStats = HeroFortify.STAT_IDS.zip(HeroStats.statsAt(model, BigInteger.valueOf(settle.long("new_level")))).toMap()
        val afterById = LinkedHashMap<Long, BigInteger>()
        afterById[1] = BigInteger.valueOf(template)
        afterById[2] = settle.int("new_level")
        afterById[3] = settle.int("new_exp")
        for (sid in HeroFortify.STAT_IDS) afterById[sid] = newStats.getValue(sid)
        for (gid in HeroFortify.GROW_IDS) afterById[gid] = grows.getValue(gid)
        val updateFields = JArr()
        for (fid in HERO_FULL_FIELDS) {
            val value = values[fid] ?: throw ItemFortifyRejected("Hero is missing field $fid for the op-46 update")
            val bits = afterById.getValue(fid)
            val tag = value.long("tag").toInt()
            if (bits >= BigInteger.ONE.shiftLeft(widthOf(tag))) throw ItemFortifyRejected("Hero field $fid would overflow its wire width")
            updateFields.add(jobj("id" to fid, "value" to jobj("tag" to tag, "bits" to bits)))
        }
        val afterTarget = JArr(heroes.getValue(targetUid).mapTo(ArrayList<JValue>()) { f ->
            val field = f.asObj
            val value = JObj(LinkedHashMap(field.obj("value").map))
            afterById[field.long("id")]?.let { value["bits"] = JInt(it) }
            jobj("id" to field["id"], "value" to value)
        })
        val consumed = settle.arr("consumed")
        val changes = itemChanges(consumed)
        val statDeltas = HeroFortify.STAT_IDS.map { newStats.getValue(it) - values.getValue(it).int("bits") }
        val heroGrowRow = jarr(targetUid, settle["total_awarded"], settle.long("new_level") - levelBefore, *statDeltas.toTypedArray(), 0, 0, 0, 0)
        val resultPacket = HERO_RESULT_OPCODE to itemFortifyResultPayload(consumed, "hero_grow", jarr(heroGrowRow))
        return jobj("kind" to "hero", "target_uid" to targetUid, "template" to template, "grade" to grade,
            "profile" to model["profile"], "profile_components" to model["components"],
            "profile_evidence_class" to model["evidence_class"], "stat_permille" to model["permille"],
            "full_grow" to model["full_grow"],
            "result_packet" to frameJson(resultPacket),
            "level_before" to levelBefore, "exp_before" to bitsOf(3),
            "new_level" to settle["new_level"], "new_exp" to settle["new_exp"],
            "new_stats" to HeroFortify.STAT_IDS.map { newStats.getValue(it) }, "grows" to HeroFortify.GROW_IDS.map { grows.getValue(it) },
            "reached_cap" to settle["reached_cap"], "levels_gained" to settle["levels_gained"],
            "total_awarded" to settle["total_awarded"], "total_staged_base" to settle["total_staged_base"],
            "total_cost_base" to settle["total_cost_base"],
            "gold_cost" to cost, "gold_after" to (gold - BigInteger.valueOf(cost)), "consumed" to consumed,
            "item_changes" to changes, "update_fields" to updateFields, "after_target" to afterTarget,
            "bonus_policy_applied" to (setup.roll != null), "rng_seed" to setup.seed,
            "bonus_tallies" to settle["bonus_tallies"], "topped_up" to settle["topped_up"],
            "evidence_class" to (if (setup.roll != null) "preservation_policy_local_rng" else "capture_observed_no_bonus"),
            "state_packet" to frameJson(HERO_UPDATE_OPCODE to HeroFortify.heroPropertyUpdatePayload(targetUid, updateFields)))
    }

    /** The code a ValueError-family refusal carries (`getattr(exc, "code", 102)`). */
    fun codeOf(e: Throwable): Int = when (e) {
        is ItemFortifyRejected -> e.code
        is HeroFortify.FortifyRejected -> e.code
        is HeroStats.ProfileUnsupported -> e.code
        is Acquisition.Rejected -> e.code
        else -> ERROR_INVALID
    }

    // --- gear / jewelry planner (shared record shape) ---------------------------------------------------------------

    /**
     * `_plan_record_item_fortify(...)`: gear (C93) and jewelry (C2641), the target is one owned 22-byte record. `inputs`
     * gives the cap, the EXP curve and scale, and the Gold cost property.
     */
    private fun planRecordItemFortify(request: JObj, records: Map<Long, List<Long>>, itemMap: Map<Long, JObj>, ownedByItem: Map<Long, JObj>,
                                      gold: BigInteger, inputs: JObj?, expectedClass: Long, updateOpcode: Int, propertyKey: String,
                                      curveKey: String, scaleKey: String, maxLevelCode: Int, bonusPolicy: JObj?, rngSeed: BigInteger?): JObj {
        val setup = bonusSetup(bonusPolicy, rngSeed)
        val targetUid = request.long("target_uid")
        val wire = records[targetUid]?.toList() ?: throw ItemFortifyRejected("Target is not owned")
        if (wire.size != 7) throw ItemFortifyRejected("Target record is malformed")
        if (inputs == null) throw ItemFortifyRejected("Catalog inputs are unavailable for the requested target")
        if (wire[HeroFortify.E_TEMPLATE] != inputs.long("template")) throw ItemFortifyRejected("Catalog inputs do not belong to the requested target template")
        val levelBefore = wire[HeroFortify.E_LEVEL]
        val cap = inputs.long("cap")
        val scale = inputs.long(scaleKey)
        val curve = inputs.obj(curveKey)
        val requirement = { lvl: Long ->
            val base = (curve[lvl.toString()] as? JInt)?.value?.toLong() ?: throw ItemFortifyRejected("No exp row for level $lvl")
            HeroFortify.expForEquipLevel(base, scale)
        }
        val resolved = resolveStaged(request.arr("staged"), itemMap, ownedByItem, expectedClass)
        val settle = planConsumption(resolved, levelBefore, wire[HeroFortify.E_EXP], cap, requirement, setup.roll, setup.fillToCap,
            maxLevelCode)
        if (settle.bool("reached_cap") && settle.long("new_level") == levelBefore) {
            throw ItemFortifyRejected("The target already reached its configured level cap", maxLevelCode)
        }
        val cost = goldCost(settle.long("total_cost_base"), levelBefore, inputs.str(propertyKey))
        if (gold < BigInteger.valueOf(cost)) throw ItemFortifyRejected("Insufficient Gold for the Fortify cost")
        val after = wire.toMutableList()
        after[HeroFortify.E_LEVEL] = settle.long("new_level")
        after[HeroFortify.E_EXP] = settle.long("new_exp")
        val consumed = settle.arr("consumed")
        val changes = itemChanges(consumed)
        val growKey = if (expectedClass == 2L) "equip_grow" else "jewel_grow"
        val resultOpcode = if (expectedClass == 2L) GEAR_RESULT_OPCODE else JEWELRY_RESULT_OPCODE
        val growRow = jarr(targetUid, settle["total_awarded"], settle.long("new_level") - levelBefore)
        val resultPacket = resultOpcode to itemFortifyResultPayload(consumed, growKey, jarr(growRow))
        return jobj("kind" to (if (expectedClass == 2L) "gear" else "jewelry"), "target_uid" to targetUid,
            "result_packet" to frameJson(resultPacket),
            "template" to wire[HeroFortify.E_TEMPLATE], "grade" to wire[HeroFortify.E_GRADE],
            "level_before" to levelBefore, "exp_before" to wire[HeroFortify.E_EXP],
            "new_level" to settle["new_level"], "new_exp" to settle["new_exp"],
            "reached_cap" to settle["reached_cap"], "levels_gained" to settle["levels_gained"],
            "total_awarded" to settle["total_awarded"], "total_staged_base" to settle["total_staged_base"],
            "gold_cost" to cost, "gold_after" to (gold - BigInteger.valueOf(cost)), "consumed" to consumed,
            "item_changes" to changes, "before_target" to wire, "after_target" to after,
            "total_cost_base" to settle["total_cost_base"],
            "bonus_policy_applied" to (setup.roll != null), "rng_seed" to setup.seed,
            "bonus_tallies" to settle["bonus_tallies"], "topped_up" to settle["topped_up"],
            "evidence_class" to (if (setup.roll != null) "preservation_policy_local_rng" else "capture_observed_no_bonus"),
            "state_packet" to frameJson(updateOpcode to HeroFortify.equipmentRecordPayload(after)))
    }

    // --- equipped jewelry: formation blocks_40 ------------------------------------------------------------------------
    // An equipped jewelry is a formation-slot block {id: u8 slot, raw: 40 bytes}: u32 uid, u32 config, u32 exp, u32 level,
    // u8 grade (byte 16), super flag (byte 17), enchant u32 (bytes 20-23), the rest preserved verbatim.
    const val JEWELRY_BLOCK_LEN = 40
    const val JEWELRY_GRADE_OFFSET = 16

    /** `jewelry_block_decode(raw)`: {uid, config, exp, level, grade, tail}. */
    fun jewelryBlockDecode(raw: ByteArray): JObj {
        if (raw.size != JEWELRY_BLOCK_LEN) throw PyValues.ValueError("Jewelry block must be 40 bytes")
        val r = WireReader(raw)
        return jobj("uid" to r.u32(), "config" to r.u32(), "exp" to r.u32(), "level" to r.u32(),
            "grade" to (raw[JEWELRY_GRADE_OFFSET].toInt() and 0xFF), "tail" to raw.copyOfRange(JEWELRY_GRADE_OFFSET + 1, raw.size).toHexString())
    }

    /** `jewelry_block_with(raw, exp, level)`: the same 40-byte block with only EXP / level replaced. */
    fun jewelryBlockWith(raw: ByteArray, exp: Long, level: Long): ByteArray {
        if (raw.size != JEWELRY_BLOCK_LEN) throw PyValues.ValueError("Jewelry block must be 40 bytes")
        val r = WireReader(raw)
        val uid = r.u32()
        val config = r.u32()
        return WireWriter().number('I', uid).number('I', config).number('I', uint(exp, 32, "Jewelry EXP"))
            .number('I', uint(level, 32, "Jewelry level")).raw(raw.copyOfRange(16, raw.size)).bytes()
    }

    /** One equipped jewelry of [jewelryView]; `record` is the 7-value op-3080 shape (uid, config, level, exp, grade, super, enchant). */
    class JewelryEntry(val slotIndex: Int, val blockIndex: Int, val blockId: JValue, val raw: ByteArray, val record: List<Long>) {
        fun toJson(): JObj = jobj("slot_index" to slotIndex, "block_index" to blockIndex, "block_id" to blockId, "raw" to raw.toHexString(), "record" to record)
    }

    /**
     * `jewelry_view(formation)`: {uid: entry} of every equipped jewelry (formation blocks_40). Unequipped jewelry (the
     * opcode-3072 list) is not modelled here. The super flag (byte 17) and enchant (u32 at 20) go into the record so an
     * S3080 after a Fortify keeps them.
     */
    fun jewelryView(formation: JArr): LinkedHashMap<Long, JewelryEntry> {
        val view = LinkedHashMap<Long, JewelryEntry>()
        formation.forEachIndexed { si, slot ->
            val blocks = (slot.asObj["blocks_40"] as? JArr) ?: JArr()
            blocks.forEachIndexed { bi, b ->
                val block = b.asObj
                val raw = block.str("raw_hex").hexBytes()
                val d = jewelryBlockDecode(raw)
                val uid = d.long("uid")
                if (uid in view) throw PyValues.ValueError("Duplicate equipped jewelry UID")
                val enchant = WireReader(raw.copyOfRange(20, 24)).u32()
                view[uid] = JewelryEntry(si, bi, block["id"]!!, raw,
                    listOf(uid, d.long("config"), d.long("level"), d.long("exp"), d.long("grade"), (raw[17].toLong() and 0xFF), enchant))
            }
        }
        return view
    }

    /** `plan_gear_item_fortify(...)` (C93). */
    fun planGearItemFortify(request: JObj, equipment: Map<Long, List<Long>>, itemMap: Map<Long, JObj>, ownedByItem: Map<Long, JObj>,
                            gold: BigInteger, inputs: JObj?, bonusPolicy: JObj? = null, rngSeed: BigInteger? = null): JObj =
        planRecordItemFortify(request, equipment, itemMap, ownedByItem, gold, inputs, 2, EQUIP_UPDATE_OPCODE, "property_411_raw",
            "equipexp", "scale_113", ERROR_GEAR_MAX_LEVEL, bonusPolicy, rngSeed)

    /** `plan_jewelry_item_fortify(...)` (C2641). */
    fun planJewelryItemFortify(request: JObj, jewelry: Map<Long, List<Long>>, itemMap: Map<Long, JObj>, ownedByItem: Map<Long, JObj>,
                               gold: BigInteger, inputs: JObj?, bonusPolicy: JObj? = null, rngSeed: BigInteger? = null): JObj =
        planRecordItemFortify(request, jewelry, itemMap, ownedByItem, gold, inputs, 3, JEWELRY_UPDATE_OPCODE, "property_jewelry_raw",
            "jewelryexp", "scale_jewelry", ERROR_GEAR_MAX_LEVEL, bonusPolicy, rngSeed)

    // --- packet builders ------------------------------------------------------------------------------------------

    /**
     * `item_fortify_packets(plan, gold_payload, activity_payload)`: the item stack updates, the state frame, the result
     * popup, the Gold update, then the regenerated activity list. The broadcast / task side-effect frames are excluded.
     */
    fun itemFortifyPackets(plan: JObj, goldPayload: ByteArray, activityPayload: ByteArray? = null): List<Frame> {
        val packets = plan.arr("item_changes").mapTo(ArrayList()) { frameOf(it.asObj.getValue("packet")) }
        packets.add(frameOf(plan.getValue("state_packet")))
        packets.add(frameOf(plan.getValue("result_packet")))
        packets.add(ROLE_UPDATE_OPCODE to goldPayload)
        if (activityPayload != null) packets.add(1184 to activityPayload)
        return packets
    }
}
