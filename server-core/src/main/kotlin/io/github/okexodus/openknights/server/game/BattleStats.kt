package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.F32
import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JNull
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.asArr
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.hexBytes
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.protocol.WireReader
import io.github.okexodus.openknights.protocol.WireWriter
import io.github.okexodus.openknights.server.store.StateStore
import java.math.BigInteger

/**
 * Effective battle stats of an owned hero in a lineup slot, and the team Power (`battle_stats.py`), ported from the
 * client's own chain (ARM64 4.4.9):
 *
 *     HeroBenchLine::GetBattleScore               sum over formation slots 0..5 holding a hero
 *       Formula::GetBattleSlotAbility             GetHeroAbility + 6 × (gear, runes, jewel) + secondary team
 *         Formula::GetHeroAbility                 wire 4/6/8/10 + album + tech + title + medals + [243] + god skills
 *                                                 + totem (binary32) + zodiac
 *       Formula::HeroAddCombEffect                hero_zuhe combos on the pre-combo stats, final score
 *
 * Score (Formula::GetCombat): u64 2*HP + 21*DEF + 15*(ATK + Unique) + 20*u32(field 22 + rune 25) + 25*u32(field 23 +
 * rune 26), every stat a u32 (32-bit wrap on every integer add). Every float step is binary32 in the instructions'
 * order (one fused multiply-add in the totem value, FCVTZU truncation).
 *
 * `state` is the decoded opcode-18 dictionary, `inputs` the catalog ([AcquisitionInputs.battleStatTables],
 * [AcquisitionInputs.propertyValueInputs]), `world` the per-character stat inputs outside opcode 18 (album_activated,
 * quest_points, god_skills {uid: [[skill, progress]]}, totems {entries, lineup}, secondary_team [[position, uid]],
 * leader_uid). A term whose input is absent contributes nothing and is listed in `unresolved`.
 *
 * The universal Power: [statWorldOf] builds a character's complete world from its own save with the session's frame
 * builders; [powerOf] = [teamPower]'s power is the one Power every participant list shows. The campaign battle actors
 * and the C3809 lineup view belong to the campaign group.
 */
object BattleStats {
    const val U32 = 0xFFFFFFFFL
    private val U64: BigInteger = BigInteger.ONE.shiftLeft(64) - BigInteger.ONE
    const val HP = 0
    const val ATK = 1
    const val DEF = 2
    const val UNQ = 3
    val STAT_INDEX = mapOf(1L to HP, 7L to ATK, 8L to DEF, 6L to UNQ)
    val COMBO_INDEX = mapOf(201L to HP, 202L to UNQ, 203L to ATK, 204L to DEF)
    const val REBORN_ATK_GEM = 25L
    const val REBORN_DEF_GEM = 26L
    val MAIN_SLOTS = 0L until 6L
    const val SECONDARY_POSITIONS = 12
    val ADVANCE_TIERS = listOf(3L, 6L, 9L)
    const val ALL_CLASSES = 4L
    const val EVIDENCE = "native_formula_port_v1"
    val BATTLE_EXCLUDED_ALBUM_TYPES = setOf(3L)
    val MODES = listOf("power", "battle")

    val WORLD_KEYS = linkedMapOf(
        "album_activated" to "world['album_activated']: the S548 activated tujian family set (PlayerInfo+0x238)",
        "quest_points" to "world['quest_points']: the S320 trailing u32 (RoleBase::SetTaskPoint, +0x130)",
        "god_skills" to "world['god_skills']: the S2848/S2850 god-skill ids per hero uid (HeroBase+0x98)",
        "totems" to "world['totems']: the S2880 totem list and lineup id (PlayerInfo+0x1f98 / +0x1fb0)",
        "secondary_team" to "world['secondary_team']: the S3745 (position, hero uid) list (PlayerInfo+0x1f38)",
        "leader_uid" to "world['leader_uid']: the S2240 leader hero uid (PlayerInfo+0x138, zodiac target)",
    )

    // --- binary32 arithmetic ------------------------------------------------------------------------------------------

    /** `f32(value)`: binary32 nearest-even (a finite overflow raises like `struct.pack`). */
    fun f32(value: Double): Double = F32.round(value)

    fun ucvtf(value: Long): Double = F32.ucvtf(value)

    /** ARM64 FMADD Sd = Sa*Sb + Sc with a single rounding. */
    fun fmadd32(a: Double, b: Double, c: Double): Double = F32.fmadd(a, b, c)

    fun fcvtzu(value: Double): Long = F32.fcvtzu(value)

    /** Formula::GetCombat: u32 inputs, u64 result. */
    fun combat(hp: Long, atk: Long, deff: Long, unique: Long, rebornAtk: Long = 0, rebornDef: Long = 0): Long =
        2 * (hp and U32) + 21 * (deff and U32) + 15 * ((atk and U32) + (unique and U32)) + 20 * (rebornAtk and U32) + 25 * (rebornDef and U32)

    private fun add(stats: LongArray, index: Int, value: Long) {
        stats[index] = (stats[index] + value) and U32
    }

    private fun addTyped(stats: LongArray, statType: Long, value: Long, terms: JObj? = null, name: String? = null): Boolean {
        val index = STAT_INDEX[statType] ?: return false
        add(stats, index, value)
        if (terms != null) {
            val term = terms.getOrPut(name!!) { jarr(0, 0, 0, 0) } as JArr
            term[index] = JInt(((term[index] as JInt).value.toLong() + value) and U32)
        }
        return true
    }

