package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JNull
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.PyInt
import io.github.okexodus.openknights.exact.PyRandom
import io.github.okexodus.openknights.exact.asArr
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.hexBytes
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.toHexString
import io.github.okexodus.openknights.protocol.BattleReport
import io.github.okexodus.openknights.protocol.PlayerSections
import io.github.okexodus.openknights.protocol.WireReader
import io.github.okexodus.openknights.protocol.WireWriter
import java.math.BigInteger
import java.security.MessageDigest

/**
 * `castle.py`: the per-character Castle document (daily collect counts from local midnight, Transmute timers, recruit
 * deadlines), the next collect costs S226, the personal Guild Tech list S2330, the buildings and Magic House techs that
 * join at login, the recruits' countdowns and the Alchemy Lab values of the S18, and the Castle actions outside combat:
 * Collect, building Evolve, Magic House tech, personal Guild Tech, Transmute / Alchemy Lab refresh / recruit slots and
 * the recruits' Work / Release / Guard. Planners work on the [Owned] view (one audited revision each).
 */
object Castle {
    const val C_COLLECT_COST = 163
    const val C_ALCHEMY_INFO = 777
    const val C_CATCH_LIST = 753
    const val C_SERVANT_CHECK = 751
    const val C_RESCUE_LIST = 755
    const val C_RUN_AWAY = 771
    const val C_FOR_HELP = 773
    const val C_RANSOM = 775
    const val S_COLLECT_COST = 226
    const val S_COLLECT_BONUS = 228
    const val S_BUILDING = 640
    const val S_GUILD_TECH = 2330
    const val S_ALCHEMY = 800
    const val S_CATCH_LIST = 804
    const val S_RESCUE_LIST = 806
    const val S_SERVANT_MSG = 808
    const val S_ITEM_CAPACITY = 72
    const val ERROR_NOT_RECRUIT = 21001

    const val VIP_LEVEL = 27L
    const val CASTLE = 1L
    const val WAREHOUSE = 6L
    val FOLLOW_CASTLE = setOf(7L, 9L)
    const val GOLD_T = 1L
    const val HONOR_T = 2L
    const val RUNES_T = 4L
    const val CASTLE_PROFILE = "castle_state_v1"
    const val GUILD_TECH_PROFILE = "guild_tech_state_v1"
    const val TERM = 2
    const val WORK_CD = 3
    const val GUARD_CD = 6

    private fun copy(value: JValue): JObj = value.deepCopy() as JObj

    fun buildingLevels(state: JObj): Map<Long, JValue> {
        val out = LinkedHashMap<Long, JValue>()
        for (e in state.obj("subsystems").obj("buildings").arr("entries")) {
            val wire = e.asObj.arr("wire_values")
            out[PyDocs.long(wire[0])] = wire[1]
        }
        return out
    }

    fun castleLevel(state: JObj): JValue = buildingLevels(state)[CASTLE] ?: JInt(1)

    /** `_set_building(state, ident, level)` (the sweep features' Warehouse repair calls it too). */
    fun setBuilding(state: JObj, ident: Long, level: JValue) {
        val section = state.obj("subsystems").obj("buildings")
        for (entry in section.arr("entries")) {
            val wire = entry.asObj.arr("wire_values")
            if (wire[0] == JInt(ident)) { wire[1] = level; return }
        }
        section.arr("entries").add(jobj("wire_values" to jarr(ident, level)))
        section.arr("entries").sortWith { a, b -> PyDocs.compare(a.asObj.arr("wire_values")[0], b.asObj.arr("wire_values")[0]) }
        section["count"] = JInt(section.arr("entries").size)
    }

    /** The per-character Castle document; the daily collect counts restart at the device's local midnight. */
    fun castleDocument(document: JValue?, now: Long): JObj {
        val doc = if (Py.truthy(document)) copy(document!!) else jobj("profile" to CASTLE_PROFILE)
        if (PyDocs.get(doc, "day") != JStr(Shops.dayOf(now))) {
            doc["day"] = JStr(Shops.dayOf(now))
            doc["collected"] = jobj(GOLD_T.toString() to 0, HONOR_T.toString() to 0, RUNES_T.toString() to 0)
        }
        if (!doc.containsKey("alchemy")) doc["alchemy"] = JNull
        if (!doc.containsKey("servants")) doc["servants"] = JObj()
        return doc
    }

    // --- Collect -------------------------------------------------------------------------------------------------------

    /** Diamonds of the next collect of one type: max(0, zhengshou[n].102 − viplv.128). */
    fun collectCost(document: JObj, inputs: DailyInputs, vip: Long, kind: Long): Long {
        val n = PyDocs.long(PyDocs.at(document.obj("collected"), kind.toString())) + 1
        val row = inputs.collectRow(n) ?: return 0
        return maxOf(0L, row.long("cost_base") - inputs.vipCastle(vip).long("free"))
    }

    /** (gold, honor, runes, all = max(0, g + h + r − 2)). */
    fun collectCosts(document: JObj, inputs: DailyInputs, vip: Long): List<Long> {
        val g = collectCost(document, inputs, vip, GOLD_T)
        val h = collectCost(document, inputs, vip, HONOR_T)
        val r = collectCost(document, inputs, vip, RUNES_T)
        return listOf(g, h, r, maxOf(0L, g + h + r - 2))
    }

    fun collectCostPayload(document: JObj, inputs: DailyInputs, vip: Long): ByteArray {
        val w = WireWriter()
        for (v in collectCosts(document, inputs, vip)) w.number('I', v)
        return w.bytes()
    }

    // --- buildings / techs ----------------------------------------------------------------------------------------------

    /**
     * Buildings whose building.csv 109 ≤ player level join the list; the Magic House and Alchemy Lab at the Castle's
     * level, the others at 1. `above`: only buildings unlocked above that level. Returns S640 frames.
     */
    fun unlockBuildings(state: JObj, inputs: DailyInputs, playerLevel: Long, above: Long? = null): List<Frame> {
        val present = buildingLevels(state)
        val castle = castleLevel(state)
        val frames = ArrayList<Frame>()
        for (row in inputs.buildings()) {
            val id = row.long("id")
            if (id in present || row.long("unlock_level") > playerLevel) continue
            if (above != null && row.long("unlock_level") <= above) continue
            val maxLevel = row.long("max_level")
            val level: JValue = if (id in FOLLOW_CASTLE && maxLevel != 0L) (if (PyDocs.compare(castle, JInt(maxLevel)) <= 0) castle else JInt(maxLevel)) else JInt(1)
            setBuilding(state, id, level)
            frames.add(S_BUILDING to WireWriter().number('B', id).number('H', level).bytes())
        }
        return frames
    }

