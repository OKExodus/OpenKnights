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
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.protocol.BattleReport
import io.github.okexodus.openknights.protocol.WireReader
import io.github.okexodus.openknights.protocol.WireWriter
import java.math.BigInteger
import java.security.MessageDigest

/**
 * `quests.py`: the story quest list S320 (seeded once, then the story / [Sub] unlock rule, the owned-state and
 * level-state kinds and the one-time `claimed` backfill), the Quest Rewards (bounty) board S322, regenerated at the
 * first touch of a new local day from a seeded draw that includes the epoch second, and the quest actions: story
 * claims (C261) and the board requests (C259 … C275).
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
    const val S_QUEST_REWARD = 324
    const val S_ACHIEVEMENT = 578
    const val ROLE_LEVEL = 3L
    const val ROLE_EXPLOIT = 7L
    /** property 517: the bounty level scaling (4000 / 10000). */
    const val LEVEL_SCALE = 517L
    /** properties 300103 / 9001: 10 Diamonds each. */
    const val STAR_REFRESH_PRICE = 300103L
    const val BOARD_REFRESH_PRICE = 9001L
    /** S578 achievement kinds advanced by a story / bounty claim. */
    const val ACH_QUESTS = 4L
    const val ACH_BOUNTY = 5L
    const val ERROR_NOT_LISTED = 11004
    const val ERROR_ACCEPT = 11000
    const val ERROR_QUIT = 11001
    const val ERROR_LIMIT = 11003
    const val ERROR_AUTO_BUSY = 70102
    const val ERROR_NOT_AUTO = 70104
    const val ERROR_NOT_READY = 70107
    const val ERROR_STARS = 70109
    const val ERROR_RESOURCES = 4000
    /** Seconds a C275 may precede the served auto-complete end (policy). */
    const val TIMER_TOLERANCE = 3L
    /** quest.csv col 117 reward kind (1 item, 3 gear). */
    const val ITEM_KIND_GEAR = 3L
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
     * item templates. */
    class Facts(val heroLevels: List<Long>, val heroStars: List<Long>, val gearLevels: List<Long>, val worn: List<List<Long>>,
                val stages: Map<Long, Long>, val held: Set<Long>)

    /**
     * The owned-state facts (`_owned_facts`). `owned`: the Owned view — held items are then every stack it counts (the
     * S18 bag, imported batches and acquired stacks); without one (callers that never read `held`) only the bag.
     */
    fun ownedFacts(state: JObj, inputs: AcquisitionInputs, owned: Owned? = null): Facts {
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
        if (owned != null) {
            for (entry in owned.items.values) if (entry.count > 0) held.add(entry.template)
        } else {
            for (i in (state["items"] as? JArr) ?: JArr()) {
                val wire = i.asObj.arr("wire_values")
                if (PyDocs.compare(wire[2], JInt(0)) > 0) held.add(PyDocs.long(wire[1]))
            }
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

    /**
     * Owned-state kinds switch to claimable when the save meets them (after the level-state kinds). `owned`: the Owned
     * view whose item stacks count as held (kind 48).
     */
    fun refreshOwned(document: JObj, inputs: DailyInputs, state: JObj, owned: Owned? = null): Boolean {
        val levelled = refreshLevels(document, inputs, state)
        val rows = document.arr("quests").map { it.asArr }.filter { it[1] == JInt(STATE_RUNNING) }
            .mapNotNull { row -> inputs.quest(PyDocs.long(row[0]))?.let { row to it } }
            .filter { it.second.long("kind") in OWNED_STATE_KINDS }
        if (rows.isEmpty()) return levelled
        val facts = ownedFacts(state, inputs, owned)
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

    /** `property(key)` through `int()` (a non-number raises ValueError); absent or blank: the default (`_prop`). */
    private fun prop(inputs: AcquisitionInputs, key: Long, default: Long): BigInteger {
        val value = inputs.property(key)
        return if (value != null && value != "") PyValues.parseInt(value) else BigInteger.valueOf(default)
    }

    /** A quest's (EXP, Gold, Honor, points). */
    class QuestReward(val exp: BigInteger, val gold: BigInteger, val honor: BigInteger, val points: BigInteger)

    /**
     * (exp, gold, honor, points) (`quest_reward`): mode 1 flat, mode 2 × level × property 517 / 10000 (bounty: × (1 +
     * the star bonus), points × (1 + the points bonus)). Other modes grant no EXP / Gold / Honor (policy).
     */
    fun questReward(quest: JObj, level: BigInteger, inputs: DailyInputs, stars: JValue? = null): QuestReward {
        val mode = PyDocs.int(PyDocs.at(quest, "reward_mode"))
        var exp = PyDocs.int(PyDocs.at(quest, "exp"))
        var gold = PyDocs.int(PyDocs.at(quest, "gold"))
        var honor = PyDocs.int(PyDocs.at(quest, "honor"))
        var points = PyDocs.int(PyDocs.at(quest, "points"))
        if (mode == BigInteger.TWO) {
            val scale = prop(inputs, LEVEL_SCALE, 4000)
            val bonus = if (Py.truthy(stars)) inputs.bountyStar(PyDocs.long(stars)) else null
            fun mul(value: BigInteger, extra: String): BigInteger {
                val add = if (bonus != null) PyDocs.int(PyDocs.at(bonus, extra)) else BigInteger.ZERO
                return PyInt.floorDiv(value * level * scale * (BigInteger.valueOf(10000) + add), BigInteger.valueOf(100_000_000))
            }
            exp = mul(exp, "exp_bonus")
            gold = mul(gold, "gold_bonus")
            honor = mul(honor, "honor_bonus")
            if (bonus != null) points = PyInt.floorDiv(points * (BigInteger.valueOf(10000) + PyDocs.int(PyDocs.at(bonus, "points_bonus"))), BigInteger.valueOf(10000))
        } else if (mode != BigInteger.ONE) {
            exp = BigInteger.ZERO
            gold = BigInteger.ZERO
            honor = BigInteger.ZERO
        }
        return QuestReward(exp, gold, honor, points)
    }

    /**
     * S128 EXP (player level-ups included), S128 Gold (+ Exploit), [gear or item], and the Reward (`_grant_quest`,
     * the live order of both captured claim chains). quest.csv col 117 = 3 grants gear, else an item.
     */
    fun grantQuest(owned: Owned, quest: JObj, exp: BigInteger, gold: BigInteger, honor: BigInteger): Pair<List<Frame>, JObj> {
        val reward = Acquisition.emptyReward()
        val frames = ArrayList<Frame>()
        if (exp.signum() != 0) {
            frames.addAll(PlayerLevel.grantExp(owned, exp, owned.inputs).first)
            reward["exp"] = JInt(exp)
        }
        val fields = ArrayList<Long>()
        if (gold.signum() != 0) {
            owned.roleAdd(Acquisition.GOLD, gold)
            reward["gold"] = JInt(gold)
            fields.add(Acquisition.GOLD)
        }
        if (honor.signum() != 0) {
            owned.roleAdd(ROLE_EXPLOIT, honor)
            reward["exploit"] = JInt(honor)
            fields.add(ROLE_EXPLOIT)
        }
        if (fields.isNotEmpty()) {
            frames.add(Acquisition.S_ROLE to Acquisition.roleUpdatePayload(fields.map { Triple(it, owned.role(it).long("tag"), owned.roleBits(it)) }))
        }
        val item = PyDocs.at(quest, "item")
        val count = PyDocs.at(quest, "item_count")
        if (Py.truthy(item) && Py.truthy(count) && quest["item_flag"] == JInt(ITEM_KIND_GEAR)) {
            // col 117 = the reward kind: 1 item, 3 gear (the "[Sub] Fortify Weapons" rows)
            var n = BigInteger.ZERO
            while (n < PyDocs.int(count)) {
                val groups = owned.grantEquipment(PyDocs.long(item)).second
                frames.addAll(groups.getValue("add"))
                frames.addAll(groups.getValue("book"))
                reward.arr("equips").add(jarr(item))
                n += BigInteger.ONE
            }
        } else if (Py.truthy(item) && Py.truthy(count)) {
            frames.add(owned.grantItem(PyDocs.long(item), PyDocs.long(count)))
            reward.arr("items").add(jarr(item, count))
        }
        return frames to reward
    }

    /** The first achievement entry of a kind: progress + 1 → its S578 (`_achievement`); none: no frame. */
    fun achievement(owned: Owned, kind: Long): List<Frame> {
        for (e in owned.state.obj("subsystems").obj("achievements").arr("entries")) {
            val wire = e.asObj.arr("wire_values")
            if (wire[0] == JInt(kind)) {
                wire[2] = JInt(PyDocs.int(wire[2]) + BigInteger.ONE)
                return listOf(S_ACHIEVEMENT to WireWriter().values("BBI", wire).bytes())
            }
        }
        return emptyList()
    }

    /** `u32 quest` of C259 / C261 / C263 / C269 / C271 / C273 (`decode_task`). */
    fun decodeTask(payload: ByteArray, opcode: Int): JObj {
        if (payload.size != 4) throw Acquisition.Rejected("C$opcode is u32 quest id")
        return jobj("quest" to WireReader(payload).number('I'))
    }

    /** S324 `u32 quest, Reward`. */
    private fun rewardFrame(quest: JValue, reward: JObj): Frame =
        S_QUEST_REWARD to WireWriter().number('I', quest).raw(BattleReport.encodeReward(reward)).bytes()

    /**
     * C261 on a claimable story quest (`plan_story_claim`) → grants, S324, S320, S578 [4, …, +1]. New quests: the unlock
     * rule at the level after the claim's EXP; a document without its `claimed` list keeps the old successor rule
     * (col 120 = the claimed id, col 108 ≤ the level after the claim's EXP).
     */
    fun planStoryClaim(request: JObj, owned: Owned, inputs: DailyInputs, document: JObj?, level: Long): Plan {
        val doc = refreshStates(copy(document ?: throw PyDocs.TypeError("'NoneType' object is not subscriptable")), inputs, level)
        val ident = PyDocs.at(request, "quest")
        val row = doc.arr("quests").firstOrNull { it.asArr[0] == ident }
        val quest = inputs.quest(PyDocs.long(ident))
        if (row == null || quest == null) throw Acquisition.Rejected("The quest is not on the list", ERROR_NOT_LISTED)
        if (row.asArr[1] != JInt(STATE_READY)) throw Acquisition.Rejected("The quest is not claimable", ERROR_NOT_READY)
        val r = questReward(quest, BigInteger.valueOf(level), inputs)
        val (grant, reward) = grantQuest(owned, quest, r.exp, r.gold, r.honor)
        val frames = ArrayList(grant)
        doc["quests"] = JArr(doc.arr("quests").filter { it.asArr[0] != ident }.toMutableList())
        doc["points"] = JInt(PyDocs.int(PyDocs.at(doc, "points")) + r.points)
        if (doc.containsKey("claimed")) {
            val claimed = sortedSetOf<BigInteger>()
            for (c in doc.arr("claimed")) claimed.add(PyDocs.int(c))
            claimed.add(PyDocs.int(ident))
            doc["claimed"] = JArr(claimed.mapTo(ArrayList()) { JInt(it) })
            unlock(doc, inputs, owned.roleBits(ROLE_LEVEL).longValueExact())
        } else {
            val known = doc.arr("quests").map { it.asArr[0] }.toSet()
            val levelAfter = owned.roleBits(ROLE_LEVEL)
            val successors = inputs.quests().values.filter { it["prerequisite"] == ident }.map { it.long("id") }.sorted()
            for (successor in successors) {
                val candidate = inputs.quest(successor)!!
                if (JInt(successor) !in known && successor !in BOUNTY_IDS && BigInteger.valueOf(candidate.long("min_level")) <= levelAfter) {
                    doc.arr("quests").add(jarr(successor, STATE_RUNNING, 0))
                }
            }
        }
        refreshStates(doc, inputs, owned.roleBits(ROLE_LEVEL).longValueExact())
        refreshOwned(doc, inputs, owned.state, owned)   // a successor may already be met by the save
        frames.add(rewardFrame(ident, reward))
        frames.add(S_QUESTS to questsPayload(doc))
        frames.addAll(achievement(owned, ACH_QUESTS))
        return Plan(jobj("quest" to ident, "points" to r.points, "quest_state_after" to doc,
            "evidence_class" to "capture_observed_csv_calculation"), frames)
    }

    /** The board row of a quest id; missing: 70100 (`_row`). */
    private fun row(document: JObj, quest: JValue?): JArr =
        document.arr("rows").firstOrNull { it.asArr[0] == quest }?.asArr ?: throw Acquisition.Rejected("Cannot find related bounty quest", 70100)

    /**
     * The bounty board (`plan_bounty`): C259 accept / C263 quit / C271 star refresh / C273 auto / C269 expedite / C275
     * timer end / C261 ready claim / C265 board refresh, on the board rolled to today.
     */
    fun planBounty(opcode: Int, request: JObj, owned: Owned, inputs: DailyInputs, questsDoc: JObj?, boardDoc: JObj?, now: Long,
                   ownerKey: String, serverTime: Long? = null, forcedStars: JValue? = null): Plan {
        val board = boardRoll(boardDoc, inputs, now, ownerKey).first
        val level = owned.roleBits(ROLE_LEVEL)
        val frames = ArrayList<Frame>()
        val quests = questsDoc?.let { copy(it) }
        val nowBig = BigInteger.valueOf(now)
        if (opcode == C_BOUNTY_REFRESH) {
            if (PyDocs.compare(PyDocs.at(board, "used"), PyDocs.at(board, "limit")) >= 0) throw Acquisition.Rejected("Bounty Quest limit reached today", ERROR_LIMIT)
            // a running auto completion is never dropped by a fresh board (the client's own text 70102)
            if (Py.truthy(PyDocs.at(board, "auto_id"))) throw Acquisition.Rejected("There are still bounty quests being auto completing, please wait.", ERROR_AUTO_BUSY)
            // free only once the board cd ran out, which the day change always precedes (the cd counts 86,400 s from the
            // day's first touch, not to the reset)
            if (PyDocs.compare(PyDocs.at(board, "board_until"), JInt(now)) > 0) {
                val price = prop(inputs, BOARD_REFRESH_PRICE, 10)
                frames.addAll(Shops.diamondAchievement(owned, price.longValueExact(), serverTime))
                frames.add(owned.roleAdd(Acquisition.DIAMOND, price.negate()))
            }
            val fresh = newBoard(inputs, now, "$ownerKey|refresh|$now")
            fresh["used"] = PyDocs.at(board, "used")
            fresh["free"] = PyDocs.at(board, "free")
            frames.add(0, S_BOARD to boardPayload(fresh, now))
            return Plan(jobj("opcode" to opcode, "bounty_board_after" to fresh, "evidence_class" to "native_use_policy"), frames)
        }
        if (opcode == C_BOUNTY_TIMER && (!Py.truthy(PyDocs.at(board, "auto_id")) ||
                PyDocs.compare(PyDocs.at(board, "auto_until"), JInt(now + TIMER_TOLERANCE)) > 0)) {
            // checked before the row lookup: without an auto completion the route's quest id is 0
            throw Acquisition.Rejected("No finished auto completion", ERROR_NOT_AUTO)
        }
        var row = row(board, request["quest"])
        when (opcode) {
            C_BOUNTY_ACCEPT -> {
                if (row[1] != JInt(STATE_AVAILABLE)) throw Acquisition.Rejected("Not an available bounty quest", ERROR_ACCEPT)
                if (PyDocs.compare(PyDocs.at(board, "used"), PyDocs.at(board, "limit")) >= 0) throw Acquisition.Rejected("Bounty Quest limit reached today", ERROR_LIMIT)
                row[1] = JInt(STATE_RUNNING)
                board["used"] = JInt(PyDocs.int(PyDocs.at(board, "used")) + BigInteger.ONE)
            }
            C_BOUNTY_QUIT -> {
                if (row[1] != JInt(STATE_RUNNING) || PyDocs.at(board, "auto_id") == row[0]) throw Acquisition.Rejected("Not an ongoing bounty quest", ERROR_QUIT)
                row[1] = JInt(STATE_AVAILABLE)
                row[2] = JInt(0)
            }
            C_BOUNTY_STARS -> {
                // a ready / claimed task and the auto-completing one (its stars set its reward and its timer)
                if (row[1] == JInt(STATE_READY) || row[1] == JInt(STATE_DONE) || PyDocs.at(board, "auto_id") == row[0]) {
                    throw Acquisition.Rejected("Stars cannot be refreshed now", ERROR_STARS)
                }
                if (PyDocs.compare(PyDocs.at(board, "free"), JInt(0)) > 0) {
                    board["free"] = JInt(PyDocs.int(PyDocs.at(board, "free")) - BigInteger.ONE)
                } else {
                    val price = prop(inputs, STAR_REFRESH_PRICE, 10)
                    frames.addAll(Shops.diamondAchievement(owned, price.longValueExact(), serverTime))
                    frames.add(owned.roleAdd(Acquisition.DIAMOND, price.negate()))
                }
                // Random result (labeled local RNG policy); the verifier replays a captured outcome.
                row[3] = if (Py.truthy(forcedStars)) forcedStars!!
                    else JInt(drawStars(inputs, rng(ownerKey, "stars|${PyDocs.str(row[0])}|$now|${PyDocs.str(PyDocs.at(board, "free"))}")))
            }
            C_BOUNTY_AUTO -> {
                if (row[1] != JInt(STATE_RUNNING)) throw Acquisition.Rejected("Not an ongoing bounty quest", ERROR_NOT_AUTO)
                if (Py.truthy(PyDocs.at(board, "auto_id"))) throw Acquisition.Rejected("Another bounty quest is auto completing", ERROR_AUTO_BUSY)
                val star = inputs.bountyStar(PyDocs.long(row[3])) ?: throw PyDocs.TypeError("'NoneType' object is not subscriptable")
                board["auto_id"] = row[0]
                board["auto_until"] = JInt(nowBig + PyDocs.int(PyDocs.at(star, "auto_seconds")))
            }
            C_BOUNTY_TIMER -> {
                // The client's countdown can end a moment before the served clock; the auto id is cleared (policy). No / an
                // unfinished auto completion is refused above, before the row lookup.
                row = row(board, PyDocs.at(board, "auto_id"))
                row[1] = JInt(STATE_READY)
                board["auto_id"] = JInt(0)
                board["auto_until"] = JInt(0)
            }
            C_BOUNTY_EXPEDITE, C_QUEST_CLAIM -> {
                var price = BigInteger.ZERO
                if (opcode == C_BOUNTY_EXPEDITE) {
                    if (PyDocs.at(board, "auto_id") != row[0]) throw Acquisition.Rejected("This bounty quest is not auto completing", ERROR_NOT_AUTO)
                    val left = PyDocs.int(PyDocs.at(board, "auto_until")) - nowBig
                    price = PyInt.ceil(PyInt.trueDiv(left.max(BigInteger.ZERO), BigInteger.valueOf(60)))   // client price (policy)
                    if (owned.roleBits(Acquisition.DIAMOND) < price) throw Acquisition.Rejected("Not enough Diamonds", ERROR_RESOURCES)
                    board["auto_id"] = JInt(0)
                    board["auto_until"] = JInt(0)
                } else if (row[1] != JInt(STATE_READY)) {
                    throw Acquisition.Rejected("The quest is not claimable", ERROR_NOT_READY)
                } else if (PyDocs.at(board, "auto_id") == row[0]) {     // a left-over auto id goes with the claim
                    board["auto_id"] = JInt(0)
                    board["auto_until"] = JInt(0)
                }
                val quest = inputs.quest(PyDocs.long(row[0])) ?: throw PyDocs.TypeError("'NoneType' object is not subscriptable")
                val r = questReward(quest, level, inputs, stars = row[3])
                val (grant, reward) = grantQuest(owned, quest, r.exp, r.gold, r.honor)
                row[1] = JInt(STATE_DONE)
                frames.addAll(grant)
                frames.add(rewardFrame(row[0], reward))
                frames.add(S_BOARD_ROW to rowPayload(board, row, now))
                if (quests != null) {
                    quests["points"] = JInt(PyDocs.int(PyDocs.at(quests, "points")) + r.points)
                    refreshStates(quests, inputs, owned.roleBits(ROLE_LEVEL).longValueExact())      // a level-up can ready level quests
                    frames.add(S_QUESTS to questsPayload(quests))
                }
                if (opcode == C_BOUNTY_EXPEDITE && price.signum() != 0) {
                    frames.addAll(Shops.diamondAchievement(owned, price.longValueExact(), serverTime))
                    frames.add(owned.roleAdd(Acquisition.DIAMOND, price.negate()))
                }
                frames.addAll(achievement(owned, ACH_BOUNTY))
                return Plan(jobj("opcode" to opcode, "quest" to row[0], "points" to r.points, "bounty_board_after" to board,
                    "quest_state_after" to quests, "evidence_class" to (if (opcode == C_BOUNTY_EXPEDITE) "capture_observed_csv_calculation" else "native_use_policy")),
                    frames)
            }
        }
        frames.add(0, S_BOARD_ROW to rowPayload(board, row, now))
        val evidence = if (opcode == C_BOUNTY_ACCEPT || opcode == C_BOUNTY_STARS || opcode == C_BOUNTY_AUTO) "capture_observed" else "native_use_policy"
        return Plan(jobj("opcode" to opcode, "quest" to row[0], "bounty_board_after" to board, "evidence_class" to evidence), frames)
    }
}
