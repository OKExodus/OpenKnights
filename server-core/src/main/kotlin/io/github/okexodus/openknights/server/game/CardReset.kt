package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.F32
import io.github.okexodus.openknights.exact.Fraction
import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JNull
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.PyInt
import io.github.okexodus.openknights.exact.PyText
import io.github.okexodus.openknights.exact.asArr
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.protocol.BattleReport
import io.github.okexodus.openknights.protocol.Inventory
import io.github.okexodus.openknights.protocol.WireReader
import io.github.okexodus.openknights.protocol.WireWriter
import io.github.okexodus.openknights.server.store.StateStore
import java.math.BigInteger
import java.util.WeakHashMap

/**
 * `card_reset.py`: Sacrifice / Reforge (Hidden Training) — C3723 hero, C3725 gear, C3727 jewelry, `u32 uid, u8 mode`.
 * Mode 0 Sacrifice: the investments come back and the card returns at level 1; mode 1 Reforge: the investments come
 * back, the card is destroyed and soul points are paid. Reply S3722 = one Reward v14; the role / bag / card frames come
 * before it. The returns themselves are [ResetReturns].
 */
object CardReset {
    const val C_RESET_HERO = 3723
    const val C_RESET_GEAR = 3725
    const val C_RESET_JEWEL = 3727
    const val S_RESET_RESULT = 3722
    val KIND = mapOf(C_RESET_HERO to "hero", C_RESET_GEAR to "gear", C_RESET_JEWEL to "jewel")
    const val SACRIFICE = 0L
    const val REFORGE = 1L
    const val S_ITEM_UPDATE = 68
    const val S_ITEM_ADD = 64
    const val S_BENCH_REMOVE = 40
    const val S_HERO_REMOVE = 34
    const val S_EQUIP_BAG_REMOVE = 102
    const val S_EQUIP_REMOVE = 98
    const val S_JEWEL_LIST_REMOVE = 3074
    const val S_JEWEL_LIST_ADD = 3076
    /** Property: the open level 65 (ResetSystem::IsOpen). */
    const val OPEN_LEVEL = 11016L
    /** SoulHero / SoulEquip / SoulJewel role properties. */
    val SOUL_ROLE = mapOf("hero" to 32L, "gear" to 33L, "jewel" to 34L)
    val SOUL_REWARD = mapOf("hero" to "soul_hero", "gear" to "soul_equip", "jewel" to "soul_jewel")
    /** resetdiamond / renascence row prefix. */
    val KIND_KEY = mapOf("hero" to 1L, "gear" to 2L, "jewel" to 3L)

    // errors (text 8000000 + code)
    const val ERROR_TUTORIAL = 70400
    const val ERROR_HERO_LEVEL = 70401
    const val ERROR_MAIN_HERO = 70402
    const val ERROR_HERO_EQUIPPED = 70403
    const val ERROR_SET_OUT = 70404
    const val ERROR_MINING = 70405
    const val ERROR_BAG = 70406
    const val ERROR_GEAR_LEVEL = 70407
    const val ERROR_GEAR_EQUIPPED = 70408
    const val ERROR_JEWEL_LEVEL = 70409
    const val ERROR_JEWEL_EQUIPPED = 70410
    const val ERROR_INVALID = 102

    /** `f32(value)`: the binary32 rounding of `float(value)`. */
    fun f32(value: Double): Double = F32.round(value)

    /** `u32 uid, u8 mode` (0 Sacrifice / 1 Reforge) (`decode_reset`). */
    fun decodeReset(payload: ByteArray, opcode: Int): JObj {
        if (payload.size != 5 || (payload[4].toInt() and 0xFF) !in listOf(SACRIFICE.toInt(), REFORGE.toInt())) {
            throw Acquisition.Rejected("C$opcode is u32 uid + u8 mode (0 Sacrifice / 1 Reforge)")
        }
        val reader = WireReader(payload)
        val uid = reader.u32()
        val mode = reader.u8()
        return jobj("uid" to uid, "mode" to mode)
    }

    /**
     * `Formula::ResetNeedDiamond`: ⌈(float) a + b/10000·level + c/10000·grade⌉ over the resetdiamond row kind·100 + star;
     * Sacrifice (a, b, c) = (704, 705, 706), Reforge = (704, 707, 708).
     */
    fun resetDiamonds(inputs: DailyInputs, kind: String, star: Long?, level: Long, grade: Long, mode: Long): Long {
        if (star == null) throw PyDocs.TypeError("unsupported operand type(s) for +: 'int' and 'NoneType'")
        val row = inputs.resetDiamondRow(KIND_KEY.getValue(kind) * 100 + star) ?: throw Acquisition.Rejected("This card cannot be reset", ERROR_INVALID)
        val (b, c) = if (mode == SACRIFICE) row.long("b_sacrifice") to row.long("c_sacrifice") else row.long("b_reforge") to row.long("c_reforge")
        var value = f32(f32(row.long("a").toDouble()) + f32(f32(f32(b.toDouble()) / f32(10000.0)) * f32(level.toDouble())))
        value = f32(value + f32(f32(f32(c.toDouble()) / f32(10000.0)) * f32(grade.toDouble())))
        return PyInt.ceil(value).longValueExact()
    }

    /** The card to reset (`card_of`'s dict): hero values / gear or jewel record, level, EXP, grade, super, star. */
    class Card(val kind: String, val uid: Long, val template: Long, val level: Long, val exp: Long, val grade: Long, val superClass: Long,
               val star: Long?, val values: Map<Long, JValue?> = emptyMap(), val record: JArr? = null, val equipped: Boolean = false)