    private fun addTerm(terms: JObj, name: String, index: Int, value: Long) {
        val term = terms.getOrPut(name) { jarr(0, 0, 0, 0) } as JArr
        term[index] = JInt(((term[index] as JInt).value.toLong() + value) and U32)
    }

    // --- state views ---------------------------------------------------------------------------------------------------

    private fun bits(value: JValue?): BigInteger? = ((value as? JObj)?.get("bits") as? JInt)?.value

    /** `hero_fields(state)`: {uid: {field id: bits}} of the owned heroes (a hero without a nonzero field 0 is left out). */
    fun heroFields(state: JObj): LinkedHashMap<Long, Map<Long, BigInteger?>> {
        val out = LinkedHashMap<Long, Map<Long, BigInteger?>>()
        for (fields in (state["heroes"] as? JArr) ?: JArr()) {
            val values = LinkedHashMap<Long, BigInteger?>()
            for (f in fields.asArr) values[f.asObj.long("id")] = bits(f.asObj["value"])
            val uid = values[0L]
            if (uid != null && uid.signum() != 0) out[uid.toLong()] = values
        }
        return out
    }

    private fun role(state: JObj, fieldId: Long, default: Long = 0): Long {
        for (field in (state["role_properties"] as? JArr) ?: JArr()) {
            if (field.asObj.long("id") == fieldId) {
                val b = bits(field.asObj["value"])
                return if (b == null || b.signum() == 0) default else b.toLong()
            }
        }
        return default
    }

    private fun slotOf(state: JObj, slotId: Long): JObj? =
        ((state["formation"] as? JArr) ?: JArr()).map { it.asObj }.firstOrNull { it.long("slot_id") == slotId }

    private class JewelBlock(val uid: Long, val config: Long, val exp: Long, val level: Long, val grade: Long, val superFlag: Long, val extra: Long)

    private fun jewelBlock(rawHex: String): JewelBlock {
        val raw = rawHex.hexBytes()
        val r = WireReader(raw)
        val uid = r.u32(); val config = r.u32(); val exp = r.u32(); val level = r.u32()
        r.offset = 20
        val extra = r.u32()
        return JewelBlock(uid, config, exp, level, raw[16].toLong() and 0xFF, raw[17].toLong() and 0xFF, extra)
    }

    private fun low32(value: BigInteger?): Long = (value ?: BigInteger.ZERO).and(BigInteger.valueOf(U32)).toLong()

    /** `int(x)` of a float: toward zero. */
    private fun trunc(x: Double): Long = io.github.okexodus.openknights.exact.PyInt.truncate(x).toLong()
    private fun orZero(value: BigInteger?): BigInteger = value ?: BigInteger.ZERO

    // --- the catalog rows, typed once per table set --------------------------------------------------------------------

    private class Totem(val group: Long, val stats: List<LongArray>)
    private class Combo(val key: Long, val enabled: Long, val hero: Long, val partners: List<LongArray>, val bonuses: List<LongArray>)
    private class Photo(val kind: Long, val family: Long, val members: List<Long>, val pairs: List<LongArray>)
    private class ItemRow(val main: Long, val cls: Long, val pairs: List<LongArray>)

    private class Tables(t: JObj) {
        fun longs(v: JValue): LongArray = v.asArr.map { it.long }.toLongArray()
        fun <V> keyed(obj: JObj, f: (JValue) -> V): Map<Long, V> = LinkedHashMap<Long, V>().also { m -> for ((k, v) in obj) m[k.toLong()] = f(v) }

        val title = keyed(t.obj("title")) { longs(it) }
        val technology = keyed(t.obj("technology")) { longs(it) }
        val questmedal = t.arr("questmedal").map { longs(it) }
        val godSkill = keyed(t.obj("god_skill")) { longs(it) }
        val property: Map<Long, Long?> = keyed(t.obj("property")) { (it as? JInt)?.value?.toLong() }
        val totem = keyed(t.obj("totem")) { v -> Totem(v.asObj.long("group"), v.asObj.arr("stats").map { longs(it) }) }
        val totemAdv = t.arr("totem_adv").map { longs(it) }
        val zodiac = keyed(t.obj("zodiac")) { longs(it) }
        val photo = t.arr("photo").map { v -> val a = v.asArr; Photo(a[0].long, a[1].long, a[2].asArr.map { it.long }, a[3].asArr.map { longs(it) }) }
        val equip = keyed(t.obj("equip")) { v -> val a = v.asArr; ItemRow(a[0].long, a[1].long, a[2].asArr.map { longs(it) }) }
        val jewel = keyed(t.obj("jewel")) { v -> val a = v.asArr; ItemRow(a[0].long, a[1].long, a[2].asArr.map { longs(it) }) }
        val gem = keyed(t.obj("gem")) { longs(it) }
        val secondaryEffect = t.arr("secondary_effect").map { longs(it) }
        val combo = t.arr("combo").map { v -> val c = v.asObj
            Combo(c.long("key"), c.long("enabled"), c.long("hero"), c.arr("partners").map { longs(it) }, c.arr("bonuses").map { longs(it) }) }
        val heroClass = keyed(t.obj("hero_class")) { it.long }
    }

    private var typedSource: JObj? = null
    private var typed: Tables? = null

    @Synchronized
    private fun tablesOf(inputs: AcquisitionInputs): Tables {
        val source = inputs.battleStatTables()
        if (typedSource !== source) {
            typed = Tables(source)
            typedSource = source
        }
        return typed!!
    }

    // --- one evaluation context ------------------------------------------------------------------------------------------

    private class Ability(val stats: LongArray, val terms: JObj, val score: Long, val scoreWritten: Boolean)
    private class SlotResult(val stats: LongArray, val terms: JObj, val score: Long, val gemReborn: LongArray)