    /** Opened bag capacities a Warehouse level allows: items P3 + L, fragments / blueprints P3 + L / 2. */
    fun warehouseCapacity(level: Long, inputs: DailyInputs): List<Long> {
        val base = inputs.prop(3, 50)
        return listOf(base + level, base + Math.floorDiv(level, 2L), base + Math.floorDiv(level, 2L))
    }

    /** S72 `u16 items, u16 fragments, u16 blueprints`. */
    fun itemCapacityPayload(values: List<JValue>): ByteArray = WireWriter().values("HHH", values).bytes()

    /** Add the techs whose technology.csv 103 ≤ Castle level. Returns the added ids. */
    fun listNewTechs(state: JObj, inputs: DailyInputs): List<Long> {
        val section = state.obj("subsystems").obj("technologies")
        val present = section.arr("entries").map { it.asObj.arr("wire_values")[0] }.toSet()
        val castle = castleLevel(state)
        val added = inputs.technologies().filter { JInt(it.long("id")) !in present && PyDocs.compare(JInt(it.long("requirement")), castle) <= 0 }
            .map { it.long("id") }
        if (added.isNotEmpty()) {
            val entries = section.arr("entries").toMutableList<JValue>() + added.map { jobj("wire_values" to jarr(it, 0)) }
            section["entries"] = JArr(entries.sortedWith { a, b -> PyDocs.compare(a.asObj.arr("wire_values")[0], b.asObj.arr("wire_values")[0]) }.toMutableList())
            section["count"] = JInt(section.arr("entries").size)
        }
        return added
    }

    // --- personal Guild Tech --------------------------------------------------------------------------------------------

    /** S2330 decode: `00` / empty → not in a guild; else `01, u8 n, n × (u32 id, u16 level, u16 cap)`. */
    fun decodeGuildTech(payload: ByteArray?): JObj {
        if (payload == null || payload.isEmpty() || payload[0].toInt() == 0) {
            return jobj("profile" to GUILD_TECH_PROFILE, "in_guild" to false, "techs" to JArr())
        }
        val n = payload[1].toInt() and 0xFF
        if (payload.size != 2 + 8 * n) throw PyValues.ValueError("S2330 length mismatch")
        val r = WireReader(payload).also { it.offset = 2 }
        val techs = JArr()
        repeat(n) { techs.add(r.values("IHH")) }
        return jobj("profile" to GUILD_TECH_PROFILE, "in_guild" to true, "techs" to techs)
    }

    fun guildTechPayload(document: JObj?): ByteArray {
        if (document == null || document.isEmpty() || !Py.truthy(document["in_guild"])) return byteArrayOf(0)
        val techs = document.arr("techs")
        val w = WireWriter().raw(PyDocs.bytes(listOf(1L, techs.size.toLong())))
        for (t in techs) w.values("IHH", t.asArr)
        return w.bytes()
    }

    // --- Alchemy Lab ----------------------------------------------------------------------------------------------------

    fun alchemyValues(state: JObj): JArr = JArr(state.obj("subsystems").obj("alchemy").arr("wire_values").toMutableList())

    /**
     * (values, timers): the S18 / S800 alchemy values with the refresh countdown and the attempts regenerated (one per
     * property 74 = 1800 s up to the maximum) on the device clock.
     */
    fun alchemyView(state: JObj, document: JObj?, inputs: DailyInputs, now: Long): Pair<JArr, JObj> {
        val values = alchemyValues(state)
        val stored = PyDocs.get(document ?: JObj(), "alchemy")
        val timers = PyDocs.shallow(if (Py.truthy(stored)) stored as JObj
            else jobj("refresh_until" to (BigInteger.valueOf(now) + PyDocs.int(values[3])), "anchor" to now))
        values[3] = JInt(maxOf(BigInteger.ZERO, PyDocs.int(PyDocs.at(timers, "refresh_until")) - BigInteger.valueOf(now)))
        val period = inputs.prop(74, 1800)
        if (PyDocs.compare(values[7], values[8]) >= 0) {
            timers["anchor"] = JInt(now)
        } else if (period != 0L) {
            val anchor = PyDocs.int(PyDocs.at(timers, "anchor"))
            val gained = PyInt.floorDiv(BigInteger.valueOf(now) - anchor, BigInteger.valueOf(period))
            if (gained.signum() > 0) {
                val v7 = PyDocs.int(values[7]) + gained
                values[7] = if (PyDocs.compare(values[8], JInt(v7)) <= 0) values[8] else JInt(v7)
                timers["anchor"] = if (PyDocs.compare(values[7], values[8]) >= 0) JInt(now) else JInt(anchor + gained * BigInteger.valueOf(period))
            }
        }
        return values to timers
    }

    /** S800: the `alchemy` section of its values. */
    fun alchemyFrame(values: JArr): Frame = S_ALCHEMY to PlayerSections.encodeSection("alchemy", jobj("wire_values" to values))

    // --- recruits ---------------------------------------------------------------------------------------------------------

    /**
     * Refresh every recruit's countdowns from the deadlines in the Castle document; recruits whose term ended leave
     * with text 758 (name, total Gold). Returns (document, the expired message frames).
     */
    fun servantsView(state: JObj, document: JObj, now: Long, inputs: DailyInputs): Pair<JObj, List<Frame>> {
        val timers = (document["servants"] ?: JObj().also { document["servants"] = it }) as JObj
        val section = state.obj("subsystems").obj("servants").obj("servants")
        val kept = ArrayList<JValue>()
        val frames = ArrayList<Frame>()
        for (s in section.arr("entries")) {
            val servant = s.asObj
            val values = servant.arr("wire_values_after_string")
            val key = PyDocs.str(servant["wire_u32_1"])
            val entry = (timers[key] ?: jobj("gold" to 0).also { timers[key] = it }) as JObj
            for ((field, name) in listOf(TERM to "term_until", WORK_CD to "work_until", GUARD_CD to "guard_until")) {
                if (!entry.containsKey(name)) entry[name] = JInt(BigInteger.valueOf(now) + PyDocs.int(values[field]))
                values[field] = JInt(maxOf(BigInteger.ZERO, PyDocs.int(entry[name]) - BigInteger.valueOf(now)))
            }
            if (values[TERM] == JInt(0)) {
                val name = String(servant.str("cstring_hex").hexBytes(), Charsets.UTF_8)
                val gold = PyDocs.at(timers.remove(key)!! as JObj, "gold")
                frames.add(message(state, 758, listOf(name, PyDocs.str(gold))))
                continue
            }
            kept.add(servant)
        }
        if (kept.size != section.arr("entries").size) {
            section["entries"] = JArr(kept.toMutableList())
            section["count"] = JInt(kept.size)
            val values = state.obj("subsystems").obj("alchemy").arr("wire_values")
            values[4] = JInt(kept.size)
            values[6] = JInt(kept.size.toLong() * inputs.prop(75, 2000))
        }
        return document to frames
    }