    private fun num(v: JValue?): Long = PyDocs.long(v)

    /** `values.get(field) or default` of the hero values. */
    private fun orDefault(values: Map<Long, JValue?>, field: Long, default: Long): Long =
        values[field].let { if (Py.truthy(it)) num(it) else default }

    /** The card of a uid (`card_of`), else 102. */
    fun cardOf(kind: String, uid: Long, owned: Owned, current: StateStore.Current, inputs: DailyInputs): Card {
        if (kind == "hero") {
            val heroes = LinkedHashMap<JValue?, JArr>()
            for (fields in PyDocs.at(owned.state, "heroes") as JArr) {
                val values = Acquisition.heroValues(fields.asArr)
                if (0L !in values) throw PyDocs.KeyError(0)
                heroes[values[0L]] = fields.asArr
            }
            val fields = heroes[JInt(uid)] ?: throw Acquisition.Rejected("Hero is not owned", ERROR_INVALID)
            val values = Acquisition.heroValues(fields)
            if (1L !in values) throw PyDocs.KeyError(1)
            val template = num(values[1L])
            return Card(kind, uid, template, orDefault(values, 2, 1), orDefault(values, 3, 0), Math.floorMod(template, 100L),
                Math.floorMod(Math.floorMod(Math.floorDiv(template, 100L), 10L), 3L), inputs.heroStar(template), values = values)
        }
        if (kind == "gear") {
            val record = (PyDocs.at(owned.state, "equipment") as JArr).map { PyDocs.at(it.asObj, "wire_values") as JArr }
                .firstOrNull { it[0] == JInt(uid) } ?: throw Acquisition.Rejected("Gear is not owned", ERROR_INVALID)
            val bag = (PyDocs.at(owned.state, "bag_equipment_uids") as JArr).toSet()
            return Card(kind, uid, num(record[1]), num(record[2]), num(record[3]), num(record[4]), num(record[5]), inputs.equipStar(num(record[1])),
                record = JArr(record.toMutableList()), equipped = JInt(uid) !in bag)
        }
        if (uid in ItemFortify.jewelryView(PyDocs.at(owned.state, "formation") as JArr)) {
            throw Acquisition.Rejected("Equipped jewelry cannot be reset", ERROR_JEWEL_EQUIPPED)
        }
        val entry = (current.jewelEntriesView ?: JArr()).firstOrNull { (PyDocs.at(it.asObj, "record") as JArr)[0] == JInt(uid) }
            ?: throw Acquisition.Rejected("Jewelry is not owned", ERROR_INVALID)
        val record = PyDocs.at(entry.asObj, "record") as JArr
        return Card(kind, uid, num(record[1]), num(record[2]), num(record[3]), num(record[4]), num(record[5]), inputs.jewelStar(num(record[1])),
            record = JArr(record.toMutableList()), equipped = false)
    }

    /** `card["level"] <= 1 or card["star"] <= 2` (a missing star compares like the reference: TypeError). */
    private fun lowLevelOrTier(card: Card): Boolean {
        if (card.level <= 1) return true
        val star = card.star ?: throw PyDocs.TypeError("'<=' not supported between instances of 'NoneType' and 'int'")
        return star <= 2
    }

    /**
     * Server-side checks of the client's chooser (`check_eligible`): the leader, lineup, Set Out and mining heroes cannot
     * be reset; gear / jewelry must be above level 1 and unequipped.
     */
    fun checkEligible(card: Card, owned: Owned, current: StateStore.Current, inputs: DailyInputs,
                      exploreHeroes: Set<JValue> = emptySet(), miningHeroes: Set<JValue> = emptySet()) {
        when (card.kind) {
            "hero" -> {
                val online = HashSet<JValue?>()
                for (e in WorldParticipants.lineupOf(owned.state)) online.add(JInt(e.uid))
                val team = PyDocs.get(current, "secondary_team")
                val document = if (Py.truthy(team)) PyDocs.get(team as JObj, "document").takeIf { Py.truthy(it) } as JObj? else null
                val references = document?.let { it["references"] ?: JArr() } ?: JArr()
                for (p in references as JArr) if (p is JObj) online.add(p["hero_uid"]?.takeIf { it != JNull })
                if (inputs.heroIsLeader(card.template)) throw Acquisition.Rejected("Main hero cannot be reset", ERROR_MAIN_HERO)
                if (Py.truthy(card.values[14L])) throw Acquisition.Rejected("Cannot reset tutorial hero", ERROR_TUTORIAL)
                if (JInt(card.uid) in online) throw Acquisition.Rejected("Equipped hero cannot be reset", ERROR_HERO_EQUIPPED)
                if (JInt(card.uid) in exploreHeroes) throw Acquisition.Rejected("Set out hero cannot be reset", ERROR_SET_OUT)
                if (JInt(card.uid) in miningHeroes) throw Acquisition.Rejected("Mining hero cannot be reset", ERROR_MINING)
                if (lowLevelOrTier(card)) throw Acquisition.Rejected("Reset Hero's level or tier is not enough", ERROR_HERO_LEVEL)
            }
            "gear" -> {
                if (card.equipped) throw Acquisition.Rejected("Equipped gear cannot be reset", ERROR_GEAR_EQUIPPED)
                if (lowLevelOrTier(card)) throw Acquisition.Rejected("Reset Gear's level or tier is not enough", ERROR_GEAR_LEVEL)
            }
            else -> if (lowLevelOrTier(card)) throw Acquisition.Rejected("Reset Jewelry's level or tier is not enough", ERROR_JEWEL_LEVEL)
        }
    }

