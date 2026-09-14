package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.asArr
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.protocol.TypedValues
import io.github.okexodus.openknights.protocol.WireReader
import java.math.BigInteger

/**
 * Reborn (C95 → S54) (`reborn.py`, docs/REBIRTH_CONTRACT.md section 1b), built from the game's own code and tables.
 *
 * - C95 `u32 hero uid, u32 target base`: the target is zhuansheng 104 of the row keyed by 102 = template / 1000.
 * - Client gates (`canHeroReborn`): a row with 105 != 0, 102 = the hero's base, grade >= 106, level >= 107. The cost is
 *   displayed but not gated (201/202 … 207/208, the type-0 class stone 210 x 211, Gold 213) — the server checks it.
 * - S54: one typed hero field map (no count byte, no Reward); the client replaces the card by the map's uid, so the uid
 *   stays the same.
 * Structural candidates / labeled policies (no live capture): new template = 104 x 1000 + grade (a super-class hero is
 * refused, 69683); uid, level, EXP, development, awaken and god skills kept, base stats / growth recomputed for the new
 * base with the shared stat model; Rebirth fields 19 = 1, 20 = 0, 21 = 1, 22 / 23 = new hero 997 / 996 x (19 + 21 + 3),
 * absent 19 / 20 / 21 added as tag-5 fields in id order; frames consumes, S128 Gold, S54 last; the refusal codes.
 */
object Reborn {
    const val C_REBORN = 95
    const val S_REBORN = 54
    const val ERROR_INVALID = 102              // "Invalid Data"
    const val ERROR_CANNOT = 69680             // "Heroes can not reborn"
    const val ERROR_LEVEL = 69681              // "Hero level doesn't meet the reborn level"
    const val ERROR_TIER = 69682               // "Hero tier doesn't meet the reborn level"
    const val ERROR_CERTAIN = 69683            // "Can not reborn certain heroes"
    const val ERROR_ITEMS = 2002               // "Not enough items available"
    const val ERROR_GOLD = 4000                // "Not enough Resources"
    const val REBORN_LEVEL = 19L
    const val REBORN_EXP = 20L
    const val REBORN_TIER = 21L
    const val REBORN_ATK = 22L
    const val REBORN_DEF = 23L
    val START = linkedMapOf(REBORN_LEVEL to 1L, REBORN_EXP to 0L, REBORN_TIER to 1L)   // structural candidate
    val ADDED_TAG = mapOf(19L to 5, 20L to 5, 21L to 5, 22L to 6, 23L to 6)              // stored_hero_fields / live S32 tags

    /** `_Unsupported`: stat-model refusals (profile outside the model, guide hero, missing awaken row) → 69683. */
    class Unsupported(message: String) : Acquisition.Rejected(message, ERROR_CERTAIN)

    private val UNSUPPORTED = HeroStats.Reject { m, _ -> Unsupported(m) }

    private fun n(row: Map<String, String>, key: String): Long {
        val value = PyValues.strip(row[key] ?: "")
        return if (value.isNotEmpty()) PyValues.parseLong(value) else 0L
    }

    /** `decode_request(payload)`: u32 hero uid, u32 target base. */
    fun decodeRequest(payload: ByteArray): JObj {
        if (payload.size != 8) throw Acquisition.Rejected("C95 is u32 hero uid, u32 target base", ERROR_INVALID)
        val r = WireReader(payload)
        return jobj("uid" to r.u32(), "target_base" to r.u32())
    }

    /** `reborn_row(inputs, base)`: the zhuansheng row `canHeroReborn` accepts for a base (105 != 0, 102 = base). */
    fun rebornRow(inputs: AcquisitionInputs, base: Long): JObj? =
        inputs.rebornRows().firstOrNull { it.long("enabled_105") != 0L && it.long("base_102") == base }

