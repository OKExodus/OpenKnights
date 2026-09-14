package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.asArr
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.gamedata.GameTable
import io.github.okexodus.openknights.protocol.BattleReport
import io.github.okexodus.openknights.protocol.TypedValues
import io.github.okexodus.openknights.protocol.WireReader
import io.github.okexodus.openknights.protocol.WireWriter
import java.math.BigInteger

/**
 * Rebirth Evolve (C101 → S58) and Rebirth Fortify (C99 → S56) (`rebirth.py`, docs/REBIRTH_CONTRACT.md), built from the
 * game's own code and tables.
 *
 * - C101 `u32 hero uid`; S58 `u32 uid, hero field map, Reward`. C99 `u32 target, u32 n, n x (u32 qianghua_itemexp key,
 *   u32 qty)` (class-4 keys, 1-1,000 items); S56 = the S52 layout (records + Reward).
 * - `zhuansheng_jinhua` row by (102 = hero 104, 103 = Rebirth Tier, 104 = hero 995): materials 201-208, class stone 301
 *   (hero 998 = 2) / 302 (998 = 3) x 303, Gold 304, Rebirth Level cap 402, required level 401, player level 502.
 * - Rebirth Level EXP to leave L = trunc(zhuansheng_exp[L].102 x hero.137 / 10000); Fortify Gold = staged EXP x Rebirth
 *   Level x property 420 / 10000.
 * - Hero fields 19 Rebirth Level, 20 Rebirth EXP, 21 Rebirth Tier, 22 / 23 additional ATK / DEF = hero 997 / 996 x (19 +
 *   21 + 3).
 * Structural candidates / labeled policy (no live capture): the frame orders, the S58 Reward, Tier + 1 with the template
 * unchanged, the 22 / 23 formula beyond tier 1 and the error codes.
 */
object Rebirth {
    const val C_EVOLVE = 101
    const val C_FORTIFY = 99
    const val S_EVOLVE = 58
    const val S_FORTIFY = 56
    const val S_HERO_UPDATE = 46
    const val REBIRTH_LEVEL = 19L
    const val REBIRTH_EXP = 20L
    const val REBIRTH_TIER = 21L
    const val REBIRTH_ATK = 22L
    const val REBIRTH_DEF = 23L
    const val ERROR_INVALID = 102              // "Invalid Data"
    const val ERROR_NOT_REBORN = 101           // "Invalid configuration"
    const val ERROR_MAX_TIER = 69685           // "Has reached max tiers, can not evolve further"
    const val ERROR_INSUFFICIENT = 69686       // "Insufficient to evolve"
    const val ERROR_LEVEL = 1009               // "You have to reach a certain level to evolve"
    const val ERROR_MAX_LEVEL = 1008           // "The Hero already reached the top level"
    const val ERROR_PLAYER_LEVEL = 103         // "Not Enough Character Level"
    const val ITEM_CLASS = 4L                  // qianghua_itemexp field 102 for rebirth heroes
    const val MAX_ITEMS = 1000L                // GameStateStrengthenItem::HandleMenuSure
    const val PROPERTY_GOLD = 420
    val GROW_TAIL = listOf(0L, 0L, 0L, 0L)

    /** `_n(row, key)`: `int(strip)` of a cell, 0 when empty (a malformed cell is a ValueError). */
    private fun n(row: Map<String, String>, key: String): Long {
        val value = PyValues.strip(row[key] ?: "")
        return if (value.isNotEmpty()) PyValues.parseLong(value) else 0L
    }

    private fun n(row: GameTable.Row, key: String): Long {
        val value = PyValues.strip(row.field(key) ?: "")
        return if (value.isNotEmpty()) PyValues.parseLong(value) else 0L
    }

    /** `_values(fields)`: {id: value}. */
    private fun values(fields: JArr): LinkedHashMap<Long, JObj> =
        LinkedHashMap<Long, JObj>().also { m -> fields.forEach { m[it.asObj.long("id")] = it.asObj.obj("value") } }

