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
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.protocol.WireReader
import io.github.okexodus.openknights.protocol.WireWriter
import java.math.BigInteger
import java.security.MessageDigest

/**
 * The login parts of `quests.py`: the story quest list S320 (seeded once, then the story / [Sub] unlock rule, the
 * owned-state and level-state kinds and the one-time `claimed` backfill) and the Quest Rewards (bounty) board S322,
 * regenerated at the first touch of a new local day from a seeded draw that includes the epoch second.
 */
object Quests {
    const val C_QUEST_CLAIM = 261
    const val C_BOUNTY_ACCEPT = 259
    const val C_BOUNTY_QUIT = 263
    const val C_BOUNTY_REFRESH = 265
    const val C_BOUNTY_EXPEDITE = 269
    const val C_BOUNTY_STARS = 271
    const val C_BOUNTY_AUTO = 273
    const val C_BOUNTY_TIMER = 275
    const val S_QUESTS = 320
    const val S_BOARD = 322
    const val S_BOARD_ROW = 326
    const val ROLE_LEVEL = 3L
    const val QUEST_PROFILE = "quest_state_v1"
    const val BOARD_PROFILE = "bounty_board_v1"
    val BOUNTY_IDS: List<Long> = (700101L..700115L).toList()
    const val BOARD_LIMIT = 25L
    const val FREE_STAR_REFRESH = 10L
    const val BOARD_SECONDS = 86400L
    const val STATE_AVAILABLE = 1L
    const val STATE_RUNNING = 2L
    const val STATE_READY = 3L
    const val STATE_DONE = 4L
    val STATE_KINDS = setOf(23L)
    val OWNED_STATE_KINDS = setOf(3L, 4L, 31L, 32L, 43L, 44L, 1L, 24L, 40L, 42L, 48L)
    val LEVEL_STATE_KINDS = linkedMapOf(2L to "buildings", 25L to "technologies")

    private fun copy(document: JValue): JObj = document.deepCopy() as JObj

    // --- codecs ------------------------------------------------------------------------------------------------------

    /** S320 `u8 n, n × (u32 id, u8 state, u32 progress), u32 points` (exact length). */
    fun decodeQuests(payload: ByteArray): JObj {
        val n = payload[0].toInt() and 0xFF
        val r = WireReader(payload).also { it.offset = 1 }
        val rows = JArr()
        repeat(n) { rows.add(r.values("IBI")) }
        val points = r.number('I')
        if (1 + 9 * n + 4 != payload.size) throw PyValues.ValueError("S320 has trailing bytes")
        return jobj("quests" to rows, "points" to points)
    }

    fun questsPayload(document: JObj): ByteArray {
        val rows = PyDocs.sorted(document.arr("quests"))
        val w = WireWriter().raw(PyDocs.bytes(listOf(rows.size.toLong())))
        for (row in rows) w.values("IBI", row.asArr)
        return w.number('I', PyDocs.at(document, "points")).bytes()
    }

    /** S322 `u8 n, n × (u32 id, u8 state, u32 progress, u8 stars), u32 used, u32 limit, i32 cd, u32 auto id, u32 auto
     * left, u32 free` (exact length). */
    fun decodeBoard(payload: ByteArray): JObj {
        val n = payload[0].toInt() and 0xFF
        val r = WireReader(payload).also { it.offset = 1 }
        val rows = JArr()
        repeat(n) { rows.add(r.values("IBIB")) }
        val tail = r.values("IIiIII")
        if (1 + 10 * n + 24 != payload.size) throw PyValues.ValueError("S322 has trailing bytes")
        return jobj("rows" to rows, "used" to tail[0], "limit" to tail[1], "cd" to tail[2], "auto_id" to tail[3],
            "auto_left" to tail[4], "free" to tail[5])
    }

    // --- story quests ------------------------------------------------------------------------------------------------

    /** Document from a seed S320 (fresh characters: 100101 + 800101, 0 points); no `claimed` list yet. */
    fun seedQuests(payload: ByteArray?, provenance: JValue?): JObj {
        val decoded = if (payload != null && payload.isNotEmpty()) decodeQuests(payload) else jobj("quests" to JArr(), "points" to 0)
        return jobj("profile" to QUEST_PROFILE, "quests" to decoded["quests"], "points" to decoded["points"],
            "seed" to (provenance ?: JNull))
    }

    private fun requirements(quest: JObj): List<Long> =
        listOf(quest.long("prerequisite"), quest.longOrNull("requires") ?: 0L).filter { it != 0L }