    private fun message(state: JObj, textId: Long, strings: List<String>): Frame {
        val entry = jobj("wire_u32_1" to textId, "strings" to jobj("count" to strings.size,
            "entries" to strings.map { it.toByteArray(Charsets.UTF_8).toHexString() }))
        val messages = state.arr("servant_messages").toMutableList<JValue>() + entry
        state["servant_messages"] = JArr(messages.takeLast(20).toMutableList())
        return S_SERVANT_MSG to PlayerSections.encodeSection("servant_message", entry)
    }

    /** S806 `u8 challenges remaining, u8, u8 n`: the friends' recruits — none offline. */
    /**
     * S804 `u8 cost, u8 cost, u8 n1, n1 × (u32 id, name, u32 level, u32 hero, u8 role), u8 n2, n2 × (…, u8 flag)` from
     * the shared world (`catch_list_payload`): "Defeated by Me" = up to six at or below the player's level (highest
     * first), "Enemy" = up to six of the others (lowest first), a "Defeated by Me" row left out by participant id; nobody
     * is a recruit offline (role 0).
     */
    fun catchListPayload(participants: List<WorldParticipants.Participant>, ownId: JValue?, ownLevel: Long, cost: Long = 5): ByteArray {
        val own = (ownId as? JInt)?.value
        val others = participants.filter { own == null || BigInteger.valueOf(it.participantId) != own }
        val below = others.filter { it.level <= ownLevel }.sortedByDescending { it.level }.take(6)
        val shown = below.mapTo(HashSet()) { it.participantId }
        val above = others.filter { it.participantId !in shown }.sortedBy { it.level }.take(6)
        fun row(p: WorldParticipants.Participant, flag: Boolean): ByteArray = WireWriter().number('I', p.participantId).raw(p.nameRaw)
            .raw(byteArrayOf(0)).number('I', p.level).number('I', p.leaderTemplate).raw(if (flag) byteArrayOf(0, 0) else byteArrayOf(0)).bytes()
        val w = WireWriter().raw(PyDocs.bytes(listOf(cost, cost, below.size.toLong())))
        for (p in below) w.raw(row(p, false))
        w.raw(PyDocs.bytes(listOf(above.size.toLong())))
        for (p in above) w.raw(row(p, true))
        return w.bytes()
    }

    fun rescueListPayload(remaining: Long = 6, second: Long = 2): ByteArray = PyDocs.bytes(listOf(remaining, second, 0))

    // --- the Castle actions (C161, C545, C97, C2179, C739, C737, C769, C745, C749, C781) --------------------------------

    const val C_COLLECT = 161
    const val C_BUILDING = 545
    const val C_TECH = 97
    const val C_GUILD_TECH = 2179
    const val C_TRANSMUTE = 739
    const val C_ALCHEMY_REFRESH = 737
    const val C_BUY_SLOT = 769
    const val C_WORK = 745
    const val C_RELEASE = 749
    const val C_GUARD = 781

    const val S_COLLECT = 224
    const val S_TECH = 192
    const val S_ALCHEMY_REWARD = 810
    const val S_SERVANTS = 802
    const val S_WORK_REWARD = 814
    const val S_ACHIEVEMENT = 578

    const val ROLE_LEVEL = 3L
    const val HONOR = 7L
    const val CONTRIBUTION = 30L
    const val MAGIC_HOUSE = 7L
    const val ALCHEMY_LAB = 9L
    /** S224 byte per multiplier (help text 1021 "x2, x4, or x10"). */
    val MULT_BYTE = mapOf(1L to 1L, 2L to 2L, 4L to 3L, 10L to 4L)
    /** S578 [6, tier, level] after every tech-101 evolve; tech 201 moves kind 7. */
    const val ACH_TECH = 6L
    val TECH_ACHIEVEMENT = mapOf(101L to ACH_TECH, 201L to 7L)
    /** Rune levels per multiplier (captured: x1 two level-4, x2 one level-5; x4 / x10 policy). */
    val RUNE_LEVELS = mapOf(1L to listOf(4L, 4L), 2L to listOf(4L, 5L), 4L to listOf(5L, 5L), 10L to listOf(5L, 6L))
    /** Recruit slot price property ids (100 / 300 / 600 Diamonds). */
    val SLOT_PRICE = mapOf(3L to 90L, 4L to 91L, 5L to 92L)

    // errors (text 8000000 + code)
    const val ERROR_COLLECT_KIND = 3000
    const val ERROR_COLLECT_LIMIT = 3001
    const val ERROR_RESOURCES = 4000
    const val ERROR_BUILDING_LOCKED = 18000
    const val ERROR_BUILDING_FIXED = 18001
    const val ERROR_BUILDING_PLAYER = 18002
    const val ERROR_BUILDING_CASTLE = 18003
    const val ERROR_BUILDING_MAX = 18004
    const val ERROR_TECH_HOUSE = 8000
    const val ERROR_TECH_LOCKED = 8001
    const val ERROR_TECH_MAX = 8002
    const val ERROR_GTECH_CONTRIBUTION = 52013
    const val ERROR_GTECH_LOCKED = 52017
    const val ERROR_GTECH_MAX = 52019
    const val ERROR_ALCHEMY_CHANCES = 21000
    const val ERROR_ALCHEMY_MAXED = 21012
    const val ERROR_ALCHEMY_DIAMONDS = 69675
    const val ERROR_SLOTS_FULL = 21003
    const val ERROR_TARGET = 21007
    const val ERROR_ACTIONS = 21009

    private const val EVIDENCE_RNG = "capture_observed_csv_calculation_rng_policy"
    private const val EVIDENCE_FORMULA = "capture_observed_native_formula"
    private const val EVIDENCE_POLICY = "native_use_policy"

    /** `_rng(*parts)`: `random.Random` seeded with the first 8 bytes (little-endian) of SHA-256 of the parts joined by `|`. */
    private fun rng(vararg parts: String): PyRandom {
        val digest = MessageDigest.getInstance("SHA-256").digest(parts.joinToString("|").toByteArray(Charsets.UTF_8))
        return PyRandom.seeded(BigInteger(1, digest.copyOfRange(0, 8).reversedArray()))
    }

    /** `_roles(owned, fields)`: S128 of the fields' current values. */
    private fun roles(owned: Owned, fields: List<Long>): Frame =
        Acquisition.S_ROLE to Acquisition.roleUpdatePayload(fields.map { Triple(it, owned.role(it).long("tag"), owned.roleBits(it)) })

    private fun level(owned: Owned): BigInteger = owned.roleBits(ROLE_LEVEL)