    /**
     * Grant the candidate items (ascending id, zero counts kept) → one S68 naming every owned candidate stack with its new
     * count (unchanged stacks too) and one S64 of the new stacks (`_grant_all`); a currency placeholder item is paid into
     * its role by [Owned.grantItem], whose S128 follows the bag frames in grant order.
     */
    fun grantAll(owned: Owned, items: List<Pair<Long, Long>>): MutableList<Frame> {
        val newBefore = owned.newItems.keys.toSet()
        val roles = ArrayList<Frame>()
        for ((template, count) in items) {
            if (count > 0) {
                val frame = owned.grantItem(template, count)
                if (template in Acquisition.CURRENCY_ITEM_ROLE) roles.add(frame)
            }
        }
        val pairs = ArrayList<Pair<Long, Long>>()
        val records = JArr()
        for ((template, _) in items) {
            val stacks = owned.items.filter { it.value.template == template && it.value.count > 0 && it.value.timed == 0L }.keys.sorted()
            if (stacks.isEmpty()) continue
            val uid = stacks[0]
            if (uid in owned.newItems && uid !in newBefore) {
                records.add(jobj("wire_values" to jarr(uid, template, owned.items.getValue(uid).count), "timed_flag" to 0))
            } else {
                pairs.add(uid to owned.items.getValue(uid).count)
            }
        }
        val frames = ArrayList<Frame>()
        if (pairs.isNotEmpty()) {
            val w = WireWriter().number('B', pairs.size.toLong())
            for ((u, c) in pairs) w.number('I', u).number('I', c)
            frames.add(S_ITEM_UPDATE to w.bytes())
        }
        if (records.isNotEmpty()) frames.add(S_ITEM_ADD to Inventory.encode(records))
        frames.addAll(roles)
        return frames
    }

    /**
     * One Sacrifice / Reforge (`plan_reset`). Live orders: hero Sacrifice S68, S32, S38, S2850, S1184, S578, S1188, S40,
     * S34, S3722, S128; gear Sacrifice S68, S64, S96, S100, S578, S1188, S102, S98, S3722, S128; gear Reforge S68, S128
     * soul, S578, S1188, S102, S98, S3722, S128. `exploreHeroes`: the heroes out on Hero Set Out.
     */
    fun planReset(opcode: Int, request: JObj, owned: Owned, inputs: DailyInputs, current: StateStore.Current, servedTime: Long,
                  exploreHeroes: Set<JValue> = emptySet(), miningHeroes: Set<JValue> = emptySet()): Plan {
        val kind = KIND.getValue(opcode)
        val mode = request.long("mode")
        if (owned.roleBits(Acquisition.ROLE_LEVEL) < BigInteger.valueOf(inputs.prop(OPEN_LEVEL, 65))) {
            throw Acquisition.Rejected("Sacrifice opens at level 65", ERROR_INVALID)
        }
        val card = cardOf(kind, request.long("uid"), owned, current, inputs)
        checkEligible(card, owned, current, inputs, exploreHeroes, miningHeroes)
        val price = resetDiamonds(inputs, kind, card.star, card.level, card.grade, mode)
        if (owned.roleBits(Acquisition.DIAMOND) < BigInteger.valueOf(price)) throw Acquisition.Rejected("Not enough Diamonds", 4000)
        checkBag(owned, inputs)
        val returns = resetReturns(card, mode, owned, current, inputs)
        val items = returns.arr("items").map { it.asArr[0].long to it.asArr[1].long }
        val frames = grantAll(owned, items)
        val reward = Acquisition.emptyReward()
        reward["items"] = JArr(items.mapTo(ArrayList()) { (t, c) -> jarr(t, c) })
        val soulKey = SOUL_REWARD.getValue(kind)
        val soul = returns.long(soulKey)
        if (mode == REFORGE && soul != 0L) {
            frames.add(owned.roleAdd(SOUL_ROLE.getValue(kind), soul))
            reward[soulKey] = JInt(soul)
        }
        val gold = returns.long("gold")
        if (gold != 0L) {
            frames.add(owned.roleAdd(Acquisition.GOLD, gold))
            reward["gold"] = JInt(gold)
        }
        val newUids = ArrayList<Long>()
        val heroes = returns.arr("heroes").map { it.long }
        val equips = returns.arr("equips").map { it.long }
        for (template in heroes) {
            val (uid, groups) = owned.grantHero(template)
            newUids.add(uid)
            frames += groups.getValue("add") + groups.getValue("book") + groups.getValue("god") + groups.getValue("activity")
        }
        for (template in equips) {
            val (uid, groups) = owned.grantEquipment(template)
            newUids.add(uid)
            frames += groups.getValue("add") + groups.getValue("book")
        }
        if (heroes.isNotEmpty()) {
            reward["heroes"] = JArr(heroes.mapTo(ArrayList()) { jarr(it) })
            reward["hero_levels"] = JArr(heroes.mapTo(ArrayList()) { jarr(0) })
        }
        if (equips.isNotEmpty()) {
            reward["equips"] = JArr(equips.mapTo(ArrayList()) { jarr(it) })
            reward["equip_levels"] = JArr(equips.mapTo(ArrayList()) { jarr(0) })
            reward["equip_grades"] = JArr(equips.mapTo(ArrayList()) { jarr(0) })
        }
        owned.roleAdd(Acquisition.DIAMOND, -price)
        frames += Shops.diamondAchievement(owned, price, servedTime)
        val plan = JObj()
        var newUid: Long? = newUids.firstOrNull()
        when (kind) {
            "hero" -> {
                owned.removeHero(card.uid)
                frames += listOf(S_BENCH_REMOVE to uidList(card.uid), S_HERO_REMOVE to uidList(card.uid))
            }
            "gear" -> {
                owned.state["equipment"] = JArr((PyDocs.at(owned.state, "equipment") as JArr)
                    .filter { (PyDocs.at(it.asObj, "wire_values") as JArr)[0] != JInt(card.uid) }.toMutableList())
                owned.state["bag_equipment_uids"] = JArr((PyDocs.at(owned.state, "bag_equipment_uids") as JArr)
                    .filter { it != JInt(card.uid) }.toMutableList())
                frames += listOf(S_EQUIP_BAG_REMOVE to uidList(card.uid), S_EQUIP_REMOVE to uidList(card.uid))
            }
            else -> {
                val listed = current.jewelEntriesView ?: throw PyDocs.TypeError("'NoneType' object is not iterable")
                val entries = JArr(listed.filter { (PyDocs.at(it.asObj, "record") as JArr)[0] != JInt(card.uid) }.toMutableList())
                frames.add(S_JEWEL_LIST_REMOVE to WireWriter().number('B', 1).number('I', card.uid).bytes())
                val added = ArrayList<JArr>()
                for (template in returns.arr("jewels")) {
                    val uid = nextJewelUid(owned, listed.map { PyDocs.at(it.asObj, "record") as JArr } + added)
                    val record = jarr(uid, template, 1, 0, 1, 0, 0)
                    entries.add(jobj("record" to record, "tail" to JNull))
                    added.add(record)
                    newUids.add(uid)
                }
                if (added.isNotEmpty()) {
                    val w = WireWriter().number('B', added.size.toLong())
                    for (r in added) w.values("IIIIBBI", r)
                    frames.add(S_JEWEL_LIST_ADD to w.bytes())
                    reward["jewels"] = JArr(returns.arr("jewels").mapTo(ArrayList()) { jarr(it) })
                }
                newUid = newUids.firstOrNull()
                plan["jewel_entries_after"] = JArr(entries.sortedWith { a, b ->
                    PyDocs.compare((a.asObj["record"] as JArr)[0], (b.asObj["record"] as JArr)[0])
                }.toMutableList())
            }
        }
        frames += listOf(S_RESET_RESULT to BattleReport.encodeReward(reward),
            Acquisition.S_ROLE to Acquisition.roleUpdatePayload(listOf(Triple(Acquisition.DIAMOND, owned.role(Acquisition.DIAMOND).long("tag"),
                owned.roleBits(Acquisition.DIAMOND)))))
        plan.putAll(jobj("kind" to kind, "mode" to (if (mode == SACRIFICE) "sacrifice" else "reforge"), "uid" to card.uid,
            "template" to card.template, "level" to card.level, "grade" to card.grade, "price" to price, "returns" to returns,
            "new_uid" to newUid, "evidence_class" to returns["evidence_class"]))
        return Plan(plan, frames)
    }