    /** One evaluation context (one character's state + world); caches per-hero GetHeroAbility results. */
    private class Model(val state: JObj, val inputs: AcquisitionInputs, world: JObj?, val battle: Boolean = false) {
        val world: JObj = world ?: JObj()
        val totemInteger = battle
        val albumExcluded: Set<Long> = if (battle) BATTLE_EXCLUDED_ALBUM_TYPES else emptySet()
        val t: Tables = tablesOf(inputs)
        val heroes = heroFields(state)
        val equipment = LinkedHashMap<Long, List<Long>>().also { m ->
            for (e in (state["equipment"] as? JArr) ?: JArr()) {
                val wire = e.asObj.arr("wire_values").map { it.long }
                m[wire[0]] = wire
            }
        }
        val subsystems: JObj? = state["subsystems"] as? JObj
        val unresolved = JArr()
        private val seen = HashSet<Pair<String, String>>()
        private val ability = HashMap<Long, Ability>()
        private val propCache = HashMap<Triple<String, Long, Long>, JObj?>()
        val roleSlots = LinkedHashMap<Long, JObj>().also { m ->
            for (s in (state["formation"] as? JArr) ?: JArr()) {
                val slot = s.asObj
                val id = slot.long("slot_id")
                if (id in MAIN_SLOTS && Py.truthy(slot["hero_uid"])) m[id] = slot
            }
        }
        val secondary: LongArray? = secondarySlots()
        private var totemCache: LongArray? = null

        fun need(term: String, needs: String, note: String = "") {
            if (seen.add(term to needs)) unresolved.add(jobj("term" to term, "needs" to needs, "class" to "UNRES", "note" to note))
        }

        fun worldValue(key: String, term: String): JValue? {
            val value = world[key]
            if (value == null || value == JNull) {
                need(term, WORLD_KEYS.getValue(key), "input not supplied; the term contributes nothing")
                return null
            }
            return value
        }

        fun sub(name: String, term: String): JObj? {
            val subs = subsystems
            if (subs == null || subs.isEmpty() || name !in subs) {
                need(term, "state['subsystems']['$name'] (full opcode-18 parse)", "section absent")
                return null
            }
            return subs[name] as? JObj
        }

        private fun secondarySlots(): LongArray? {
            val team = world["secondary_team"]
            if (team == null || team == JNull) return null
            val slots = LongArray(SECONDARY_POSITIONS)
            for (entry in team.asArr) {
                val pair = entry.asArr
                if (pair.size != 2) throw PyValues.ValueError("secondary_team entries are (position, uid) pairs")
                val position = pair[0].long
                if (position in 0 until SECONDARY_POSITIONS) slots[position.toInt()] = pair[1].long
            }
            return slots
        }

        /** Formula::GetTotemAbility (role-wide). */
        fun totemAbility(): LongArray {
            totemCache?.let { return it }
            val totems = worldValue("totems", "totem")
            val out = longArrayOf(0, 0, 0, 0)
            val p4001 = t.property[4001L]
            val p4007 = t.property[4007L]
            if (totems != null && p4001 != null && p4007 != null) {
                val lineup = (totems.asObj["lineup"] as? JInt)?.value?.toLong() ?: 0L
                val tenThousand = f32(10000.0)
                val entries = ((totems.asObj["entries"] as? JArr) ?: JArr()).map { e -> val a = e.asArr; Triple(a[0].long, a[1].long, a[2].long) }
                    .sortedWith(compareBy({ it.first }, { it.second }, { it.third }))
                for ((ident, grade, level) in entries) {
                    val row = t.totem[ident] ?: continue
                    var potRate = 0L
                    if (row.group != 0L) potRate = t.totemAdv.filter { it[1] == row.group && it[0] < grade }.sumOf { it[2] }
                    val pot = f32(f32(ucvtf(potRate) / tenThousand) + f32(1.0))
                    val local = longArrayOf(0, 0, 0, 0)
                    for (stat in row.stats) {
                        val index = STAT_INDEX[stat[0]] ?: continue
                        val grown = ucvtf(((level - 1) * stat[2]) and U32)
                        add(local, index, fcvtzu(fmadd32(grown, pot, ucvtf(stat[1]))))
                    }
                    val ratio = f32(ucvtf(if (lineup == ident) p4001 else p4007) / tenThousand)
                    val gained = local.map { fcvtzu(f32(ratio * ucvtf(it))) }
                    for (i in 0 until 4) add(out, i, gained[i])
                }
            }
            totemCache = out
            return out
        }