    private fun vip(owned: Owned): Long = try {
        owned.roleBits(VIP_LEVEL).longValueExact()
    } catch (e: Acquisition.Rejected) {
        0L
    }

    /** `state["subsystems"][name]`. */
    private fun sub(state: JObj, name: String): JObj = PyDocs.at(PyDocs.at(state, "subsystems") as JObj, name) as JObj

    /** `max(a, b)` of two document numbers (the first when equal). */
    private fun max(a: JValue, b: JValue): JValue = if (PyDocs.compare(b, a) > 0) b else a

    /** `seq[:3] = new`. */
    private fun setHead(values: JArr, new: List<JValue>) {
        repeat(minOf(3, values.size)) { values.removeAt(0) }
        values.addAll(0, new)
    }

    /** `a, b, c = seq` of a document list. */
    private fun unpack3(value: JValue): JArr {
        val seq = value as? JArr ?: throw PyDocs.TypeError("cannot unpack non-iterable object")
        if (seq.size > 3) throw PyValues.ValueError("too many values to unpack (expected 3)")
        if (seq.size < 3) throw PyValues.ValueError("not enough values to unpack (expected 3, got ${seq.size})")
        return seq
    }

    /** `u8` request (`decode_u8`). */
    fun decodeU8(payload: ByteArray, opcode: Int): Long {
        if (payload.size != 1) throw Acquisition.Rejected("C$opcode is u8")
        return payload[0].toLong() and 0xFF
    }

    /** `u32` request (`decode_u32`). */
    fun decodeU32(payload: ByteArray, opcode: Int): Long {
        if (payload.size != 4) throw Acquisition.Rejected("C$opcode is u32")
        return WireReader(payload).u32()
    }

    // --- Collect (C161) ------------------------------------------------------------------------------------------------

    /** Personal Guild Tech 102 "Gold Bonus" level (0.5 % per level). */
    fun guildGoldBonus(guildDoc: JObj?): BigInteger {
        val techs = if (guildDoc == null || guildDoc.isEmpty()) JArr() else (guildDoc["techs"] ?: JArr())
        if (techs !is JArr) throw PyDocs.TypeError("'${if (techs == JNull) "NoneType" else "object"}' object is not iterable")
        for (tech in techs) {
            val t = unpack3(tech)
            if (t[0] == JInt(102)) return PyDocs.int(t[1])
        }
        return BigInteger.ZERO
    }

    /** x2 / x4 / x10 by zhengshou 201 / 202 / 203 as weights per 10,000. */
    fun rollMultiplier(row: JObj, rng: PyRandom): Long {
        val pick = rng.randrange(10000)
        val x10 = row.long("x10")
        val x4 = row.long("x4")
        val x2 = row.long("x2")
        if (pick < x10) return 10
        if (pick < x10 + x4) return 4
        if (pick < x10 + x4 + x2) return 2
        return 1
    }

    /**
     * C161 `u8 kind` (1 Gold, 2 Honor, 4 Runes, 7 all) → [S578 + S1188 when paid] → S1536 per rune → S224 `u8 m_gold,
     * u8 m_honor, u8 m_runes` + Reward → S128 → S226 next costs (`plan_collect`). Gold = zhengshou[n].103 × (1 +
     * viplv.113) × (1 + 0.5 % × Gold Bonus) × mult, Honor = zhengshou[n].104 × (1 + viplv.114) × mult; two runes (random
     * type). `forced` (the reference's capture verifier only) replays multipliers, rune ids and a donation.
     */
    fun planCollect(kind: Long, owned: Owned, inputs: DailyInputs, document: JValue?, guildDoc: JObj, now: Long, servedTime: Long,
                    ownerKey: String, forced: JValue? = null): Plan {
        if (kind !in listOf(1L, 2L, 4L, 7L)) throw Acquisition.Rejected("Invalid Collect method", ERROR_COLLECT_KIND)
        val doc = castleDocument(document, now)
        val level = level(owned)
        val vip = vip(owned)
        val runesOpen = level >= BigInteger.valueOf(inputs.prop(198, 55))
        if (kind == RUNES_T && !runesOpen) throw Acquisition.Rejected("Runes unlock at a higher level", ERROR_COLLECT_KIND)
        val types = listOf(GOLD_T, HONOR_T, RUNES_T).filter { (kind and it) != 0L && (it != RUNES_T || runesOpen) }
        val collected = PyDocs.at(doc, "collected") as JObj
        val rows = LinkedHashMap<Long, JObj>()
        for (t in types) {
            rows[t] = inputs.collectRow(PyDocs.long(PyDocs.at(collected, t.toString())) + 1)
                ?: throw Acquisition.Rejected("You've exceeded the collection limit", ERROR_COLLECT_LIMIT)
        }
        val (g, h, r, every) = collectCosts(doc, inputs, vip)
        val cost = if (kind == 7L) every else mapOf(GOLD_T to g, HONOR_T to h, RUNES_T to r).getValue(kind)
        val frames = ArrayList<Frame>()
        val fields = ArrayList<Long>()
        if (cost != 0L) {
            if (owned.roleBits(Acquisition.DIAMOND) < BigInteger.valueOf(cost)) throw Acquisition.Rejected("Not enough Diamonds", ERROR_RESOURCES)
            owned.roleAdd(Acquisition.DIAMOND, -cost)
            frames += Shops.diamondAchievement(owned, cost, servedTime)
        }
        val rng = rng("collect", ownerKey, PyDocs.str(doc["day"]), PyDocs.sortedDump(collected), kind.toString())
        var multipliers = LinkedHashMap<Long, JValue>()
        for (t in types) multipliers[t] = JInt(rollMultiplier(rows.getValue(t), rng))
        var forcedRunes: List<JValue>? = null
        val forcedDoc = forced?.takeIf { it != JNull } as JObj?
        if (forcedDoc != null) {
            val given = PyDocs.at(forcedDoc, "multipliers") as JObj
            multipliers = LinkedHashMap<Long, JValue>().also { m -> for (t in types) m[t] = PyDocs.at(given, t.toString()) }
            forcedRunes = (forcedDoc["runes"]?.takeIf { Py.truthy(it) } as JArr?)?.toList() ?: emptyList()
        }
        val reward = Acquisition.emptyReward()
        val vipRow = inputs.vipCastle(vip)
        if (GOLD_T in types) {
            val gold = PyInt.floorDiv(PyDocs.int(rows.getValue(GOLD_T)["gold"]) * BigInteger.valueOf(10000 + vipRow.long("gold_bonus")) *
                (BigInteger.valueOf(200) + guildGoldBonus(guildDoc)), BigInteger.valueOf(10000L * 200)) * PyDocs.int(multipliers[GOLD_T])
            owned.roleAdd(Acquisition.GOLD, gold)
            reward["gold"] = JInt(gold)
            fields.add(Acquisition.GOLD)
        }
        if (HONOR_T in types) {
            val honor = PyInt.floorDiv(PyDocs.int(rows.getValue(HONOR_T)["honor"]) * BigInteger.valueOf(10000 + vipRow.long("honor_bonus")),
                BigInteger.valueOf(10000)) * PyDocs.int(multipliers[HONOR_T])
            owned.roleAdd(HONOR, honor)
            reward["exploit"] = JInt(honor)
            fields.add(HONOR)
        }
        if (RUNES_T in types) {
            val counts = EquipFormation.gemCounts(sub(owned.state, "gems"))
            val granted = LinkedHashMap<Long, Long>()
            val levels = RUNE_LEVELS[PyDocs.long(multipliers[RUNES_T])] ?: throw PyDocs.KeyError(PyDocs.str(multipliers[RUNES_T]))
            for ((index, runeLevel) in levels.withIndex()) {
                var rune = rng.randint(1, 7) * 100 + runeLevel
                if (forcedRunes != null) rune = PyDocs.long(PyDocs.index(forcedRunes, index))
                counts[rune] = (counts[rune] ?: 0L) + 1
                granted[rune] = (granted[rune] ?: 0L) + 1
                frames.add(EquipFormation.gemBagFrame(rune, counts.getValue(rune)))
            }
            (PyDocs.at(owned.state, "subsystems") as JObj)["gems"] = EquipFormation.gemsSectionWith(counts)
            reward["gems"] = JArr(granted.entries.sortedBy { it.key }.mapTo(ArrayList()) { jarr(it.key, it.value) })
        }
        for (t in types) collected[t.toString()] = JInt(PyDocs.int(PyDocs.at(collected, t.toString())) + BigInteger.ONE)
        if (cost != 0L) fields.add(Acquisition.DIAMOND)
        val donation = forcedDoc?.get("donation") ?: JInt(0)
        if (Py.truthy(donation)) {
            owned.roleAdd(CONTRIBUTION, PyDocs.int(donation))
            reward["donation"] = donation
            fields.add(CONTRIBUTION)
        }
        val head = PyDocs.bytes(listOf(GOLD_T, HONOR_T, RUNES_T).map { t ->
            multipliers[t]?.let { m -> MULT_BYTE[PyDocs.long(m)] ?: throw PyDocs.KeyError(PyDocs.str(m)) } ?: 4L
        })
        frames.add(S_COLLECT to head + BattleReport.encodeReward(reward))
        if (fields.isNotEmpty()) frames.add(roles(owned, fields.sorted()))
        frames.add(S_COLLECT_COST to collectCostPayload(doc, inputs, vip))
        val shown = JObj()
        for ((t, m) in multipliers) shown[t.toString()] = m
        return Plan(jobj("kind" to kind, "types" to types, "multipliers" to shown, "cost" to cost, "castle_state_after" to doc,
            "evidence_class" to EVIDENCE_RNG), frames)
    }