    /** `reborn_cost(row)`: [(template, quantity)] in row order (201/202 … 207/208, the type-0 class stone 210 x 211), Gold. */
    fun rebornCost(row: JObj): Pair<List<Pair<Long, Long>>, Long> {
        val items = row.arr("materials").map { it.asArr[0].long to it.asArr[1].long }.filter { (t, q) -> t != 0L && q != 0L }.toMutableList()
        if (row.long("stone_type0_210") != 0L && row.long("stone_count_211") != 0L) items.add(row.long("stone_type0_210") to row.long("stone_count_211"))
        return items to row.long("gold_213")
    }

    private fun widthOf(tag: Int): Int = when (TypedValues.WIDTH_FORMAT.getValue(tag).uppercaseChar()) { 'B' -> 8; 'H' -> 16; 'I' -> 32; else -> 64 }

    /** `_apply(fields, changes)`: absolute values with the stored tags (absent rebirth fields added, id order); the changed ids. */
    private fun apply(fields: JArr, changes: Map<Long, BigInteger>): List<Long> {
        val present = fields.map { it.asObj.long("id") }.toSet()
        for (fid in (changes.keys - present).sorted()) {
            val tag = ADDED_TAG[fid] ?: throw Acquisition.Rejected("Hero lacks field $fid", ERROR_INVALID)
            fields.add(jobj("id" to fid, "value" to jobj("tag" to tag, "bits" to 0)))
        }
        val sorted = fields.sortedBy { it.asObj.long("id") }
        fields.clear()
        fields.addAll(sorted)
        val changed = ArrayList<Long>()
        for (fv in fields) {
            val f = fv.asObj
            val id = f.long("id")
            val new = changes[id] ?: continue
            val value = f.obj("value")
            if ((value["tag"] as? JInt)?.value?.toInt() !in TypedValues.WIDTH_FORMAT) throw Acquisition.Rejected("Hero field $id is not a scalar", ERROR_INVALID)
            if (new.signum() < 0 || new >= BigInteger.ONE.shiftLeft(widthOf(value.long("tag").toInt()))) {
                throw Acquisition.Rejected("Hero field $id would overflow its wire width", ERROR_INVALID)
            }
            if (JInt(new) != value["bits"]) {
                value["bits"] = JInt(new)
                changed.add(id)
            }
        }
        return changed
    }