    private fun uidList(uid: Long): ByteArray = WireWriter().number('B', 1).number('I', uid).bytes()

    /**
     * `ResetSystem::CheckBagIsEnough`: opened item slots − owned item stacks ≥ property 11015 (20), else 70406 (`check_bag`;
     * the stacks counted as `Owned._bag_count(1)`).
     */
    fun checkBag(owned: Owned, inputs: DailyInputs) {
        val opened = PyDocs.int(PyDocs.index(PyDocs.at(owned.state, "item_capacity_values") as JArr, 0))
        val stacks = owned.items.values.count { it.count > 0 && owned.bagOf(it.template) == 1L }
        if (opened - BigInteger.valueOf(stacks.toLong()) < BigInteger.valueOf(inputs.prop(11015, 20))) {
            throw Acquisition.Rejected("Insufficient space, needs 20 slots of space", ERROR_BAG)
        }
    }

    /** max(equipped jewelry ∪ listed) + 1 (`_next_jewel_uid`). */
    private fun nextJewelUid(owned: Owned, listed: List<JArr>): Long {
        val uids = HashSet<Long>(ItemFortify.jewelryView(PyDocs.at(owned.state, "formation") as JArr).keys)
        for (record in listed) uids.add(num(record[0]))
        return (uids.maxOrNull() ?: 0L) + 1
    }