    // --- Buildings (C545) ----------------------------------------------------------------------------------------------

    /** `Formula::GetUpgradeBuildingCost`: factor × (L + max(0, L−10) + 2·[L > 17] + max(0, L−20) + max(0, L−30) + 18·[L > 50]). */
    fun buildingCost(factor: Long, level: Long): Long =
        factor * (level + maxOf(0L, level - 10) + (if (level > 17) 2 else 0) + maxOf(0L, level - 20) + maxOf(0L, level - 30) +
            (if (level > 50) 18 else 0))

    /**
     * C545 `u8 building` → S640 `u8 id, u16 level` → S128 Gold (`plan_building`). The Castle also raises the Magic House
     * and Alchemy Lab to its level without a push, newly reachable techs join the Magic House list (S192 id, 0); a
     * Warehouse upgrade raises the opened bag capacities to its new limit (never lowered) and pushes S72.
     */
    fun planBuilding(ident: Long, owned: Owned, inputs: DailyInputs): Plan {
        val state = owned.state
        val levels = buildingLevels(state)
        val row = inputs.building(ident)
        if (ident !in levels) throw Acquisition.Rejected("Building is locked", ERROR_BUILDING_LOCKED)
        if (row == null || !row.bool("upgradable")) throw Acquisition.Rejected("Cannot upgrade building", ERROR_BUILDING_FIXED)
        val level = PyDocs.long(levels.getValue(ident))
        val maxLevel = row.long("max_level")
        if (maxLevel != 0L && level >= maxLevel) throw Acquisition.Rejected("The Building level has reached its limit", ERROR_BUILDING_MAX)
        if (ident == CASTLE && BigInteger.valueOf(level) >= level(owned)) throw Acquisition.Rejected("Player Lv has to > Castle Lv", ERROR_BUILDING_PLAYER)
        if (ident != CASTLE && PyDocs.compare(JInt(level), levels[CASTLE] ?: JInt(1)) >= 0) {
            throw Acquisition.Rejected("Castle Lv has to > other buildings Lv", ERROR_BUILDING_CASTLE)
        }
        val cost = buildingCost(row.long("cost_factor"), level)
        if (owned.roleBits(Acquisition.GOLD) < BigInteger.valueOf(cost)) throw Acquisition.Rejected("Not enough Gold", ERROR_RESOURCES)
        owned.roleAdd(Acquisition.GOLD, -cost)
        setBuilding(state, ident, JInt(level + 1))
        val frames = mutableListOf(S_BUILDING to WireWriter().number('B', ident).number('H', level + 1).bytes(), roles(owned, listOf(Acquisition.GOLD)))
        if (ident == CASTLE) {
            for (follower in FOLLOW_CASTLE) {
                val followerRow = inputs.building(follower)
                val current = levels[follower]
                if (current != null && PyDocs.compare(current, JInt(level + 1)) < 0 && followerRow != null) {
                    val cap = followerRow.long("max_level").let { if (it != 0L) it else level + 1 }
                    setBuilding(state, follower, JInt(minOf(level + 1, cap)))
                }
            }
            for (tech in listNewTechs(state, inputs)) frames.add(S_TECH to WireWriter().number('I', tech).number('I', 0).bytes())
        }
        val plan = jobj("building" to ident, "level_after" to level + 1, "cost" to cost)
        val capacity = state["item_capacity_values"]
        if (ident == WAREHOUSE && (if (Py.truthy(capacity)) (capacity as JArr).size else 0) == 3) {
            val before = JArr((capacity as JArr).toMutableList())
            val limits = warehouseCapacity(level + 1, inputs)
            val after = JArr(before.mapIndexedTo(ArrayList()) { i, b -> max(b, JInt(limits[i])) })
            state["item_capacity_values"] = after
            frames.add(S_ITEM_CAPACITY to itemCapacityPayload(after))
            plan["item_capacity_before"] = before
            plan["item_capacity_after"] = after
        }
        plan["evidence_class"] = JStr("capture_observed_native_formula")
        return Plan(plan, frames)
    }