    /**
     * Append every quest the unlock rule offers as running (`[id, 2, 0]`): story rows (col 121 = 1) neither active nor
     * claimed nor a bounty task, their requirements claimed, min level ≤ level. A document without `claimed` unlocks
     * nothing. Returns whether anything was added.
     */
    fun unlock(document: JObj, inputs: DailyInputs, level: Long): Boolean {
        if (!document.containsKey("claimed")) return false
        val claimed = document.arr("claimed").map { PyDocs.long(it) }.toSet()
        val active = document.arr("quests").map { PyDocs.long(it.asArr[0]) }.toSet()
        var added = false
        for (ident in inputs.quests().keys.sorted()) {
            val quest = inputs.quest(ident)!!
            if (quest.long("story") != 1L || ident in claimed || ident in active || ident in BOUNTY_IDS) continue
            if (quest.long("min_level") > level || requirements(quest).any { it !in claimed }) continue
            document.arr("quests").add(jarr(ident, STATE_RUNNING, 0))
            added = true
        }
        return added
    }

    private fun ancestors(ids: Collection<Long>, inputs: DailyInputs): Set<Long> {
        val seen = LinkedHashSet<Long>()
        val stack = ArrayList(ids)
        while (stack.isNotEmpty()) {
            val quest = inputs.quest(stack.removeAt(stack.size - 1))
            for (parent in if (quest != null) requirements(quest) else emptyList()) {
                if (parent !in seen) { seen.add(parent); stack.add(parent) }
            }
        }
        return seen
    }

    private fun descendants(ids: Collection<Long>, inputs: DailyInputs): Set<Long> {
        val children = LinkedHashMap<Long, MutableList<Long>>()
        for (quest in inputs.quests().values) for (parent in requirements(quest)) children.getOrPut(parent) { ArrayList() }.add(quest.long("id"))
        val seen = LinkedHashSet<Long>()
        val stack = ArrayList(ids)
        while (stack.isNotEmpty()) {
            for (child in children[stack.removeAt(stack.size - 1)] ?: emptyList()) {
                if (child !in seen) { seen.add(child); stack.add(child) }
            }
        }
        return seen
    }

    /**
     * One-time `claimed` list for a document stored before it existed (fresh characters: the save's quest_claim history
     * plus every requirement ancestor of the active and claimed rows). Returns whether it was added.
     */
    fun backfillClaimed(document: JObj, inputs: DailyInputs, historyClaims: List<Long>?, level: Long, fresh: Boolean): Boolean {
        if (document.containsKey("claimed") || historyClaims == null) return false
        val active = document.arr("quests").map { PyDocs.long(it.asArr[0]) }.toSet()
        val claimed = LinkedHashSet(historyClaims)
        claimed.addAll(ancestors(active + claimed, inputs))
        if (!fresh) {
            val later = descendants(active, inputs)
            for (q in inputs.quests().values) {
                if (q.long("story") == 1L && q.long("min_level") <= level && q.long("id") !in later) claimed.add(q.long("id"))
            }
        }
        document["claimed"] = JArr((claimed - active).sorted().mapTo(ArrayList()) { JInt(it) })
        return true
    }

    /** State-based kinds (player level) switch to claimable when met. */
    fun refreshStates(document: JObj, inputs: DailyInputs, level: Long): JObj {
        for (r in document.arr("quests")) {
            val row = r.asArr
            val quest = inputs.quest(PyDocs.long(row[0]))
            if (quest != null && quest.long("kind") in STATE_KINDS && row[1] == JInt(STATE_RUNNING) && level >= quest.long("target")) {
                row[1] = JInt(STATE_READY)
            }
        }
        return document
    }

    /** The owned-state facts: hero levels, hero stars, gear levels, worn gear stars per lineup hero, stage stars, held
     * item templates (initialization items only). */
    class Facts(val heroLevels: List<Long>, val heroStars: List<Long>, val gearLevels: List<Long>, val worn: List<List<Long>>,
                val stages: Map<Long, Long>, val held: Set<Long>)