        /** Formula::GetHeroAbility. */
        fun heroAbility(uid: Long): Ability {
            ability[uid]?.let { return it }
            val hero = heroes[uid] ?: throw PyValues.ValueError("Hero uid $uid is not owned")
            val stats = longArrayOf(low32(hero[4L]), low32(hero[6L]), low32(hero[8L]), low32(hero[10L]))
            val terms = jobj("wire" to stats.toList())

            // album / collections (PhotoConfig): types 1 hero bases, 2 equip configs, 3 jewelry configs
            val activated = worldValue("album_activated", "album")
            if (activated != null) {
                val hc = sub("hero_collection", "album")
                val ec = sub("equip_collection", "album")
                val jc = sub("jewelry_collection", "album")
                if (hc != null && ec != null && jc != null) {
                    fun firsts(section: JObj) = section.arr("entries").map { it.asObj.arr("wire_values")[0].long }
                    val sets = mapOf(1L to firsts(hc).map { Math.floorDiv(it, 1000L) }.toSet(), 2L to firsts(ec).toSet(), 3L to firsts(jc).toSet())
                    val families = activated.asArr.map { it.long }.toSet()
                    for (photo in t.photo) {
                        if (photo.kind in albumExcluded) continue
                        val set = sets[photo.kind] ?: continue
                        if (photo.family in families && photo.members.all { it in set }) {
                            for (pair in photo.pairs) addTyped(stats, pair[0], pair[1], terms, "album")
                        }
                    }
                }
            }

            // technology: level × value per (id, level) of the S18 technologies list
            val techs = sub("technologies", "technology")
            if (techs != null) {
                for (e in techs.arr("entries")) {
                    val wire = e.asObj.arr("wire_values")
                    if (wire.size != 2) throw PyValues.ValueError("technology entries are (id, level) pairs")
                    val row = t.technology[wire[0].long] ?: continue
                    addTyped(stats, row[0], (wire[1].long * row[1]) and U32, terms, "technology")
                }
            }

            // title row of role property 22 (exact key)
            val title = t.title[role(state, 22)]
            if (title != null) {
                for ((index, value) in listOf(HP to title[0], UNQ to title[1], ATK to title[2])) {
                    add(stats, index, value)
                    addTerm(terms, "title", index, value)
                }
            }

            // quest medals: rows in key order while threshold (301) <= task point
            val points = worldValue("quest_points", "quest_medal")
            if (points != null) {
                val p = points.long and U32
                for (row in t.questmedal) {
                    if ((row[1] and U32) > p) break
                    addTyped(stats, row[2], row[3], terms, "quest_medal")
                    addTyped(stats, row[4], row[5], terms, "quest_medal")
                }
            }

            val p243 = t.property[243L]
            val p4001 = t.property[4001L]
            var scoreWritten = p243 != null
            if (scoreWritten) {
                if (p243 != 0L) need("property_243", "the PlayerInfo+0x118 hero vector", "property 243 is non-zero")
                val god = worldValue("god_skills", "god_skill")
                if (god != null) {
                    val skills = (god.asObj[uid.toString()] as? JArr) ?: JArr()
                    for (skill in skills) {
                        val id = if (skill is JArr) skill[0].long else skill.long
                        val row = t.godSkill[id] ?: continue
                        addTyped(stats, row[0], row[1], terms, "god_skill")
                    }
                }
                // totem (role-wide, binary32; rounds the whole stat through binary32 even when the totem adds 0)
                val totem = totemAbility()
                if (p4001 == null) {
                    scoreWritten = false
                } else {
                    val factor = ucvtf(p4001)
                    val tenThousand = f32(10000.0)
                    val before = stats.copyOf()
                    for (i in 0 until 4) {
                        val gained = f32(f32(factor * ucvtf(totem[i])) / tenThousand)
                        stats[i] = if (totemInteger) (stats[i] + fcvtzu(gained)) and U32 else fcvtzu(f32(gained + ucvtf(stats[i])))
                    }
                    terms["totem"] = JArr((0 until 4).mapTo(ArrayList()) { JInt(stats[it] - before[it]) })
                    // zodiac (GetZodiacAbility): only the hero whose uid == PlayerInfo+0x138 (S2240)
                    val leader = worldValue("leader_uid", "zodiac")
                    val xing = sub("xinggong", "zodiac")
                    if (leader != null && xing != null && leader.long == uid) {
                        for (e in xing.obj("entries").arr("entries")) {
                            val wire = e.asObj.arr("wire_values")
                            if (wire.size != 3) throw PyValues.ValueError("zodiac entries are (id, level, value) triples")
                            val row = t.zodiac[wire[0].long] ?: continue
                            val level = wire[1].long
                            val value = if (level == 0L && wire[2].long == 0L) 0L else (row[1] + row[2] * level) and U32
                            addTyped(stats, row[0], value, terms, "zodiac")
                        }
                    }
                }
            }
            val score = if (scoreWritten) combat(stats[0], stats[1], stats[2], stats[3], low32(hero[22L]), low32(hero[23L])) else 0L
            val result = Ability(stats, terms, score, scoreWritten)
            ability[uid] = result
            return result
        }

        fun gearValue(record: List<Long>): Long {
            if (record.size != 7) throw PyValues.ValueError("equipment records are 7 values")
            val (config, level, grade) = Triple(record[1], record[2], record[4])
            val key = Triple("gear", config, grade)
            if (key !in propCache) propCache[key] = inputs.propertyValueInputs("gear", config, grade)
            val i = propCache[key]
            if (i == null) {
                need("gear_main", "equip $config grade $grade property inputs", "catalog inputs unavailable")
                return 0
            }
            return EquipEvolve.equipPropertyValue(level = level, superFlag = record[5], extra = record[6], base108 = i.long("base_108"),
                growth109 = i.long("growth_109"), potential = i.long("potential_before"), property912 = i.long("property_912"))
        }

        fun jewelValue(block: JewelBlock): Long {
            val key = Triple("jewelry", block.config, block.grade)
            if (key !in propCache) propCache[key] = inputs.propertyValueInputs("jewelry", block.config, block.grade)
            val i = propCache[key]
            if (i == null) {
                need("jewel_main", "jewelry ${block.config}: the client draws rand() over its base/ratio range " +
                    "(GetJewelPropertyValue 0x00b4ec84) or the catalog inputs are unavailable", "no deterministic value; contributes nothing")
                return 0
            }
            return EquipEvolve.jewelPropertyValue(level = block.level, superFlag = block.superFlag, extra = block.extra,
                base108 = i.long("base_108"), ratio110 = i.long("ratio_110"), potential = i.long("potential_before"),
                property956 = i.long("property_956"))
        }