    // --- Magic House tech (C97) ----------------------------------------------------------------------------------------

    /** `Formula::GetUpgradeTechCost`: factor × (L + 1 < 51 ? L + 2 : 2L − 48). */
    fun techCost(factor: Long, level: Long): Long = factor * (if (level + 1 < 51) level + 2 else 2 * level - 48)

    /**
     * C97 `u32 tech` → S192 `u32 id, u32 level` → S128 Honor → S578 [6, tier, level] for tech 101 / [7, tier, level] for
     * tech 201 (`plan_tech`).
     */
    fun planTech(ident: Long, owned: Owned, inputs: DailyInputs): Plan {
        val state = owned.state
        val entry = (PyDocs.at(sub(state, "technologies"), "entries") as JArr).firstOrNull { it.asObj.arr("wire_values")[0] == JInt(ident) }
        val row = inputs.technology(ident)
        if (entry == null || row == null) throw Acquisition.Rejected("The Tech is locked", ERROR_TECH_LOCKED)
        val wire = entry.asObj.arr("wire_values")
        val level = PyDocs.long(wire[1])
        if (level >= inputs.prop(761, 500)) throw Acquisition.Rejected("Max Level Achieved", ERROR_TECH_MAX)
        if (PyDocs.compare(JInt(level), castleLevel(state)) >= 0) throw Acquisition.Rejected("Magic House level is too low", ERROR_TECH_HOUSE)
        val cost = techCost(row.long("cost_factor"), level)
        if (owned.roleBits(HONOR) < BigInteger.valueOf(cost)) throw Acquisition.Rejected("Not enough Honor", ERROR_RESOURCES)
        owned.roleAdd(HONOR, -cost)
        wire[1] = JInt(level + 1)
        val frames = mutableListOf(S_TECH to WireWriter().number('I', ident).number('I', level + 1).bytes(), roles(owned, listOf(HONOR)))
        val kind = TECH_ACHIEVEMENT[ident]
        if (kind != null) {
            for (achievement in sub(state, "achievements").arr("entries")) {
                val values = achievement.asObj.arr("wire_values")
                if (values[0] == JInt(kind)) {
                    values[2] = max(values[2], JInt(level + 1))
                    frames.add(S_ACHIEVEMENT to WireWriter().values("BBI", values).bytes())
                    break
                }
            }
        }
        return Plan(jobj("tech" to ident, "level_after" to level + 1, "cost" to cost, "evidence_class" to EVIDENCE_FORMULA), frames)
    }

    // --- personal Guild Tech (C2179) -----------------------------------------------------------------------------------

    /** (contribution, Honor): f111 × (L < 51 ? L : 2L − 50), f112 × (L + 1 < 51 ? L + 2 : 2L − 48). */
    fun guildTechCosts(row: JObj, level: Long): Pair<Long, Long> =
        row.long("donate_factor") * (if (level < 51) level else 2 * level - 50) to
            row.long("honor_factor") * (if (level + 1 < 51) level + 2 else 2 * level - 48)

    /**
     * C2179 `u32 tech` → S128 (Honor, contribution) → S2330 full list (`plan_guild_tech`); the personal level is capped by
     * the guild's level of that tech (the list's cap).
     */
    fun planGuildTech(ident: Long, owned: Owned, inputs: DailyInputs, document: JObj): Plan {
        if (document.isEmpty() || !Py.truthy(document["in_guild"])) throw Acquisition.Rejected("Not in a guild", ERROR_GTECH_LOCKED)
        val row = inputs.guildTech(ident)
        val entry = (PyDocs.at(document, "techs") as JArr).firstOrNull { it.asArr[0] == JInt(ident) }
        if (row == null || row.bool("guild_only") || entry == null) throw Acquisition.Rejected("You cannot upgrade this Tech yet", ERROR_GTECH_LOCKED)
        val values = unpack3(entry)
        if (PyDocs.compare(values[1], values[2]) >= 0) throw Acquisition.Rejected("Max Tech Lv", ERROR_GTECH_MAX)
        val level = PyDocs.long(values[1])
        val (donate, honor) = guildTechCosts(row, level)
        if (owned.roleBits(CONTRIBUTION) < BigInteger.valueOf(donate)) {
            throw Acquisition.Rejected("Not enough Accu. Contribution", ERROR_GTECH_CONTRIBUTION)
        }
        if (owned.roleBits(HONOR) < BigInteger.valueOf(honor)) throw Acquisition.Rejected("Not enough Honor", ERROR_RESOURCES)
        owned.roleAdd(HONOR, -honor)
        owned.roleAdd(CONTRIBUTION, -donate)
        val doc = copy(document)
        doc.arr("techs").first { it.asArr[0] == JInt(ident) }.asArr[1] = JInt(level + 1)
        return Plan(jobj("tech" to ident, "level_after" to level + 1, "contribution" to donate, "honor" to honor,
            "guild_tech_state_after" to doc, "evidence_class" to EVIDENCE_FORMULA),
            listOf(roles(owned, listOf(HONOR, CONTRIBUTION)), S_GUILD_TECH to guildTechPayload(doc)))
    }

    // --- Transmute / Alchemy Lab (C739 / C737 / C769) ------------------------------------------------------------------

    /** Three shard ids drawn by the lianjin weight column (`_draw_shards`). */
    private fun drawShards(inputs: DailyInputs, rng: PyRandom, weight: String): List<JValue> {
        val rows = inputs.alchemyRows()
        return List(3) { rng.choices(rows.map { it["id"]!! }, rows.map { it.long(weight) })[0] }
    }

    private fun alchemyRow(rows: Map<Long, JObj>, shard: JValue): JObj =
        (shard as? JInt)?.value?.takeIf { it.bitLength() < 64 }?.let { rows[it.toLong()] } ?: throw PyDocs.KeyError(PyDocs.str(shard))