    fun ownedFacts(state: JObj, inputs: AcquisitionInputs): Facts {
        val heroes = ((state["heroes"] as? JArr) ?: JArr()).map { Acquisition.heroValues(it.asArr) }
        val gear = LinkedHashMap<Long, JArr>()
        for (r in (state["equipment"] as? JArr) ?: JArr()) {
            val wire = r.asObj.arr("wire_values")
            gear[PyDocs.long(wire[0])] = wire
        }
        val heroLevels = heroes.map { h -> h[2L]?.takeIf { Py.truthy(it) }?.let { PyDocs.long(it) } ?: 0L }
        val heroStars = heroes.map { h ->
            val template = h[1L]?.takeIf { Py.truthy(it) }?.let { PyDocs.long(it) } ?: 0L
            inputs.heroStar(template)?.takeIf { it != 0L } ?: 0L
        }
        val gearLevels = gear.values.map { PyDocs.long(it[2]) }
        val worn = ArrayList<List<Long>>()
        for (s in (state["formation"] as? JArr) ?: JArr()) {
            val slot = s.asObj
            if (Py.truthy(slot["hero_uid"])) {
                val stars = ArrayList<Long>()
                for (a in (slot["assignments"] as? JArr) ?: JArr()) {
                    val uid = PyDocs.long(a.asArr[1])
                    val record = gear[uid] ?: continue
                    stars.add(inputs.equipStar(PyDocs.long(record[1]))?.takeIf { it != 0L } ?: 0L)
                }
                worn.add(stars)
            }
        }
        val subsystems = state["subsystems"] as? JObj ?: JObj()
        val stagesSection = (PyDocs.get(subsystems, "stages") as? JObj)?.let { PyDocs.get(it, "stages") as? JObj } ?: JObj()
        val stages = LinkedHashMap<Long, Long>()
        for (e in (stagesSection["entries"] as? JArr) ?: JArr()) {
            val wire = e.asObj.arr("wire_values")
            stages[PyDocs.long(wire[0])] = PyDocs.long(wire[1])
        }
        val held = LinkedHashSet<Long>()
        for (i in (state["items"] as? JArr) ?: JArr()) {
            val wire = i.asObj.arr("wire_values")
            if (PyDocs.compare(wire[2], JInt(0)) > 0) held.add(PyDocs.long(wire[1]))
        }
        return Facts(heroLevels, heroStars, gearLevels, worn, stages, held)
    }

    /** The map of the stage of the nearest kind-1 quest up the prerequisite chain (kind 24), else null. */
    private fun mapOfAncestor(quest: JObj, inputs: DailyInputs): Long? {
        val seen = HashSet<Long>()
        var current = if (quest.long("prerequisite") != 0L) inputs.quest(quest.long("prerequisite")) else null
        while (current != null && current.long("id") !in seen) {
            seen.add(current.long("id"))
            if (current.long("kind") == 1L && current.long("param") != 0L) {
                val row = Campaign.stageRow(inputs, current.long("param"))
                return row?.get("117")
            }
            current = if (current.long("prerequisite") != 0L) inputs.quest(current.long("prerequisite")) else null
        }
        return null
    }

    fun ownedStateMet(quest: JObj, facts: Facts, inputs: DailyInputs?): Boolean {
        val kind = quest.long("kind")
        val param = quest.long("param")
        val target = quest.long("target")
        if (kind == 1L || kind == 40L) return param in facts.stages
        if (kind == 48L) return param !in facts.held
        if ((kind == 42L || kind == 24L) && inputs != null) {
            val mapId = if (kind == 42L) param else mapOfAncestor(quest, inputs)
            var wanted = if (mapId != null && mapId != 0L) Campaign.mapStages(inputs, mapId) else emptyList()
            if (kind == 24L) wanted = wanted.filter { Math.floorDiv(it, 10000L) == 1L }
            val need = if (kind == 42L) 3L else 1L
            return wanted.isNotEmpty() && wanted.all { (facts.stages[it] ?: 0L) >= need }
        }
        return when (kind) {
            3L -> (facts.heroLevels.maxOrNull() ?: 0L) >= target
            4L -> (facts.gearLevels.maxOrNull() ?: 0L) >= target
            31L -> facts.heroLevels.count { it >= target } >= maxOf(1L, param)
            32L -> facts.gearLevels.count { it >= target } >= maxOf(1L, param)
            43L -> facts.worn.any { stars -> stars.count { it >= param } >= target }
            44L -> facts.heroStars.count { it >= target } >= maxOf(1L, param)
            else -> false
        }
    }