    /**
     * The card description the returns read (`return_card`): hero Astral skill ids, Power Up dev 15–18, stats 4/6/8/10,
     * Rebirth Level 19 / Tier 21, ascension 24; gear / jewel: record fields, enchant = the record's last u32.
     */
    fun returnCard(card: Card, owned: Owned, current: StateStore.Current, inputs: DailyInputs): ResetReturns.Described {
        if (card.kind == "hero") {
            val values = card.values
            val god: JObj = owned.godDocument?.takeIf { it.isNotEmpty() }
                ?: PyDocs.get(current, "god_skills")?.takeIf { Py.truthy(it) }?.let { PyDocs.get(it as JObj, "document") }?.takeIf { Py.truthy(it) } as JObj?
                ?: JObj()
            val heroes = god["heroes"] ?: JArr()
            val entry = (heroes as JArr).firstOrNull { PyDocs.at(it.asObj, "uid") == JInt(card.uid) }
            val skills = if (entry != null) (PyDocs.at(entry.asObj, "skills") as JArr).map { it.asArr[0] }
                else inputs.astralInitialSkills(card.template).map { it.asArr[0] }
            return ResetReturns.Described("hero", card.template, card.level, card.exp, awaken = orDefault(values, 24, 0), godSkills = skills,
                dev = listOf(15L, 16L, 17L, 18L).map { orDefault(values, it, 0) }, stats = listOf(4L, 6L, 8L, 10L).map { orDefault(values, it, 0) },
                rebornLevel = orDefault(values, 19, 0), rebornGrade = orDefault(values, 21, 0))
        }
        val record = card.record!!
        return ResetReturns.Described(card.kind, num(record[1]), num(record[2]), num(record[3]), grade = num(record[4]), superFlag = num(record[5]),
            enchant = num(record[6]), propertyValue = if (num(record[6]) != 0L) inputs.cardPropertyValue(card.kind, record) else null)
    }

    /**
     * What a Sacrifice / Reforge gives back (`reset_returns`): the client's own preview functions with their level gates;
     * the returned hero card comes back at grade 1 of its star / super class on a hero Sacrifice.
     */
    fun resetReturns(card: Card, mode: Long, owned: Owned, current: StateStore.Current, inputs: DailyInputs): JObj {
        val tables = ResetReturns.tablesFor(inputs)
        val described = returnCard(card, owned, current, inputs)
        val level = owned.roleBits(Acquisition.ROLE_LEVEL)
        val result = if (mode == SACRIFICE) ResetReturns.sacrifice(described, tables, level) else ResetReturns.reforge(described, tables, level)
        val heroes = result.arr("heroes")
        if (mode == SACRIFICE && card.kind == "hero" && heroes.isNotEmpty()) {
            val first = heroes[0].long
            heroes[0] = JInt(if (Math.floorMod(first, 100L) != 0L) Math.floorDiv(first, 100L) * 100 + 1 else first)
        }
        result["evidence_class"] = JStr("native_use_calculation_capture_confirmed")
        return result
    }
}

/**
 * `reset_returns.py`: what a Sacrifice / Reforge gives back — the client's `ResetSystem::getDecomposeInfos` /
 * `getRebirthInfos` family and the `Formula::GetReset*Return` functions they call. Every component adds into one item
 * map (created even at 0), so the Reward's item list is merged by id, ascending, zeros included. Arithmetic keeps the
 * binary32 roundings of the client as exact rationals. Tables are read as {int column: int} rows ([Tables]).
 */
object ResetReturns {
    const val U32 = 0xFFFFFFFFL
    private val TEN_K = Fraction.of(10000)
    private val M64 = BigInteger.ONE.shiftLeft(64) - BigInteger.ONE

    /** Exact round-to-nearest-even to binary32, kept as a rational. */
    fun f32(q: Fraction): Fraction = F32.exactFraction(q)
    fun f32(v: Long): Fraction = F32.exactFraction(Fraction.of(v))
    fun fcvtzu(q: Fraction): Long = F32.fcvtzuFraction(q)
    fun fcvtzs(q: Fraction): Long = F32.fcvtzs(q)
    fun f32FromDouble(x: Double): Fraction = F32.fromDouble(x)

    /** C `atoi`: strip, optional sign, the leading digits (0 when none). */
    fun atoi(s: String?): Long {
        val text = PyValues.strip(s ?: "")
        var sign = 1L
        var i = 0
        if (text.isNotEmpty() && (text[0] == '+' || text[0] == '-')) {
            sign = if (text[0] == '-') -1L else 1L
            i = 1
        }
        val digits = StringBuilder()
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (!PyText.isDigit(cp)) break
            digits.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return if (digits.isEmpty()) 0L else sign * PyValues.parseInt(digits.toString()).longValueExact()
    }

    /** The key column of every table read. */
    val KEYS = mapOf("property" to 101, "hero" to 101, "heroexp" to 101, "equip" to 101, "equipexp" to 101, "jewelry" to 101,
        "jewelry_exp" to 101, "qianghua_itemexp" to 101, "resetherojinhua" to 401, "resetequipjinhua" to 601,
        "resetjewelryjinhua" to 601, "resetheropeiyang" to 101, "resetequipfumo" to 101, "resetjewelryfumo" to 101,
        "resetsuper" to 501, "resetgodskill" to 301, "resetheroreborn" to 101, "resetherojuexing" to 101,
        "renascence" to 501, "zhuansheng" to 102)

    /** A config row; an absent column reads 0 (the client's zero-initialised record). */
    class IntRow(private val cells: Map<Int, Long>) {
        operator fun get(column: Int): Long = cells[column] ?: 0L
    }

    /** std::map semantics of the client configs: ascending key, the first row of a key wins, key 0 dropped. */
    class Tables(private val source: (String) -> List<Map<Int, String>>) {
        private val cache = HashMap<String, Map<Long, IntRow>>()

        fun rows(name: String): Map<Long, IntRow> = synchronized(cache) {
            cache.getOrPut(name) {
                val out = HashMap<Long, IntRow>()
                val keyColumn = KEYS[name] ?: throw PyDocs.KeyError("'$name'")
                for (raw in source(name)) {
                    val k = atoi(raw[keyColumn]) and U32
                    if (k != 0L && k !in out) out[k] = IntRow(raw.mapValues { atoi(it.value) })
                }
                java.util.TreeMap(out)
            }
        }