        fun slotGear(slot: JObj): Map<Long, List<Long>?> = LinkedHashMap<Long, List<Long>?>().also { m ->
            for (a in (slot["assignments"] as? JArr) ?: JArr()) {
                val pair = a.asArr
                if (pair.size != 2) throw PyValues.ValueError("assignments are (position, uid) pairs")
                val pos = pair[0].long
                if (pos in 0 until 6) m[pos] = equipment[pair[1].long]
            }
        }

        fun slotJewels(slot: JObj): Map<Long, JewelBlock> = LinkedHashMap<Long, JewelBlock>().also { m ->
            for (b in (slot["blocks_40"] as? JArr) ?: JArr()) {
                val id = b.asObj.long("id")
                if (id in 0 until 6) m[id] = jewelBlock(b.asObj.str("raw_hex"))
            }
        }

        fun slotGems(slot: JObj): Map<Long, List<Long>> = LinkedHashMap<Long, List<Long>>().also { m ->
            for (g in (slot["groups"] as? JArr) ?: JArr()) {
                val id = g.asObj.long("id")
                if (id in 0 until 6) m[id] = g.asObj.arr("values").map { it.long }
            }
        }

        /** Formula::GetBattleSlotAbility; (null, reason) when the client would not count the slot. */
        fun battleSlot(slot: JObj): Pair<SlotResult?, String?> {
            val uid = slot.long("hero_uid")
            val ability = heroAbility(uid)
            val stats = ability.stats.copyOf()
            val terms = JObj()
            for ((k, v) in ability.terms) terms[k] = JArr(v.asArr.toMutableList())
            val hero = heroes.getValue(uid)
            val base = Math.floorDiv(orZero(hero[1L]).toLong(), 1000L)
            val heroClass = t.heroClass[base]
            val gear = slotGear(slot)
            val jewels = slotJewels(slot)
            val gems = slotGems(slot)
            val gemReborn = longArrayOf(0, 0)
            for (pos in 0L until 6L) {
                val record = gear[pos]
                if (record != null && record[0] != 0L) {
                    val row = t.equip[record[1]] ?: return null to "EquipConfig row ${record[1]} absent (GetBattleSlotAbility returns false)"
                    addTyped(stats, row.main, gearValue(record), terms, "gear_main")
                    if (heroClass == null) continue          // HeroConfig lookup failed: the native code skips runes / jewel too
                    val grade = record[4] and 0xFF
                    if (row.cls == ALL_CLASSES || row.cls == heroClass) advance(stats, terms, row, grade, "gear_advance")
                }
                for (gemId in gems[pos] ?: emptyList()) {
                    val row = t.gem[gemId] ?: continue
                    addTyped(stats, row[0], row[1], terms, "runes")
                    if (row[0] == REBORN_ATK_GEM) gemReborn[0] = (gemReborn[0] + row[1]) and U32
                    else if (row[0] == REBORN_DEF_GEM) gemReborn[1] = (gemReborn[1] + row[1]) and U32
                }
                val block = jewels[pos]
                if (block != null && block.uid != 0L) {
                    val row = t.jewel[block.config] ?: return null to "JewelConfig row ${block.config} absent (GetBattleSlotAbility returns false)"
                    addTyped(stats, row.main, jewelValue(block), terms, "jewel_main")
                    if (heroClass != null && (row.cls == ALL_CLASSES || row.cls == heroClass)) advance(stats, terms, row, block.grade, "jewel_advance")
                }
            }
            // secondary team: SecondaryTeamPropertyEffectConfig pct of each secondary hero's GetHeroAbility stats
            val team = secondary
            if (team == null) {
                need("secondary_team", WORLD_KEYS.getValue("secondary_team"), "input not supplied; contributes nothing")
            } else {
                val tenThousand = f32(10000.0)
                for (secUid in team) {
                    if (secUid == 0L || secUid !in heroes) continue
                    val sec = heroAbility(secUid).stats
                    for (effect in t.secondaryEffect) {
                        val index = STAT_INDEX[effect[0]] ?: continue
                        val value = fcvtzu(f32(f32(ucvtf(effect[1]) * ucvtf(sec[index])) / tenThousand))
                        if (value != 0L) {
                            add(stats, index, value)
                            addTerm(terms, "secondary_team", index, value)
                        }
                    }
                }
            }
            val score = combat(stats[0], stats[1], stats[2], stats[3], low32(hero[22L]) + gemReborn[0], low32(hero[23L]) + gemReborn[1])
            return SlotResult(stats, terms, score, gemReborn) to null
        }

        private fun advance(stats: LongArray, terms: JObj, row: ItemRow, grade: Long, name: String) {
            for ((k, tier) in ADVANCE_TIERS.withIndex()) {
                if (grade >= tier) {
                    for (pair in listOf(row.pairs[2 * k], row.pairs[2 * k + 1])) {
                        if (pair[0] != 0L) addTyped(stats, pair[0] and 0xFF, pair[1], terms, name)
                    }
                }
            }
        }