    private fun hero(owned: Owned, uid: Long): JArr {
        for (fields in owned.state.arr("heroes")) {
            if (values(fields.asArr).getValue(0L).int("bits") == BigInteger.valueOf(uid)) return fields.asArr
        }
        throw Acquisition.Rejected("Hero is not owned", ERROR_INVALID)
    }

    /** `_profile(owned, fields, inputs)`: the template row and the rebirth selectors (IsReborn = hero 998 in {2, 3}). */
    private fun profile(fields: JArr, inputs: AcquisitionInputs): JObj {
        val values = values(fields)
        val template = values.getValue(1L).long("bits")
        val row = inputs.heroFields(template) ?: throw Acquisition.Rejected("Unknown hero template", ERROR_INVALID)
        val kind = n(row, "998")
        if (kind != 2L && kind != 3L) throw Acquisition.Rejected("This hero is not a Rebirth hero", ERROR_NOT_REBORN)
        for (fid in listOf(REBIRTH_LEVEL, REBIRTH_EXP, REBIRTH_TIER, REBIRTH_ATK, REBIRTH_DEF)) {
            val v = values[fid]
            if (v == null || (v["tag"] as? JInt)?.value?.toInt() !in TypedValues.WIDTH_FORMAT) throw Acquisition.Rejected("Hero lacks rebirth field $fid", ERROR_INVALID)
        }
        return jobj("template" to template, "kind_998" to kind, "group_995" to n(row, "995"), "category_104" to n(row, "104"),
            "leader_143" to n(row, "143"), "scale_137" to n(row, "137"), "atk_997" to n(row, "997"),
            "def_996" to n(row, "996"), "level" to values.getValue(REBIRTH_LEVEL)["bits"], "exp" to values.getValue(REBIRTH_EXP)["bits"],
            "tier" to values.getValue(REBIRTH_TIER)["bits"])
    }

    /** `advance_row(inputs, profile, tier)`: the zhuansheng_jinhua row of (hero 104, tier, hero 995), first match. */
    fun advanceRow(inputs: AcquisitionInputs, profile: JObj, tier: Long): GameTable.Row? =
        inputs.rows("zhuansheng_jinhua").firstOrNull {
            n(it, "102") == profile.long("category_104") && n(it, "103") == tier && n(it, "104") == profile.long("group_995")
        }

    /** `exp_to_leave(inputs, profile, level)`: trunc(zhuansheng_exp[level].102 x hero 137 / 10000) (binary64). */
    fun expToLeave(inputs: AcquisitionInputs, profile: JObj, level: Long): Long {
        for (f in inputs.rows("zhuansheng_exp")) {
            if (n(f, "101") == level) return (n(f, "102").toDouble() * profile.long("scale_137").toDouble() / 10000).toLong()
        }
        throw Acquisition.Rejected("No zhuansheng_exp row for level $level", ERROR_INVALID)
    }

    /** `bonus_stats(profile, level, tier)`: fields 22 / 23 (bounded at tier 1; the same multiplier kept above — policy). */
    fun bonusStats(profile: JObj, level: Long, tier: Long): Pair<Long, Long> =
        profile.long("atk_997") * (level + tier + 3) to profile.long("def_996") * (level + tier + 3)

    private fun widthOf(tag: Int): Int = when (TypedValues.WIDTH_FORMAT.getValue(tag).uppercaseChar()) { 'B' -> 8; 'H' -> 16; 'I' -> 32; else -> 64 }

    /** `_set(fields, changes)`: absolute new values with the stored tags (in place); the changed typed fields in id order. */
    private fun set(fields: JArr, changes: Map<Long, Long>): JArr {
        val changed = JArr()
        for (fv in fields) {
            val f = fv.asObj
            val id = f.long("id")
            val value = f.obj("value")
            val new = changes[id] ?: continue
            if (JInt(new) != value["bits"]) {
                if (!(new >= 0 && BigInteger.valueOf(new) < BigInteger.ONE.shiftLeft(widthOf(value.long("tag").toInt())))) {
                    throw Acquisition.Rejected("Hero field $id would overflow its wire width", ERROR_INVALID)
                }
                value["bits"] = JInt(new)
                changed.add(jobj("id" to f["id"], "value" to JObj(LinkedHashMap(value.map))))
            }
        }
        return changed
    }