        fun get(name: String, key: Long): IntRow? = rows(name)[key and U32]

        fun prop(pid: Long): Long = get("property", pid)?.let { it[102] } ?: 0L
    }

    private val tablesCache = WeakHashMap<DailyInputs, Tables>()

    /** One [Tables] view per input loader (the daily inputs' catalog rows, digit headers only). */
    fun tablesFor(inputs: DailyInputs): Tables = synchronized(tablesCache) {
        tablesCache.getOrPut(inputs) {
            Tables { name ->
                inputs.tableRows(name).map { row ->
                    val cells = LinkedHashMap<Int, String>()
                    for ((c, v) in row.fields()) if (PyValues.isDigit(c)) cells[PyValues.parseInt(c).intValueExact()] = v
                    cells
                }
            }
        }
    }

    /** The card as the returns read it (`return_card`'s dict). */
    class Described(val kind: String, val template: Long, val level: Long, val exp: Long, val awaken: Long = 0,
                    val godSkills: List<JValue> = emptyList(), val dev: List<Long> = listOf(0, 0, 0, 0), val stats: List<Long> = emptyList(),
                    val rebornLevel: Long = 0, val rebornGrade: Long = 0, val grade: Long = 0, val superFlag: Long = 0, val enchant: Long = 0,
                    val propertyValue: Long? = null)

    /** The native Reward: the item map, the card vectors, scalars and a trace of what added what. */
    class Out {
        val items = LinkedHashMap<Long, Long>()
        val heroes = ArrayList<Long>()
        val equips = ArrayList<Long>()
        val jewels = ArrayList<Long>()
        var gold = 0L
        var soulHero = 0L
        var soulEquip = 0L
        var soulJewel = 0L
        val trace = ArrayList<Triple<String, Long, Long>>()

        fun add(item: Long, count: Long, why: String) {
            items[item] = ((items[item] ?: 0L) + count) and U32
            trace.add(Triple(why, item, count))
        }

        fun result(): JObj = jobj("items" to items.entries.sortedBy { it.key }.map { jarr(it.key, it.value) }, "heroes" to heroes,
            "equips" to equips, "jewels" to jewels, "gold" to gold, "soul_hero" to soulHero, "soul_equip" to soulEquip,
            "soul_jewel" to soulJewel, "trace" to trace.map { jarr(it.first, it.second, it.third) })
    }

    /** `GetExpOfHeroLevel`. */
    fun heroLevelExp(t: Tables, tpl: Long, level: Long): Long {
        val h = t.get("hero", Math.floorDiv(tpl, 1000L))
        val e = t.get("heroexp", level and 0xFFFF)
        if (h == null || e == null) return -1
        return fcvtzs(f32FromDouble((e[102] and U32).toDouble() * (h[137] and U32).toDouble() / 10000.0))
    }

    /** `GetExpOfEquipLevel`. */
    fun equipLevelExp(t: Tables, tpl: Long, level: Long): Long {
        val c = t.get("equip", tpl)
        val e = t.get("equipexp", level and 0xFFFF)
        if (c == null || e == null) return -1
        return fcvtzs(f32FromDouble(e[102].toDouble() * (c[113] and U32).toDouble() / 10000.0))
    }

    /** `GetExpOfJewelUpLevel`. */
    fun jewelLevelExp(t: Tables, tpl: Long, level: Long): Long {
        val c = t.get("jewelry", tpl)
        val e = t.get("jewelry_exp", level and 0xFFFF)
        if (c == null || e == null) return -1
        return fcvtzs(f32FromDouble(e[102].toDouble() * (c[115] and U32).toDouble() / 10000.0))
    }

    private class Upgrade(val props: List<Long>, val pctProp: Long, val typ: Long, val helper: (Tables, Long, Long) -> Long)

    private val UPGRADE = mapOf(
        "hero" to Upgrade(listOf(11003, 11004, 11005), 300301, 1, ::heroLevelExp),
        "gear" to Upgrade(listOf(11007, 11008, 11009), 300302, 2, ::equipLevelExp),
        "jewel" to Upgrade(listOf(11010, 11011, 11012), 300303, 3, ::jewelLevelExp))

    /** §3.1 level return. */
    fun upgradeReturn(t: Tables, card: Described, out: Out) {
        val u = UPGRADE.getValue(card.kind)
        val hero = card.kind == "hero"
        val level = card.level and (if (hero) 0xFFFFL else U32)
        var total = BigInteger.ZERO
        var lv = 1L
        while (lv <= level) {
            total += BigInteger.valueOf(u.helper(t, card.template, lv))
            lv++
        }
        val exp = if (hero) card.exp and U32 else (card.exp and U32).toInt().toLong()
        val pct = t.prop(u.pctProp).let { if (it != 0L) it else 10000L }
        var pool = PyInt.floorDiv(((total + BigInteger.valueOf(exp)).and(M64) * BigInteger.valueOf(pct)).and(M64), BigInteger.valueOf(10000))
        for (pid in u.props) {
            val item = t.prop(pid)
            val row = t.rows("qianghua_itemexp").values.firstOrNull { (it[102] and 0xFF) == u.typ && it[103] == item } ?: return
            val v = row[104] and U32
            out.add(item, if (v != 0L) pool.divide(BigInteger.valueOf(v)).longValueExact() else 0L, "upgrade")
            if (v != 0L) pool = pool.mod(BigInteger.valueOf(v))
        }
    }