        /** Formula::HeroAddCombEffect: is every nonzero partner of the combo present? */
        fun comboActive(combo: Combo, slot: JObj): Boolean {
            for (partner in combo.partners) {
                val (kind, id) = partner[0] to partner[1]
                if (id == 0L) continue
                val ok = when (kind) {
                    1L -> {
                        val bases = HashSet<Long>()
                        for (s in roleSlots.values) bases.add(Math.floorDiv(orZero(heroes[s.long("hero_uid")]?.get(1L)).toLong(), 1000L))
                        for (secUid in secondary ?: LongArray(0)) {
                            if (secUid in heroes) bases.add(Math.floorDiv(orZero(heroes.getValue(secUid)[1L]).toLong(), 1000L))
                        }
                        id in bases
                    }
                    2L -> slotGear(slot).values.any { r -> r != null && r[0] != 0L && r[1] == id }
                    3L -> slotJewels(slot).values.any { b -> b.uid != 0L && b.config == id }
                    4L -> slotGems(slot).values.any { ids -> ids.any { g -> Math.floorDiv(g, 100L) == Math.floorDiv(id, 100L) && Math.floorMod(id, 100L) <= Math.floorMod(g, 100L) } }
                    else -> false
                }
                if (!ok) return false
            }
            return true
        }

        fun slot(slotId: Long): JObj {
            val slot = slotOf(state, slotId)
            val base = jobj("slot_id" to slotId, "hero_uid" to 0, "position" to null, "counted" to false, "hp" to 0, "atk" to 0,
                "def" to 0, "crit" to 0, "reborn_atk" to 0, "reborn_def" to 0, "score" to 0, "terms" to JObj(), "combos" to JArr(),
                "evidence_class" to EVIDENCE)
            if (slot == null || slotId !in MAIN_SLOTS || !Py.truthy(slot["hero_uid"])) {
                base["reason"] = io.github.okexodus.openknights.exact.JStr("no hero in a main formation slot (HeroBenchLine::GetBattleScore skips it)")
                return base
            }
            val uid = slot.long("hero_uid")
            base["hero_uid"] = JInt(uid)
            base["position"] = slot["flag"] ?: JNull
            val (result, reason) = battleSlot(slot)
            if (result == null) {
                base["reason"] = io.github.okexodus.openknights.exact.JStr(reason!!)
                return base
            }
            var stats = result.stats
            val terms = result.terms
            val hero = heroes.getValue(uid)
            val pre = stats.copyOf()
            val tenThousand = f32(10000.0)
            val combos = JArr()
            val heroBase = Math.floorDiv(orZero(hero[1L]).toLong(), 1000L)
            for (combo in t.combo) {
                if (combo.enabled != 1L || (combo.hero and U32) != heroBase || !comboActive(combo, slot)) continue
                combos.add(JInt(combo.key))
                for (bonus in combo.bonuses) {
                    val index = COMBO_INDEX[bonus[0]] ?: continue
                    val value = fcvtzu(f32(f32(ucvtf(pre[index]) * ucvtf(bonus[1])) / tenThousand))
                    add(stats, index, value)
                    addTerm(terms, "combo", index, value)
                }
            }
            val rebornAtk = (low32(hero[22L]) + result.gemReborn[0]) and U32
            val rebornDef = (low32(hero[23L]) + result.gemReborn[1]) and U32
            val score = combat(stats[0], stats[1], stats[2], stats[3], rebornAtk, rebornDef)
            if (battle) stats = LongArray(4) { trunc(f32(stats[it].toDouble())) and U32 }
            base["counted"] = io.github.okexodus.openknights.exact.JBool(true)
            base["hp"] = JInt(stats[HP]); base["atk"] = JInt(stats[ATK]); base["def"] = JInt(stats[DEF]); base["crit"] = JInt(stats[UNQ])
            base["reborn_atk"] = JInt(rebornAtk); base["reborn_def"] = JInt(rebornDef); base["score"] = JInt(score)
            base["terms"] = terms; base["combos"] = combos
            base["hero_ability_score"] = JInt(heroAbility(uid).score)
            base["mode"] = io.github.okexodus.openknights.exact.JStr(if (battle) "battle" else "power")
            return base
        }
    }

    private fun model(state: JObj, inputs: AcquisitionInputs, world: JObj?, mode: String): Model {
        if (mode !in MODES) throw PyValues.ValueError("mode must be one of ('power', 'battle')")
        return Model(state, inputs, world, battle = mode == "battle")
    }

    // --- public API --------------------------------------------------------------------------------------------------------

    /** Effective stats of the hero in formation slot `slotId` (0..5), with the model's unresolved list. */
    fun slotStats(state: JObj, slotId: Long, inputs: AcquisitionInputs, world: JObj? = null, mode: String = "power"): JObj {
        val m = model(state, inputs, world, mode)
        val result = m.slot(slotId)
        result["unresolved"] = m.unresolved
        return result
    }

    /** `lineup_stats`: every main formation slot (0..5) plus the shared unresolved list. */
    fun lineupStats(state: JObj, inputs: AcquisitionInputs, world: JObj? = null, mode: String = "power"): JObj {
        val m = model(state, inputs, world, mode)
        val slots = JArr(MAIN_SLOTS.mapTo(ArrayList()) { m.slot(it) })
        return jobj("slots" to slots, "unresolved" to m.unresolved)
    }

    /** `team_power`: HeroBenchLine::GetBattleScore, the exact u64 sum of the counted slot scores. */
    fun teamPower(state: JObj, inputs: AcquisitionInputs, world: JObj? = null): JObj {
        val lineup = lineupStats(state, inputs, world)
        var power = BigInteger.ZERO
        for (s in lineup.arr("slots")) if (s.asObj.bool("counted")) power += s.asObj.int("score")
        return jobj("power" to power.and(U64), "slots" to lineup["slots"], "unresolved" to lineup["unresolved"],
            "complete" to lineup.arr("unresolved").isEmpty())
    }