    /** Level-state kinds (2 building, 25 tech; param = the id, 0 = any) follow the save's levels. */
    fun refreshLevels(document: JObj, inputs: DailyInputs, state: JObj): Boolean {
        val subsystems = PyDocs.get(state, "subsystems") as? JObj ?: JObj()
        val levels = LinkedHashMap<Long, Map<Long, JValue>>()
        for ((kind, name) in LEVEL_STATE_KINDS) {
            val m = LinkedHashMap<Long, JValue>()
            val section = PyDocs.get(subsystems, name) as? JObj ?: JObj()
            for (e in (section["entries"] as? JArr) ?: JArr()) {
                val wire = e.asObj.arr("wire_values")
                m[PyDocs.long(wire[0])] = wire[1]
            }
            levels[kind] = m
        }
        var changed = false
        for (r in document.arr("quests")) {
            val row = r.asArr
            val quest = inputs.quest(PyDocs.long(row[0]))
            if (quest == null || quest.long("kind") !in levels || row[1] != JInt(STATE_RUNNING)) continue
            val known = levels.getValue(quest.long("kind"))
            val value: JValue = if (quest.long("param") != 0L) known[quest.long("param")] ?: JInt(0)
                else known.values.maxWithOrNull(PyDocs.ORDER) ?: JInt(0)
            if (value != row[2]) { row[2] = value; changed = true }
            if (PyDocs.compare(value, JInt(quest.long("target"))) >= 0) { row[1] = JInt(STATE_READY); changed = true }
        }
        return changed
    }

    /** Owned-state kinds switch to claimable when the save meets them (after the level-state kinds). */
    fun refreshOwned(document: JObj, inputs: DailyInputs, state: JObj): Boolean {
        val levelled = refreshLevels(document, inputs, state)
        val rows = document.arr("quests").map { it.asArr }.filter { it[1] == JInt(STATE_RUNNING) }
            .mapNotNull { row -> inputs.quest(PyDocs.long(row[0]))?.let { row to it } }
            .filter { it.second.long("kind") in OWNED_STATE_KINDS }
        if (rows.isEmpty()) return levelled
        val facts = ownedFacts(state, inputs)
        var changed = levelled
        for ((row, quest) in rows) {
            if (ownedStateMet(quest, facts, inputs)) { row[1] = JInt(STATE_READY); changed = true }
        }
        return changed
    }

    /**
     * Counter hook (`count`): every running quest of the kind counts; it becomes claimable at its target. A `param`
     * must match quest.csv col 110 (0 matches any). Returns true when anything changed.
     */
    fun count(document: JObj, inputs: DailyInputs, kind: Long, amount: Long, param: Long? = null): Boolean {
        var changed = false
        for (r in document.arr("quests")) {
            val row = r.asArr
            val quest = inputs.quest(PyDocs.long(row[0]))
            if (quest == null || quest.long("kind") != kind || row[1] != JInt(STATE_RUNNING)) continue
            if (param != null && quest.long("param") != 0L && quest.long("param") != param) continue
            row[2] = JInt(PyDocs.int(row[2]) + BigInteger.valueOf(amount))
            if (PyDocs.compare(row[2], quest["target"]!!) >= 0) row[1] = JInt(STATE_READY)
            changed = true
        }
        return changed
    }

    /**
     * Level-state kinds (`set_state`: 2 building level, 25 tech level; param = the building / tech id): the progress of
     * every running quest of the kind becomes the value; claimable at its target. Returns true when anything changed.
     */
    fun setState(document: JObj, inputs: DailyInputs, kind: Long, value: Long, param: Long? = null): Boolean {
        var changed = false
        for (r in document.arr("quests")) {
            val row = r.asArr
            val quest = inputs.quest(PyDocs.long(row[0]))
            if (quest == null || quest.long("kind") != kind || row[1] != JInt(STATE_RUNNING)) continue
            if (param != null && quest.long("param") != 0L && quest.long("param") != param) continue
            if (row[2] != JInt(value)) {
                row[2] = JInt(value)
                changed = true
            }
            if (PyDocs.compare(JInt(value), quest["target"]!!) >= 0) {
                row[1] = JInt(STATE_READY)
                changed = true
            }
        }
        return changed
    }

    // --- bounty board ----------------------------------------------------------------------------------------------------

    /** `random.Random` seeded with the first 8 bytes (little-endian) of SHA-256 of `<owner_key>|<salt>`. */
    fun rng(ownerKey: String, salt: String): PyRandom {
        val digest = MessageDigest.getInstance("SHA-256").digest("$ownerKey|$salt".toByteArray(Charsets.UTF_8))
        return PyRandom.seeded(BigInteger(1, digest.copyOfRange(0, 8).reversedArray()))
    }