    private fun payGold(owned: Owned, cost: Long): Frame? {
        if (owned.roleBits(Acquisition.GOLD) < BigInteger.valueOf(cost)) throw Acquisition.Rejected("Not enough Gold", ERROR_INSUFFICIENT)
        return if (cost != 0L) owned.roleAdd(Acquisition.GOLD, -cost) else null
    }

    // --- C101 Rebirth Evolve ---------------------------------------------------------------------------------------

    /** `decode_evolve_request(payload)`: u32 hero uid. */
    fun decodeEvolveRequest(payload: ByteArray): JObj {
        if (payload.size != 4) throw Acquisition.Rejected("C101 is u32 hero uid", ERROR_INVALID)
        return jobj("uid" to WireReader(payload).u32())
    }

    /** `evolve_cost(row, profile)`: [(template, quantity)] (materials 201-208, the class stone) and the Gold 304. */
    fun evolveCost(row: GameTable.Row, profile: JObj): Pair<List<Pair<Long, Long>>, Long> {
        val items = (0 until 4).map { k -> n(row, (201 + 2 * k).toString()) to n(row, (202 + 2 * k).toString()) }.toMutableList()
        val stone = if (profile.long("kind_998") == 2L) n(row, "301") else n(row, "302")
        if (stone != 0L && n(row, "303") != 0L) items.add(stone to n(row, "303"))
        return items.filter { (t, q) -> t != 0L && q != 0L } to n(row, "304")
    }

    /**
     * `plan_evolve(request, owned, inputs)`: the cost of the current tier's row; Rebirth Tier + 1, fields 22 / 23
     * recomputed. Frames: consumes (S66 / S68), S128 Gold, S58 (uid, full field map, Reward with one HeroGrow row).
     */
    fun planEvolve(request: JObj, owned: Owned, inputs: AcquisitionInputs): Plan {
        val uid = request.long("uid")
        val fields = hero(owned, uid)
        val profile = profile(fields, inputs)
        if (profile.long("group_995") == 0L) throw Acquisition.Rejected("This hero has no Rebirth Evolve row", ERROR_NOT_REBORN)
        val tier = maxOf(profile.long("tier"), 1L)                       // the client treats tier 0 as 1
        val row = advanceRow(inputs, profile, tier)
        val following = advanceRow(inputs, profile, tier + 1)
        if (row == null || following == null) throw Acquisition.Rejected("Rebirth Tier is already at its maximum", ERROR_MAX_TIER)
        if (profile.long("level") < n(row, "401")) throw Acquisition.Rejected("Rebirth Level is below this tier's requirement", ERROR_LEVEL)
        if (owned.roleBits(Acquisition.ROLE_LEVEL) < BigInteger.valueOf(n(row, "502"))) {      // "Level Needed for Players"
            throw Acquisition.Rejected("Character level is below this tier's requirement", ERROR_PLAYER_LEVEL)
        }
        val (materials, gold) = evolveCost(row, profile)
        for ((template, quantity) in materials) {
            if (owned.countOf(template) < quantity) throw Acquisition.Rejected("Not enough materials to evolve", ERROR_INSUFFICIENT)
        }
        if (owned.roleBits(Acquisition.GOLD) < BigInteger.valueOf(gold)) throw Acquisition.Rejected("Not enough Gold to evolve", ERROR_INSUFFICIENT)
        val frames = ArrayList<Frame>()
        for ((template, quantity) in materials) frames += owned.consumeTemplate(template, quantity)
        payGold(owned, gold)?.let { frames.add(it) }
        val before = bonusStats(profile, profile.long("level"), profile.long("tier"))
        val (atk, dfn) = bonusStats(profile, profile.long("level"), tier + 1)
        val changed = set(fields, mapOf(REBIRTH_TIER to tier + 1, REBIRTH_ATK to atk, REBIRTH_DEF to dfn))
        val reward = BattleReport.emptyReward(14)
        reward["hero_grow"] = jarr(jarr(uid, 0, 0, 0, maxOf(atk - before.first, 0L), maxOf(dfn - before.second, 0L), 0, *GROW_TAIL.toTypedArray()))
        frames.add(S_EVOLVE to WireWriter().u32(uid).also { TypedValues.encodeFields(fields, it) }.also { BattleReport.encodeReward(it, reward) }.bytes())
        return Plan(jobj("uid" to uid, "template" to profile["template"], "tier_before" to profile["tier"], "tier_after" to tier + 1,
            "rebirth_level" to profile["level"], "cap_after" to n(following, "402"), "row" to n(row, "101"),
            "materials" to materials.map { jarr(it.first, it.second) }, "gold_cost" to gold, "fields_changed" to changed, "reward" to reward,
            "evidence_class" to "native_use_structural_candidate"), frames)
    }