    /** `hero_ability`: GetHeroAbility of one owned hero (the per-hero value of the hero lists). */
    fun heroAbility(state: JObj, uid: Long, inputs: AcquisitionInputs, world: JObj? = null): JObj {
        val m = Model(state, inputs, world)
        val a = m.heroAbility(uid)
        return jobj("stats" to a.stats.toList(), "terms" to a.terms, "score" to a.score, "score_written" to a.scoreWritten,
            "unresolved" to m.unresolved)
    }

    /** The city bar's final value: the binary32 rounding of the exact Power. */
    fun cityBarDisplay(power: Long): Long = trunc(f32(power.toDouble()))

    const val S_LINEUP_VIEW = 3776
    const val C_LINEUP_VIEW = 3809

    val BATTLE_MODE_NOTE = "mode 'battle' reproduces the live campaign report max_hp (CAND, 106/107 captured own " +
        "actors): the client chain without the jewelry-album (type 3) families, the totem added as an integer, and " +
        "the four totals rounded through binary32; its score is not the client Power (use mode 'power')"

    /** `hero.get(id) or 0`: the field's bits when truthy, else 0. */
    private fun heroField(hero: Map<Long, BigInteger?>, id: Long): Long {
        val bits = hero[id]
        return if (bits == null || bits.signum() == 0) 0L else bits.toLong()
    }

    /**
     * `battle_actors(state, inputs, world)`: own campaign actors for the battle engine — `{battle position 1..6: slot
     * stats (mode "battle")}` for the counted main slots (report position = formation flag + 1), a thin wrapper over
     * `lineupStats(mode = "battle")`.
     */
    fun battleActors(state: JObj, inputs: AcquisitionInputs, world: JObj? = null): JObj {
        val lineup = lineupStats(state, inputs, world, mode = "battle")
        val actors = JObj(intKeys = true)
        for (s in lineup.arr("slots")) {
            val slot = s.asObj
            if (slot.bool("counted")) actors[(slot.long("position") + 1).toString()] = slot
        }
        return jobj("actors" to actors, "unresolved" to lineup["unresolved"], "note" to BATTLE_MODE_NOTE)
    }

    /**
     * `lineup_view_payload(state, inputs, world)`: S3776 for one character (own card, another local character or a bot
     * built as a state) — HandleGetOthersInfo layout over the power-mode `Model`.
     */
    fun lineupViewPayload(state: JObj, inputs: AcquisitionInputs, world: JObj? = null): ByteArray {
        val model = Model(state, inputs, world)
        val w = WireWriter().number('I', 0L).number('I', 0L).number('B', 0L)
        val slots = ArrayList<Pair<JObj, JObj>>()
        for (slotId in MAIN_SLOTS) {
            val raw = slotOf(state, slotId) ?: continue
            if (!Py.truthy(raw["hero_uid"])) continue
            slots.add(raw to model.slot(slotId))
        }
        w.number('B', slots.size.toLong())
        for ((raw, stats) in slots) {
            val hero = model.heroes.getValue(raw.long("hero_uid"))
            val flag = (raw["flag"] as? JInt)?.value?.toLong() ?: 0L
            w.number('B', flag and 0xFF).number('I', heroField(hero, 1L)).number('B', 0L)
                .number('H', heroField(hero, 2L) and 0xFFFF).number('H', 0L)
            for (k in listOf("hp", "atk", "def", "crit", "reborn_atk", "reborn_def")) w.number('I', stats.getValue(k))
            w.number('Q', stats.getValue("score"))
            val gear = model.slotGear(raw).entries.filter { it.value != null && it.value!![0] != 0L }.sortedBy { it.key }
            w.number('B', gear.size.toLong())
            for ((pos, r) in gear) w.number('B', pos).number('I', r!![1]).number('B', r[4] and 0xFF)
                .number('B', r[5] and 0xFF).number('I', r[2]).number('I', r[6])
            val jewels = model.slotJewels(raw).entries.filter { it.value.uid != 0L }.sortedBy { it.key }
            w.number('B', jewels.size.toLong())
            for ((pos, b) in jewels) w.number('B', pos).number('I', b.config).number('B', b.grade)
                .number('B', b.superFlag).number('I', b.level).number('I', b.extra)
            val groups = model.slotGems(raw).entries.sortedBy { it.key }
            w.number('B', groups.size.toLong())
            for ((gid, ids) in groups) {
                w.number('B', gid).number('B', ids.size.toLong())
                for (g in ids) w.number('I', g)
            }
        }
        val team = (model.secondary ?: LongArray(0)).withIndex().filter { it.value != 0L && it.value in model.heroes }
        w.number('B', team.size.toLong())
        for ((position, uid) in team) {
            val hero = model.heroes.getValue(uid)
            val ability = model.heroAbility(uid)
            val s = ability.stats
            w.number('B', position.toLong()).number('I', heroField(hero, 1L)).number('H', heroField(hero, 2L) and 0xFFFF).number('H', 0L)
            w.number('I', s[HP]).number('I', s[ATK]).number('I', s[DEF]).number('I', s[UNQ])
                .number('I', heroField(hero, 22L) and U32).number('I', heroField(hero, 23L) and U32).number('Q', ability.score)
        }
        return w.bytes()
    }

    // --- world helpers -----------------------------------------------------------------------------------------------------