    /**
     * C739 → S800 (new shards, attempts − 1) → S810 Reward → S128 Gold (`plan_transmute`). Pays the shards shown before
     * the tap: Σ lianjin.104 (+ 105 Gold and 107 × item 106 when all three match) × (1 + 0.5 % × Gold Bonus) × (1 +
     * recruit bonus); new shards by lianjin weight A. `forcedShards`: the reference's capture verifier only.
     */
    fun planTransmute(owned: Owned, inputs: DailyInputs, document: JValue?, guildDoc: JObj, now: Long, ownerKey: String,
                      forcedShards: JValue? = null): Plan {
        val doc = castleDocument(document, now)
        val (values, timers) = alchemyView(owned.state, doc, inputs, now)
        if (PyDocs.compare(values[7], JInt(1)) < 0) throw Acquisition.Rejected("Insufficient Alchemy chances", ERROR_ALCHEMY_CHANCES)
        val rows = LinkedHashMap<Long, JObj>()
        for (row in inputs.alchemyRows()) rows[row.long("id")] = row
        val shards = JArr(values.subList(0, 3).toMutableList())
        var gold = BigInteger.ZERO
        for (s in shards) gold += PyDocs.int(alchemyRow(rows, s)["gold"])
        val reward = Acquisition.emptyReward()
        val frames = ArrayList<Frame>()
        if (shards[0] == shards[1] && shards[1] == shards[2]) {
            val row = alchemyRow(rows, shards[0])
            gold += PyDocs.int(row["extra_gold"])
            if (row.long("extra_item") != 0L && row.long("extra_count") != 0L) {
                frames.add(owned.grantItem(row.long("extra_item"), row.long("extra_count")))
                reward.arr("items").add(jarr(row["extra_item"], row["extra_count"]))
            }
        }
        gold = PyInt.floorDiv(PyInt.floorDiv(gold * (BigInteger.valueOf(200) + guildGoldBonus(guildDoc)), BigInteger.valueOf(200)) *
            (BigInteger.valueOf(10000) + PyDocs.int(values[6])), BigInteger.valueOf(10000))
        owned.roleAdd(Acquisition.GOLD, gold)
        reward["gold"] = JInt(gold)
        if (PyDocs.compare(values[7], values[8]) >= 0) timers["anchor"] = JInt(now)
        values[7] = JInt(PyDocs.int(values[7]) - BigInteger.ONE)
        setHead(values, if (Py.truthy(forcedShards)) (forcedShards as JArr).toList()
            else drawShards(inputs, rng("transmute", ownerKey, now.toString(), PyDocs.str(values[7])), "weight_a"))
        sub(owned.state, "alchemy")["wire_values"] = values
        doc["alchemy"] = timers
        val packets = listOf(alchemyFrame(values)) + frames + listOf(S_ALCHEMY_REWARD to BattleReport.encodeReward(reward),
            roles(owned, listOf(Acquisition.GOLD)))
        return Plan(jobj("shards_paid" to shards, "gold" to gold, "castle_state_after" to doc, "evidence_class" to EVIDENCE_RNG), packets)
    }

    /**
     * C737 → new shards → S800 (`plan_alchemy_refresh`). Free when the countdown is over (it restarts at property 73),
     * else property 94 Diamonds; all three at the top shard → 21012. New shards by lianjin weight B.
     */
    fun planAlchemyRefresh(owned: Owned, inputs: DailyInputs, document: JValue?, now: Long, servedTime: Long, ownerKey: String): Plan {
        val doc = castleDocument(document, now)
        val (values, timers) = alchemyView(owned.state, doc, inputs, now)
        val ids = inputs.alchemyRows().map { it.long("id") }
        if (ids.isEmpty()) throw PyValues.ValueError("max() iterable argument is empty")
        val top = JInt(ids.max())
        if (values[0] == values[1] && values[1] == values[2] && values[2] == top) {
            throw Acquisition.Rejected("All Golden Shards are at the max Level", ERROR_ALCHEMY_MAXED)
        }
        val frames = ArrayList<Frame>()
        if (PyDocs.compare(values[3], JInt(0)) > 0) {
            val price = inputs.prop(94, 10)
            if (owned.roleBits(Acquisition.DIAMOND) < BigInteger.valueOf(price)) {
                throw Acquisition.Rejected("Insufficient diamonds to refresh", ERROR_ALCHEMY_DIAMONDS)
            }
            owned.roleAdd(Acquisition.DIAMOND, -price)
            frames.add(roles(owned, listOf(Acquisition.DIAMOND)))
            frames += Shops.diamondAchievement(owned, price, servedTime)
        } else {
            timers["refresh_until"] = JInt(now + inputs.prop(73, 3600))
            values[3] = JInt(PyDocs.int(timers["refresh_until"]) - BigInteger.valueOf(now))
        }
        setHead(values, drawShards(inputs, rng("refresh", ownerKey, now.toString()), "weight_b"))
        sub(owned.state, "alchemy")["wire_values"] = values
        doc["alchemy"] = timers
        return Plan(jobj("shards_after" to JArr(values.subList(0, 3).toMutableList()), "castle_state_after" to doc,
            "evidence_class" to EVIDENCE_POLICY), listOf(alchemyFrame(values)) + frames)
    }

    /** C769 → S128 Diamonds → S800 with one more recruit slot (`plan_buy_slot`). */
    fun planBuySlot(owned: Owned, inputs: DailyInputs, document: JValue?, now: Long, servedTime: Long): Plan {
        val doc = castleDocument(document, now)
        val (values, timers) = alchemyView(owned.state, doc, inputs, now)
        val slots = (values[5] as? JInt)?.value?.takeIf { it.bitLength() < 64 }?.toLong()
        val pid = slots?.let { SLOT_PRICE[it] } ?: throw Acquisition.Rejected("Recruits Slots are full", ERROR_SLOTS_FULL)
        val price = inputs.prop(pid, 0)
        if (owned.roleBits(Acquisition.DIAMOND) < BigInteger.valueOf(price)) throw Acquisition.Rejected("Not enough Diamonds", ERROR_RESOURCES)
        owned.roleAdd(Acquisition.DIAMOND, -price)
        values[5] = JInt(slots + 1)
        sub(owned.state, "alchemy")["wire_values"] = values
        doc["alchemy"] = timers
        val packets = listOf(roles(owned, listOf(Acquisition.DIAMOND))) + Shops.diamondAchievement(owned, price, servedTime) +
            listOf(alchemyFrame(values))
        return Plan(jobj("slots_after" to values[5], "price" to price, "castle_state_after" to doc, "evidence_class" to EVIDENCE_POLICY), packets)
    }

    // --- recruits outside combat (C745 / C749 / C781) ------------------------------------------------------------------

    /**
     * `Formula::GetServantOutcome` (binary64 as in the reference): ⌊(1 + lab² / 10000) × ⌊(p + r)^0.8 × (P76 − remaining)
     * / max(P77, 1) × P78⌋⌋, both truncated to 32 bits.
     */
    fun servantOutcome(playerLevel: BigInteger, recruitLevel: JValue, labLevel: JValue, remaining: Long, inputs: DailyInputs): Long {
        val p76 = inputs.prop(76, 86400)
        val p77 = inputs.prop(77, 600)
        val p78 = inputs.prop(78, 300)
        val mask = BigInteger.valueOf(0xFFFFFFFFL)
        val scaled = Math.pow((playerLevel + PyDocs.int(recruitLevel)).toDouble(), 0.8) * (p76 - remaining).toDouble() /
            maxOf(p77, 1L).toDouble() * p78.toDouble()
        val base = PyInt.truncate(scaled).and(mask)
        val lab = PyDocs.int(labLevel)
        val factor = 1.0 + PyInt.trueDiv(lab * lab, BigInteger.valueOf(10000))
        return PyInt.truncate(factor * base.toDouble()).and(mask).toLong()
    }

