package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JNull
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.PyInt
import io.github.okexodus.openknights.exact.asArr
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.hexBytes
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.toHexString
import io.github.okexodus.openknights.protocol.PlayerSections
import io.github.okexodus.openknights.protocol.WireReader
import io.github.okexodus.openknights.protocol.WireWriter
import java.math.BigInteger

/**
 * The login / query parts of `castle.py`: the per-character Castle document (daily collect counts from local
 * midnight, Transmute timers, recruit deadlines), the next collect costs S226, the personal Guild Tech list S2330, the
 * buildings and Magic House techs that join at login, the recruits' countdowns and the Alchemy Lab values of the S18.
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

    private fun setBuilding(state: JObj, ident: Long, level: JValue) {
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
        val doc = if (PyDocs.truthy(document)) copy(document!!) else jobj("profile" to CASTLE_PROFILE)
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
        if (document == null || document.isEmpty() || !PyDocs.truthy(document["in_guild"])) return byteArrayOf(0)
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
        val timers = PyDocs.shallow(if (PyDocs.truthy(stored)) stored as JObj
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
    fun rescueListPayload(remaining: Long = 6, second: Long = 2): ByteArray = PyDocs.bytes(listOf(remaining, second, 0))
}