    /**
     * `world_from_payloads(payloads)`: the world from served payloads {opcode: bytes} (548, 320, 2848, 2880, 3745,
     * 2240); an opcode absent from the payloads stays absent from the world.
     */
    fun worldFromPayloads(payloads: Map<Int, ByteArray>): JObj {
        val world = JObj()
        payloads[548]?.let { raw ->
            val r = WireReader(raw)
            val n = r.u32()
            world["album_activated"] = JArr((0 until n).mapTo(ArrayList()) { JInt(r.u32()) })
        }
        payloads[320]?.let { raw ->
            val r = WireReader(raw)
            r.offset = 1 + 9 * (raw[0].toInt() and 0xFF)
            world["quest_points"] = JInt(r.u32())
        }
        payloads[GodSkills.INIT_OPCODE]?.let { raw ->
            val god = JObj()
            for (hero in GodSkills.decodeGodSkillList(raw)) god[hero.long("uid").toString()] = JArr(hero.arr("skills").mapTo(ArrayList()) { JArr(it.asArr.toMutableList()) })
            world["god_skills"] = god
        }
        payloads[2880]?.let { world["totems"] = decodeTotemInit(it) }
        payloads[3745]?.let { raw ->
            val team = SecondaryTeam.decodeSecondaryTeam(raw)
            world["secondary_team"] = JArr(team.arr("entries").mapTo(ArrayList()) { e ->
                jarr(e.asObj.getValue("position"), SecondaryTeam.heroUid(e.asObj.arr("hero"))) })
        }
        payloads[2240]?.let { world["leader_uid"] = JInt(WireReader(it).u32()) }
        return world
    }

    /** S2880 `u8 n, n × (u32 id, u8 grade, u32 level, u64 exp), u32 lineup totem id` → {"entries", "lineup"}. */
    fun decodeTotemInit(raw: ByteArray): JObj {
        val n = raw[0].toInt() and 0xFF
        val r = WireReader(raw).also { it.offset = 1 }
        val entries = JArr()
        repeat(n) { entries.add(jarr(r.u32(), r.u8(), r.u32(), r.u64())) }
        val offset = r.offset
        val lineup = r.u32()
        if (offset + 4 != raw.size) throw PyValues.ValueError("S2880 has trailing bytes")
        return jobj("entries" to entries, "lineup" to lineup)
    }

    // --- universal Power -----------------------------------------------------------------------------------------------------

    /** What the client holds for a stat frame the session never sent (empty containers, leader uid 0). */
    fun unservedWorld(): JObj = jobj("album_activated" to JArr(), "quest_points" to 0, "god_skills" to JObj(),
        "totems" to jobj("entries" to JArr(), "lineup" to 0), "secondary_team" to JArr(), "leader_uid" to 0)

    /**
     * `stat_payloads_of(snapshot, current)`: {548, 2880, 320, 2848, 3745, 2240: payload} as a session serves them to this
     * character, rebuilt from its own save with the session's builders. The reference reads the seed frames
     * (`snapshot.fresh_systems` through `seeds_for`) and the S2240 catalog (`snapshot.leader_inputs`) from the service:
     * here they are [freshSystems] and [leaderInputs]. A frame the session would not send (no god-skill document, no
     * single leader-class hero) is left out. (A character without a profile — dev-only — sends no S3745 here.)
     */
    fun statPayloadsOf(freshSystems: Map<Int, List<ByteArray>>?, leaderInputs: EvolutionInputs, current: StateStore.Current): LinkedHashMap<Int, ByteArray> {
        val seeds = SystemSeeds.seedsFor(freshSystems, current)
        val heroes = SecondaryTeam.ownedHeroes(current.state)
        val frames = SweepFeatures.startupFrames(current, seeds)
        val payloads = linkedMapOf(548 to frames.album, 2880 to frames.totems, 320 to Quests.questsPayload(DailyRoutes.questDocument(current, seeds)))
        val god = current.godSkills
        if (god != null) payloads[GodSkills.INIT_OPCODE] = GodSkills.startupPayload(god.obj("document"), heroes.keys)
        if (current.characterProfile != null) payloads[3745] = AltTeam.infoPayload(current, emptyMap())
        val info = leaderInputs.leaderInfo(heroes)
        if (info != null) payloads[HeroEvolution.LEADER_INFO_OPCODE] = HeroEvolution.leaderInfoPayload(info.long("uid"), info.long("progress_key"))
        return payloads
    }

    /** `stat_world_of(snapshot, current)`: the complete world of a character from its own save, never partial. */
    fun statWorldOf(freshSystems: Map<Int, List<ByteArray>>?, leaderInputs: EvolutionInputs, current: StateStore.Current): JObj {
        val world = unservedWorld()
        world.putAll(worldFromPayloads(statPayloadsOf(freshSystems, leaderInputs, current)))
        return world
    }

    /**
     * `power_of(snapshot, current, inputs)`: THE Power of a character — [teamPower] of its state with [statWorldOf].
     * `inputs` is the service's catalog (`snapshot.acquisition_inputs`); the seed frames and the S2240 catalog are
     * parameters as in [statPayloadsOf].
     */
    fun powerOf(freshSystems: Map<Int, List<ByteArray>>?, leaderInputs: EvolutionInputs, current: StateStore.Current,
                inputs: AcquisitionInputs?): BigInteger {
        if (inputs == null) throw PyValues.ValueError("Power needs catalog inputs with battle_stat_tables()")
        return teamPower(current.state, inputs, statWorldOf(freshSystems, leaderInputs, current)).int("power")
    }

    /**
     * `participant_power(snapshot, inputs)`: the `power_of(current)` callable of the participant lists. A save that
     * cannot be evaluated shows no Power (null → the lists send 0) instead of failing the list.
     */
    fun participantPower(freshSystems: Map<Int, List<ByteArray>>?, leaderInputs: EvolutionInputs, inputs: AcquisitionInputs?): (StateStore.Current) -> BigInteger? =
        { current -> try { powerOf(freshSystems, leaderInputs, current, inputs) } catch (e: Exception) { if (e is NotPorted) throw e; null } }
}