    /** §3.2 evolve return (the current-grade row). */
    fun evolveReturn(t: Tables, card: Described, out: Out) {
        if (card.kind == "hero") {
            val h = t.get("hero", Math.floorDiv(card.template, 1000L)) ?: return
            val row = t.rows("resetherojinhua").values.firstOrNull {
                (it[402] and 0xFF) == (h[104] and 0xFF) && (it[403] and 0xFF) == Math.floorMod(card.template, 100L)
            } ?: return
            out.add(row[404], row[405], "evolve")
            out.add(row[406], row[407], "evolve")
            val cls = h[103] and 0xFF
            if (cls in 1L..3L) out.add(row[(407 + cls).toInt()], row[411], "evolve(class)")
            return
        }
        val c = t.get(if (card.kind == "gear") "equip" else "jewelry", card.template) ?: return
        val tab = if (card.kind == "gear") "resetequipjinhua" else "resetjewelryjinhua"
        val row = t.rows(tab).values.firstOrNull { (it[602] and 0xFF) == (c[106] and 0xFF) && (it[603] and 0xFF) == (card.grade and 0xFF) } ?: return
        for (i in 0 until 3) out.add(row[604 + 2 * i], row[605 + 2 * i], "evolve")
    }

    /** §3.4 three-tier split. */
    fun tierReturn(row: IntRow, value: Long, maximum: Long, out: Out, why: String) {
        var current = value
        val s8 = f32(maximum)
        for (i in 0 until 3) {
            val s10 = f32(current)
            val s9 = f32(f32(row[103 + 4 * i] and U32) / TEN_K)
            val s11 = f32(s9 * s8)
            val take = if (s11 < s10) s11 else s10
            val div = f32(f32(row[104 + 4 * i] and U32) / TEN_K)
            out.add(row[105 + 4 * i], fcvtzu(f32(f32(take / div) * f32(row[106 + 4 * i] and U32))), why)
            if (!(s11 < s10)) break
            current = fcvtzu(f32(s10 - s9 * s8))                      // fmsub, one rounding
        }
    }

    /** §3.4 Power Up ("foster"). */
    fun fosterReturn(t: Tables, card: Described, out: Out) {
        val p = f32(t.prop(156))
        val dev = card.dev
        val maxes = card.stats.zip(dev).map { (s, d) -> fcvtzu(f32(f32(p * f32((s - d) and U32)) / TEN_K)) }
        if (0L in maxes) return
        val slot = mapOf(1L to 0, 7L to 1, 8L to 2, 6L to 3)
        for (row in t.rows("resetheropeiyang").values) {
            val k = slot[row[102] and 0xFF]
            if (k != null) tierReturn(row, dev[k] and U32, maxes[k], out, "foster")
        }
    }

    /** §3.5 enchant (skipped when 0). */
    fun magicReturn(t: Tables, card: Described, out: Out) {
        val enchant = card.enchant and U32
        if (enchant == 0L) return
        val gear = card.kind == "gear"
        val propertyValue = card.propertyValue ?: throw PyDocs.KeyError("'property_value'")
        val mx = fcvtzu(f32(f32(f32(t.prop(if (gear) 300104 else 300105)) / TEN_K) * f32((propertyValue - enchant) and U32)))
        val c = t.get(if (gear) "equip" else "jewelry", card.template)
        if (mx == 0L || c == null) return
        for (row in t.rows(if (gear) "resetequipfumo" else "resetjewelryfumo").values) {
            if ((row[102] and 0xFF) == (c[601] and 0xFF)) tierReturn(row, enchant, mx, out, "enchant")
        }
    }

    /** §3.3 Astral. */
    fun godSkillReturn(t: Tables, card: Described, out: Out) {
        var total = 0L
        for (skill in card.godSkills.sortedWith(PyDocs.ORDER)) {
            val row = t.get("resetgodskill", PyDocs.long(skill)) ?: return
            total = (total + row[303]) and U32
        }
        out.add(t.prop(11006), total, "god_skill")
    }

    /** §3.6 fusion ("Awaken" in the UI). */
    fun superReturn(t: Tables, card: Described, out: Out, withCards: Boolean) {
        val on: Boolean
        val star: Long
        val kind: Long
        if (card.kind == "hero") {
            val h = t.get("hero", Math.floorDiv(card.template, 1000L))
            on = Math.floorMod(Math.floorMod(Math.floorDiv(card.template, 100L), 10L), 3L) == 1L
            star = h?.let { it[104] and 0xFF } ?: 0L
            kind = 1
        } else {
            val c = t.get(if (card.kind == "gear") "equip" else "jewelry", card.template)
            on = (card.superFlag and 0xFF) == 1L
            star = c?.let { it[106] and 0xFF } ?: 0L
            kind = if (card.kind == "gear") 2 else 3
        }
        if (!on) return
        val row = t.rows("resetsuper").values.firstOrNull { (it[502] and 0xFF) == kind && (it[503] and 0xFF) == star } ?: return
        if (withCards) {
            val cid = when (kind) { 1L -> (row[504] * 1000) or 1L; 2L -> row[505]; else -> row[506] }
            val target = when (kind) { 1L -> out.heroes; 2L -> out.equips; else -> out.jewels }
            var n = row[507] and U32
            while (n-- > 0) target.add(cid)
        }
        out.add(row[508], row[509], "super")
    }