    /** Stars by the quest_bounty col 103 weights (labeled local RNG policy). */
    fun drawStars(inputs: DailyInputs, rng: PyRandom): Long {
        val rows = (1L..5L).map { inputs.bountyStar(it) ?: throw PyDocs.TypeError("'NoneType' object is not subscriptable") }
        return rng.choices(rows.map { it.long("stars") }, rows.map { it.long("weight") })[0]
    }

    fun newBoard(inputs: DailyInputs, now: Long, ownerKey: String): JObj {
        val rng = rng(ownerKey, "board|${Shops.dayOf(now)}|$now")
        val day = Shops.dayOf(now)
        val rows = JArr()
        for (i in BOUNTY_IDS) rows.add(jarr(i, STATE_AVAILABLE, 0, drawStars(inputs, rng)))
        return jobj("profile" to BOARD_PROFILE, "day" to day, "rows" to rows, "used" to 0, "limit" to BOARD_LIMIT,
            "board_until" to now + BOARD_SECONDS, "auto_id" to 0, "auto_until" to 0, "free" to FREE_STAR_REFRESH)
    }

    /** Document from a seed S322 (its `day` is none: the first touch regenerates it). */
    fun seedBoard(payload: ByteArray, now: Long, provenance: JValue?): JObj {
        val decoded = decodeBoard(payload)
        val autoId = decoded.long("auto_id")
        return jobj("profile" to BOARD_PROFILE, "day" to null, "rows" to decoded["rows"], "used" to decoded["used"],
            "limit" to decoded["limit"], "board_until" to now + decoded.long("cd"), "auto_id" to autoId,
            "auto_until" to (if (autoId != 0L) now + decoded.long("auto_left") else 0L), "free" to decoded["free"],
            "seed" to (provenance ?: JNull))
    }

    /** (document, changed): the board regenerates at the first touch of a new day. */
    fun boardRoll(document: JObj?, inputs: DailyInputs, now: Long, ownerKey: String): Pair<JObj, Boolean> {
        if (document == null || PyDocs.get(document, "day") != JStr(Shops.dayOf(now))) return newBoard(inputs, now, ownerKey) to true
        return copy(document) to false
    }

    private fun tail(document: JObj, now: Long): List<JValue> {
        val autoId = PyDocs.at(document, "auto_id")
        val left: JValue = if (Py.truthy(autoId)) JInt(maxOf(BigInteger.ZERO, PyDocs.int(PyDocs.at(document, "auto_until")) - BigInteger.valueOf(now))) else JInt(0)
        return listOf(PyDocs.at(document, "used"), PyDocs.at(document, "limit"), autoId, left, PyDocs.at(document, "free"))
    }

    /** S326 one board row `u32 id, u8 state, u32 progress, u8 stars` and the board's tail (`row_payload`). */
    fun rowPayload(document: JObj, row: JArr, now: Long): ByteArray {
        val (used, limit, autoId, left, free) = tail(document, now)
        return WireWriter().values("IBIB", row).number('I', used).number('I', limit).number('I', autoId).number('I', left)
            .number('I', free).bytes()
    }

    fun boardPayload(document: JObj, now: Long): ByteArray {
        val rows = document.arr("rows")
        val (used, limit, autoId, left, free) = tail(document, now)
        val w = WireWriter().raw(PyDocs.bytes(listOf(rows.size.toLong())))
        for (row in rows) w.values("IBIB", row.asArr)
        return w.number('I', used).number('I', limit).number('i', PyDocs.int(PyDocs.at(document, "board_until")) - BigInteger.valueOf(now))
            .number('I', autoId).number('I', left).number('I', free).bytes()
    }

    // --- the quest actions (C261 story claims, the bounty board C259 … C275) -----------------------------------------

    /** `u32 quest` of C259 / C261 / C263 / C269 / C271 / C273 (`decode_task`). */
    fun decodeTask(payload: ByteArray, opcode: Int): JObj = throw NotPorted("quests.decode_task")

    /** C261 on a claimable story quest (`plan_story_claim`). */
    fun planStoryClaim(request: JObj, owned: Owned, inputs: DailyInputs, document: JObj?, level: Long): Plan =
        throw NotPorted("quests.plan_story_claim")

    /** The bounty board requests and C261 on a board task (`plan_bounty`). */
    fun planBounty(opcode: Int, request: JObj, owned: Owned, inputs: DailyInputs, questsDoc: JObj?, boardDoc: JObj?, now: Long,
                   ownerKey: String, serverTime: Long? = null, forcedStars: JValue? = null): Plan = throw NotPorted("quests.plan_bounty")
}