    /** `plan_reborn(request, owned, inputs, excluded_uids)`: C95 in the client's gate order, then the labeled rest. */
    fun planReborn(request: JObj, owned: Owned, inputs: AcquisitionInputs, excludedUids: Collection<Long> = emptyList()): Plan {
        val uid = request.long("uid")
        val fields = owned.state.arr("heroes").firstOrNull { Acquisition.heroValues(it.asArr)[0L] == JInt(uid) }?.asArr
            ?: throw Acquisition.Rejected("Hero is not owned", ERROR_INVALID)
        val values = Acquisition.heroValues(fields)
        val templateValue = values[1L]
        val levelValue = values[2L]
        if (templateValue !is JInt || levelValue !is JInt) throw Acquisition.Rejected("Hero template / level are not scalars", ERROR_INVALID)
        val template = templateValue.value.toLong()
        val level = levelValue.value
        val base = Math.floorDiv(template, 1000L)
        val grade = Math.floorMod(template, 100L)
        val digit = Math.floorMod(Math.floorDiv(template, 100L), 10L)
        val heroRow = inputs.heroFields(template) ?: throw Acquisition.Rejected("Unknown hero template", ERROR_INVALID)
        if (n(heroRow, "143") == 1L) throw Acquisition.Rejected("The leader cannot be reborn", ERROR_CANNOT)
        val row = rebornRow(inputs, base)
        if (row == null || row.long("base_104") == 0L || request.long("target_base") != row.long("base_104")) {
            throw Acquisition.Rejected("No Reborn row of this hero for the requested base", ERROR_CANNOT)
        }
        if (grade < row.long("grade_106")) throw Acquisition.Rejected("Hero tier is below the Reborn requirement", ERROR_TIER)
        if (level < BigInteger.valueOf(row.long("level_107"))) throw Acquisition.Rejected("Hero level is below the Reborn requirement", ERROR_LEVEL)
        if (digit != 0L) {
            throw Acquisition.Rejected("Super-class heroes are refused until the operator decides their reborn template " +
                "(the client preview drops the hundreds digit)", ERROR_CERTAIN)
        }
        if (uid in excludedUids.toSet()) {
            throw Acquisition.Rejected("Exploring / mining heroes cannot be reborn (GameStateRebornChoose drops them)", ERROR_CERTAIN)
        }
        val newTemplate = row.long("base_104") * 1000 + grade
        val newRow = inputs.heroFields(newTemplate) ?: throw Acquisition.Rejected("Reborn base is not a hero template", ERROR_CANNOT)
        val oldInputs: JObj
        val newInputs: JObj
        try {
            oldInputs = inputs.heroStatInputs(template)
            newInputs = inputs.heroStatInputs(newTemplate)
        } catch (e: IllegalArgumentException) {
            throw Acquisition.Rejected("Hero stat inputs unavailable: ${e.message}", ERROR_CERTAIN)
        }
        val resolved = HeroStats.resolveProfile(fields, oldInputs, UNSUPPORTED, ERROR_CERTAIN)
        val grow = HeroStats.recomputeGrow(newInputs.arr("raw_511_514").map { it.long }, newInputs.long("current_potential_rate")).map { BigInteger.valueOf(it) }
        val permille = HeroStats.statPermille(newTemplate, newInputs, resolved.obj("profile").int("awaken"), UNSUPPORTED)
        val stats = HeroStats.baseStats(grow, level, grade, permille, resolved.arr("dev").map { it.big })
        val wireGrow = HeroStats.wireGrowBits(grow, resolved.arr("grow_widths").map { it.long.toInt() })
        val (materials, gold) = rebornCost(row)
        val need = LinkedHashMap<Long, Long>()
        for ((item, quantity) in materials) need[item] = (need[item] ?: 0L) + quantity
        for ((item, quantity) in need) {
            if (owned.countOf(item) < quantity) throw Acquisition.Rejected("Not enough materials to Reborn", ERROR_ITEMS)
        }
        if (owned.roleBits(Acquisition.GOLD) < BigInteger.valueOf(gold)) throw Acquisition.Rejected("Not enough Gold to Reborn", ERROR_GOLD)
        val multiplier = START.getValue(REBORN_LEVEL) + START.getValue(REBORN_TIER) + 3
        val changes = LinkedHashMap<Long, BigInteger>()
        changes[1L] = BigInteger.valueOf(newTemplate)
        HeroStats.STAT_IDS.zip(stats).forEach { (id, v) -> changes[id] = v }
        HeroStats.GROW_IDS.zip(wireGrow).forEach { (id, v) -> changes[id] = v }
        START.forEach { (id, v) -> changes[id] = BigInteger.valueOf(v) }
        changes[REBORN_ATK] = BigInteger.valueOf(n(newRow, "997") * multiplier)
        changes[REBORN_DEF] = BigInteger.valueOf(n(newRow, "996") * multiplier)
        val statsBefore = HeroStats.STAT_IDS.map { values[it] }
        val rebirthBefore = listOf(19L, 20L, 21L, 22L, 23L).map { values[it] }
        val frames = ArrayList<Frame>()
        for ((item, quantity) in materials) frames += owned.consumeTemplate(item, quantity)
        if (gold != 0L) frames.add(owned.roleAdd(Acquisition.GOLD, -gold))
        val changed = apply(fields, changes)
        frames.add(S_REBORN to TypedValues.encodeFieldsBytes(fields))
        return Plan(jobj("operation" to "reborn", "uid" to uid, "target_base" to request["target_base"], "row" to row["id"],
            "template_before" to template, "template_after" to newTemplate, "level" to level, "grade" to grade,
            "materials" to materials.map { jarr(it.first, it.second) }, "gold_cost" to gold, "stats_before" to statsBefore,
            "stats_after" to stats, "grow_after" to wireGrow, "rebirth_before" to rebirthBefore,
            "rebirth_after" to listOf(19L, 20L, 21L, 22L, 23L).map { changes[it] }, "fields_changed" to changed,
            "stat_permille" to permille, "profile_components" to resolved["components"],
            "evidence_class" to "native_use_structural_candidate"), frames)
    }
}