    /** §3.7; the base-form card or null. */
    fun rebornReturn(t: Tables, card: Described, out: Out): Long? {
        val base = Math.floorDiv(card.template, 1000L)
        val row = t.rows("zhuansheng").values.firstOrNull { it[103] == base || it[104] == base } ?: return null
        for (i in 0 until 4) if (row[202 + 2 * i] != 0L) out.add(row[201 + 2 * i], row[202 + 2 * i], "reborn")
        if (row[211] != 0L) out.add(if (row[103] == base) row[209] else row[210], row[211], "reborn")
        val r2 = t.rows("resetheroreborn").values.firstOrNull {
            (it[102] and 0xFFFF) == (card.rebornGrade and 0xFFFF) && it[103] == (card.rebornLevel and 0xFFFF)
        }
        if (r2 != null) for (i in 0 until 4) if (r2[202 + 2 * i] != 0L) out.add(r2[201 + 2 * i], r2[202 + 2 * i], "reborn_prop")
        return (row[102] * 1000) or 1L
    }

    /** §3.8 `GetResetHeroAwakenReturn`: adds the materials, sets the Gold, returns the card count. */
    fun awakenFormula(t: Tables, card: Described, out: Out): Long {
        val h = t.get("hero", Math.floorDiv(card.template, 1000L))
        val row = if (h != null) t.get("resetherojuexing", (card.awaken and 0xFFFF) + (h[104] and 0xFF) * 100) else null
        if (row == null) return 0
        for (i in 0 until 3) out.add(row[201 + 2 * i], row[202 + 2 * i], "awaken")
        out.gold = row[401] and U32                                    // SetGold, not add
        return row[301] and 0xFF
    }

    /** `GetHeroAwakeResourseCard`. */
    fun awakenResourceCard(t: Tables, tpl: Long): Long = t.rows("zhuansheng").values.firstOrNull { it[104] == tpl }?.let { it[102] } ?: tpl

    /** §3.10 soul points (the last matching row). */
    fun renaSoul(t: Tables, typ: Long, sup: Long, star: Long, n: Long = 0): Long? {
        var row: IntRow? = null
        for (r in t.rows("renascence").values) {
            if ((r[502] and 0xFF) == typ && (r[503] and 0xFF) == sup && (r[504] and 0xFF) == star) row = r
        }
        return row?.let { (it[506] + n * it[507]) and U32 }
    }

    private val GATES = mapOf(("hero" to "god_skill") to 4005L, ("gear" to "evolve") to 215L, ("jewel" to "*") to 955L)

    private fun gate(t: Tables, kind: String, comp: String, level: BigInteger?): Boolean {
        val pid = GATES[kind to comp] ?: GATES[kind to "*"]
        return level == null || pid == null || level >= BigInteger.valueOf(t.prop(pid))
    }

    /** Mode 0, the `getDecomposeInfos` order. */
    fun sacrifice(card: Described, t: Tables, playerLevel: BigInteger? = null): JObj {
        val out = Out()
        val k = card.kind
        if (k == "hero") {
            if (gate(t, k, "god_skill", playerLevel)) godSkillReturn(t, card, out)
            superReturn(t, card, out, true)
            fosterReturn(t, card, out)
            evolveReturn(t, card, out)
            upgradeReturn(t, card, out)
            val base = rebornReturn(t, card, out)
            out.heroes.add(base ?: card.template)
            if ((card.awaken and 0xFFFF) != 0L) {
                val resource = awakenResourceCard(t, card.template)
                var n = awakenFormula(t, card, out)
                while (n-- > 0) out.heroes.add(resource)
            }
        } else {
            val g = gate(t, k, "*", playerLevel)
            if (g) {
                magicReturn(t, card, out)
                superReturn(t, card, out, true)
            }
            if (g && gate(t, k, "evolve", playerLevel)) evolveReturn(t, card, out)
            if (g) {
                upgradeReturn(t, card, out)
                (if (k == "gear") out.equips else out.jewels).add(card.template)
            }
        }
        return out.result()
    }

    /** Mode 1, the `getRebirthInfos` order. */
    fun reforge(card: Described, t: Tables, playerLevel: BigInteger? = null): JObj {
        val out = Out()
        val k = card.kind
        if (k == "hero") {
            if (gate(t, k, "god_skill", playerLevel)) godSkillReturn(t, card, out)
            superReturn(t, card, out, false)
            fosterReturn(t, card, out)
            evolveReturn(t, card, out)
            upgradeReturn(t, card, out)
            rebornReturn(t, card, out)
            val n = awakenFormula(t, card, out)
            val h = t.get("hero", Math.floorDiv(card.template, 1000L))
            out.soulHero = renaSoul(t, 1, Math.floorDiv(Math.floorMod(card.template, 1000L), 100L), h?.let { it[104] and 0xFF } ?: 0L, n) ?: 0L
        } else {
            val g = gate(t, k, "*", playerLevel)
            if (g) {
                magicReturn(t, card, out)
                superReturn(t, card, out, false)
            }
            if (g && gate(t, k, "evolve", playerLevel)) evolveReturn(t, card, out)
            if (g) upgradeReturn(t, card, out)
            val c = t.get(if (k == "gear") "equip" else "jewelry", card.template)
            val soul = renaSoul(t, if (k == "gear") 2 else 3, if ((card.superFlag and 0xFF) != 0L) 1 else 0, c?.let { it[106] and 0xFF } ?: 0L) ?: 0L
            if (k == "gear") out.soulEquip = soul else out.soulJewel = soul
        }
        return out.result()
    }
}