    private fun servant(state: JObj, ident: Long): JObj? =
        sub(state, "servants").obj("servants").arr("entries").firstOrNull { it.asObj["wire_u32_1"] == JInt(ident) }?.asObj

    private fun servantsFrame(state: JObj): Frame = S_SERVANTS to PlayerSections.encodeSection("servants", sub(state, "servants"))

    private fun servantName(servant: JObj): String = PyBytes.decodeReplace(servant.str("cstring_hex").hexBytes())

    /**
     * C745 `u32 recruit` (Work / Bounty Quest) → S128 Gold → S814 Reward → S808 text 760 → S802 (actions − 1, work CD
     * property 82) (`plan_work`); Gold = outcome(player, recruit level, Alchemy Lab level, P76 − property 88).
     */
    fun planWork(ident: Long, owned: Owned, inputs: DailyInputs, document: JValue?, now: Long): Plan {
        val state = owned.state
        val doc = servantsView(state, castleDocument(document, now), now, inputs).first
        val servant = servant(state, ident) ?: throw Acquisition.Rejected("Target Recruit not found", ERROR_TARGET)
        val prefix = sub(state, "servants").arr("wire_u8_prefix")
        val values = servant.arr("wire_values_after_string")
        if (PyDocs.compare(prefix[1], JInt(1)) < 0 || PyDocs.compare(values[WORK_CD], JInt(0)) > 0) {
            throw Acquisition.Rejected("Insufficient remaining Actions", ERROR_ACTIONS)
        }
        val lab = buildingLevels(state)[ALCHEMY_LAB] ?: JInt(1)
        val gold = servantOutcome(level(owned), values[0], lab, inputs.prop(76, 86400) - inputs.prop(88, 7200), inputs)
        owned.roleAdd(Acquisition.GOLD, gold)
        prefix[1] = JInt(PyDocs.int(prefix[1]) - BigInteger.ONE)
        val total = PyDocs.at(PyDocs.at(doc, "servants") as JObj, ident.toString()) as JObj
        total["work_until"] = JInt(now + inputs.prop(82, 1800))
        values[WORK_CD] = JInt(inputs.prop(82, 1800))
        total["gold"] = JInt(PyDocs.int(PyDocs.at(total, "gold")) + BigInteger.valueOf(gold))
        val reward = Acquisition.emptyReward()
        reward["gold"] = JInt(gold)
        val name = servantName(servant)
        return Plan(jobj("recruit" to ident, "gold" to gold, "castle_state_after" to doc, "evidence_class" to EVIDENCE_FORMULA),
            listOf(roles(owned, listOf(Acquisition.GOLD)), S_WORK_REWARD to BattleReport.encodeReward(reward),
                message(state, 760, listOf(name, gold.toString())), servantsFrame(state)))
    }

    /**
     * C749 `u32 recruit` → S808 text 759 (name, total Gold earned) → S802 without the recruit → S800 (the Transmute bonus
     * loses property 75 per recruit) (`plan_release`).
     */
    fun planRelease(ident: Long, owned: Owned, inputs: DailyInputs, document: JValue?, now: Long): Plan {
        val state = owned.state
        val doc = servantsView(state, castleDocument(document, now), now, inputs).first
        val servant = servant(state, ident) ?: throw Acquisition.Rejected("Target Recruit not found", ERROR_TARGET)
        val section = sub(state, "servants").obj("servants")
        section["entries"] = JArr(section.arr("entries").filter { it.asObj["wire_u32_1"] != JInt(ident) }.toMutableList())
        section["count"] = JInt(section.arr("entries").size)
        val popped = (PyDocs.at(doc, "servants") as JObj).remove(ident.toString()) ?: jobj("gold" to 0)
        val total = PyDocs.at(popped as JObj, "gold")
        val (values, timers) = alchemyView(state, doc, inputs, now)
        values[4] = max(JInt(0), JInt(PyDocs.int(values[4]) - BigInteger.ONE))
        values[6] = max(JInt(0), JInt(PyDocs.int(values[6]) - BigInteger.valueOf(inputs.prop(75, 2000))))
        sub(state, "alchemy")["wire_values"] = values
        doc["alchemy"] = timers
        val name = servantName(servant)
        return Plan(jobj("recruit" to ident, "castle_state_after" to doc, "evidence_class" to EVIDENCE_POLICY),
            listOf(message(state, 759, listOf(name, PyDocs.str(total))), servantsFrame(state), alchemyFrame(values)))
    }

    /**
     * C781 `u32 recruit` ("lock up", property 232 Diamonds) → S128 Diamonds → S802 with the guard countdown set to the
     * recruit's remaining term (`plan_guard`).
     */
    fun planGuard(ident: Long, owned: Owned, inputs: DailyInputs, document: JValue?, now: Long, servedTime: Long): Plan {
        val state = owned.state
        val doc = servantsView(state, castleDocument(document, now), now, inputs).first
        val servant = servant(state, ident) ?: throw Acquisition.Rejected("Target Recruit not found", ERROR_TARGET)
        if (PyDocs.compare(sub(state, "servants").arr("wire_u8_prefix")[1], JInt(1)) < 0) {
            throw Acquisition.Rejected("Insufficient remaining Actions", ERROR_ACTIONS)
        }
        val price = inputs.prop(232, 100)
        if (owned.roleBits(Acquisition.DIAMOND) < BigInteger.valueOf(price)) throw Acquisition.Rejected("Not enough Diamonds", ERROR_RESOURCES)
        owned.roleAdd(Acquisition.DIAMOND, -price)
        val values = servant.arr("wire_values_after_string")
        values[GUARD_CD] = values[TERM]
        ((PyDocs.at(doc, "servants") as JObj)[ident.toString()] as JObj)["guard_until"] = JInt(BigInteger.valueOf(now) + PyDocs.int(values[TERM]))
        val packets = listOf(roles(owned, listOf(Acquisition.DIAMOND))) + Shops.diamondAchievement(owned, price, servedTime) +
            listOf(servantsFrame(state))
        return Plan(jobj("recruit" to ident, "price" to price, "castle_state_after" to doc, "evidence_class" to EVIDENCE_POLICY), packets)
    }
}