    // --- C99 Rebirth Fortify ---------------------------------------------------------------------------------------

    /** `decode_fortify_request(payload)`: u32 target, u32 n, n x (u32 key, u32 qty); any reader failure is refused. */
    fun decodeFortifyRequest(payload: ByteArray): JObj {
        val reader = WireReader(payload)
        val staged = JArr()
        val target: Long
        try {
            target = reader.u32()
            val count = reader.u32()
            var i = 0L
            while (i < count) {
                val key = reader.u32()
                val qty = reader.u32()
                staged.add(jarr(key, qty))
                i++
            }
        } catch (e: Exception) {
            throw Acquisition.Rejected("C99 is u32 target, u32 n, n × (u32 key, u32 qty)", ERROR_INVALID)
        }
        if (reader.offset != payload.size) throw Acquisition.Rejected("C99 has trailing bytes", ERROR_INVALID)
        return jobj("target_uid" to target, "staged" to staged)
    }

    /**
     * `plan_fortify(request, owned, inputs)`: class-4 EXP stones in request order until the tier's Rebirth Level cap (the
     * C91 settlement, no bonus); fields 19 / 20 / 22 / 23. Frames: consumes, S46 (19, 20, 22, 23), S128 Gold, S56.
     */
    fun planFortify(request: JObj, owned: Owned, inputs: AcquisitionInputs): Plan {
        val uid = request.long("target_uid")
        val fields = hero(owned, uid)
        val profile = profile(fields, inputs)
        if (profile.long("leader_143") == 1L) throw Acquisition.Rejected("The leader cannot be Rebirth-fortified", ERROR_NOT_REBORN)
        val staged = request.arr("staged").map { it.asArr[0].long to it.asArr[1].long }
        val total = staged.sumOf { it.second }
        if (staged.isEmpty() || total !in 1..MAX_ITEMS || staged.any { it.second <= 0 }) throw Acquisition.Rejected("Select 1 to 1,000 items", ERROR_INVALID)
        if (staged.map { it.first }.toSet().size != staged.size) throw Acquisition.Rejected("Duplicate item type", ERROR_INVALID)
        val keys = LinkedHashMap<Long, GameTable.Row>()
        for (f in inputs.rows("qianghua_itemexp")) keys[n(f, "101")] = f
        val resolved = ArrayList<JObj>()
        for ((key, quantity) in staged) {
            val f = keys[key]
            if (f == null || n(f, "102") != ITEM_CLASS) throw Acquisition.Rejected("Not a Rebirth EXP item", ERROR_INVALID)
            val item = n(f, "103")
            val ownedCount = owned.countOf(item)
            if (ownedCount < quantity) throw Acquisition.Rejected("Not enough items", ERROR_INSUFFICIENT)
            resolved.add(jobj("request_template" to key, "item_id" to item, "item_exp" to n(f, "104"), "owned_uid" to 0,
                "owned_count" to ownedCount, "quantity" to quantity))
        }
        val tier = maxOf(profile.long("tier"), 1L)
        val row = advanceRow(inputs, profile, tier) ?: throw Acquisition.Rejected("No Rebirth row for this hero", ERROR_NOT_REBORN)
        val cap = n(row, "402")
        val settle = try {
            ItemFortify.planConsumption(resolved, profile.long("level"), profile.long("exp"), cap, { level -> expToLeave(inputs, profile, level) })
        } catch (e: ItemFortify.ItemFortifyRejected) {
            throw Acquisition.Rejected(e.message ?: "", if (e.code == 1008) ERROR_MAX_LEVEL else ERROR_INVALID)
        }
        val propertyText = inputs.property(PROPERTY_GOLD)
        val propertyRaw = if (propertyText.isNullOrEmpty()) 0L else PyValues.parseLong(propertyText)
        val gold = Math.floorDiv(Math.multiplyExact(Math.multiplyExact(settle.long("total_staged_base"), profile.long("level")), propertyRaw), 10000L)
        if (owned.roleBits(Acquisition.GOLD) < BigInteger.valueOf(gold)) throw Acquisition.Rejected("Not enough Gold", ERROR_INSUFFICIENT)
        val frames = ArrayList<Frame>()
        val consumed = settle.arr("consumed")
        for (c in consumed) frames += owned.consumeTemplate(c.asObj.long("item_id"), c.asObj.long("taken"))
        val heroValues = values(fields)
        val atkBefore = heroValues.getValue(REBIRTH_ATK).long("bits")
        val defBefore = heroValues.getValue(REBIRTH_DEF).long("bits")
        val newLevel = settle.long("new_level")
        val (atk, dfn) = bonusStats(profile, newLevel, profile.long("tier"))
        set(fields, mapOf(REBIRTH_LEVEL to newLevel, REBIRTH_EXP to settle.long("new_exp"), REBIRTH_ATK to atk, REBIRTH_DEF to dfn))
        val update = JArr(fields.filterTo(ArrayList<JValue>()) { it.asObj.long("id") in setOf(REBIRTH_LEVEL, REBIRTH_EXP, REBIRTH_ATK, REBIRTH_DEF) })
        frames.add(S_HERO_UPDATE to WireWriter().u32(uid).also { TypedValues.encodeFields(update, it) }.bytes())
        payGold(owned, gold)?.let { frames.add(it) }
        // UpdateRebornRewardDisplay (native use): wire index 2 / 4 / 5 != 0 shows the Level / ATK / DEF line as "(getter −
        // index 8 / 9 / 10 -> getter)"; index 7 is "EXP Gained".
        val dLevel = newLevel - profile.long("level")
        val dAtk = maxOf(atk - atkBefore, 0L)
        val dDef = maxOf(dfn - defBefore, 0L)
        val grow = jarr(uid, 0, dLevel, 0, dAtk, dDef, 0, settle["total_awarded"], dLevel, dAtk, dDef)
        frames.add(S_FORTIFY to ItemFortify.itemFortifyResultPayload(consumed, "hero_grow", jarr(grow)))
        return Plan(jobj("uid" to uid, "template" to profile["template"], "tier" to profile["tier"], "cap" to cap,
            "level_before" to profile["level"], "exp_before" to profile["exp"], "level_after" to newLevel,
            "exp_after" to settle["new_exp"], "reached_cap" to settle["reached_cap"],
            "consumed" to consumed.map { c -> jobj("item" to c.asObj["item_id"], "taken" to c.asObj["taken"], "exp" to c.asObj["awarded"]) },
            "exp_awarded" to settle["total_awarded"], "gold_cost" to gold, "bonus_before" to listOf(atkBefore, defBefore),
            "bonus_after" to listOf(atk, dfn), "evidence_class" to "native_use_structural_candidate"), frames)
    }
}
