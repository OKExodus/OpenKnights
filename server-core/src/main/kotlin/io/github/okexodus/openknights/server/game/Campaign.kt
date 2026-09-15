package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JBool
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
import io.github.okexodus.openknights.protocol.BattleReport
import io.github.okexodus.openknights.protocol.PlayerSections
import io.github.okexodus.openknights.protocol.WireReader
import io.github.okexodus.openknights.protocol.WireWriter
import io.github.okexodus.openknights.server.store.StateStore
import java.math.BigInteger
import java.security.MessageDigest
import java.util.WeakHashMap

/**
 * The login parts of `campaign.py` (the battles belong to the campaign port): the per-character campaign document
 * (claimed star boxes S3234, reset day, regeneration anchors), the new-day reset of the stage records' daily bytes,
 * Action Points / Energy regeneration over the time the game was closed, and the release of a level-gated CurStage.
 */
object Campaign {
    const val S_BOXES = 3234
    const val ROLE_LEVEL = 3L
    const val ROLE_CUR_STAGE = 13L
    const val ROLE_MAX_STAMINA = 23L
    const val ENERGY = 10L
    const val ROLE_MAX_ENERGY = 24L
    const val P_REGEN_SECONDS = 20L
    const val P_ENERGY_REGEN_SECONDS = 21L
    const val PROFILE = "campaign_state_v1"

    /** (resource role, maximum role, document anchor key, period property, default seconds). */
    data class Regen(val valueRole: Long, val capRole: Long, val anchorKey: String, val periodKey: Long, val default: Long)

    val REGEN = listOf(Regen(Acquisition.STAMINA, ROLE_MAX_STAMINA, "regen_anchor", P_REGEN_SECONDS, 360),
        Regen(ENERGY, ROLE_MAX_ENERGY, "energy_regen_anchor", P_ENERGY_REGEN_SECONDS, 15))

    // --- tables --------------------------------------------------------------------------------------------------------

    /** `_int(value, default)`: `str(value).strip()` as an integer when it is (optionally signed) digits, else default. */
    fun int(value: String?, default: Long = 0): Long = PyValues.digitInt(value, default)

    private val tableCache = WeakHashMap<DailyInputs, HashMap<String, Map<Long, Map<String, Long>>>>()

    /** `{int(101): {field: int(cell)}}` of a table (non-digit cells → 0), cached on the inputs. */
    fun table(inputs: DailyInputs, name: String): Map<Long, Map<String, Long>> = synchronized(tableCache) {
        tableCache.getOrPut(inputs) { HashMap() }.getOrPut(name) {
            val out = LinkedHashMap<Long, Map<String, Long>>()
            for (r in inputs.tableRows(name)) {
                val key = int(r.field("101"))
                if (key == 0L) continue
                out[key] = LinkedHashMap<String, Long>().also { m -> for ((k, v) in r.fields()) m[k] = int(v) }
            }
            out
        }
    }

    fun stageRow(inputs: DailyInputs, stage: Long): Map<String, Long>? = table(inputs, "stage")[stage]

    fun mapStages(inputs: DailyInputs, mapId: Long): List<Long> =
        table(inputs, "stage").entries.filter { it.value["117"] == mapId }.map { it.key }.sorted()

    /** 1 normal, 2 elite, 3 epic — the leading digit. */
    fun modeOf(stage: Long): Long = Math.floorDiv(stage, 10000L)

    private fun prop(inputs: AcquisitionInputs, key: Long, default: Long): Long {
        val value = inputs.property(key)
        return if (value == null || value == "") default else int(value, default)
    }

    // --- state ------------------------------------------------------------------------------------------------------------

    private fun entries(state: JObj): JObj = state.obj("subsystems").obj("stages").obj("stages")

    /** {stage: [stars, fights today, entry open]} of the S18 stage section. */
    fun records(state: JObj): Map<Long, List<JValue>> {
        val out = LinkedHashMap<Long, List<JValue>>()
        for (e in entries(state).arr("entries")) {
            val wire = e.asObj.arr("wire_values")
            out[PyDocs.long(wire[0])] = wire.drop(1)
        }
        return out
    }

    fun role(owned: Owned, field: Long): BigInteger = owned.roleBits(field)

    private fun setRole(owned: Owned, field: Long, value: Long): Frame = owned.roleAdd(field, BigInteger.valueOf(value) - role(owned, field))

    fun newDocument(seedPayload: ByteArray?, provenance: JValue?): JObj {
        val boxes = JArr()
        if (seedPayload != null && seedPayload.isNotEmpty()) {
            val r = WireReader(seedPayload)
            val n = r.u16()
            repeat(n) { boxes.add(JInt(r.u32())) }
        }
        return jobj("profile" to PROFILE, "day" to null, "boxes" to boxes, "regen_anchor" to null, "seed" to (provenance ?: JNull))
    }

    /** S3234 `u16 n, n × u32 claimed box` (sorted). */
    fun boxesPayload(document: JObj): ByteArray {
        val boxes = PyDocs.sorted(document["boxes"]?.let { it as JArr } ?: JArr())
        val w = WireWriter().number('H', boxes.size.toLong())
        for (b in boxes) w.number('I', b)
        return w.bytes()
    }

    /** Local midnight: every stage record's daily bytes return to (0, 1). Returns whether the day changed. */
    fun dayRoll(state: JObj, document: JObj, now: Long): Boolean {
        val today = Shops.dayOf(now)
        if (PyDocs.get(document, "day") == JStr(today)) return false
        for (e in entries(state).arr("entries")) {
            val wire = e.asObj.arr("wire_values")
            wire[2] = JInt(0)
            wire[3] = JInt(1)
        }
        document["day"] = JStr(today)
        return true
    }

    private fun regenOne(owned: Owned, document: JObj, valueRole: Long, capRole: Long, anchorKey: String, period: Long, now: Long): Boolean {
        val value = role(owned, valueRole)
        val cap = role(owned, capRole)
        val anchor = PyDocs.get(document, anchorKey)
        if (value >= cap) {
            document[anchorKey] = JNull
            return false
        }
        if (anchor == null || PyDocs.compare(anchor, JInt(now)) > 0) {
            document[anchorKey] = JInt(now)
            return false
        }
        val anchorValue = PyDocs.int(anchor)
        val ticks = maxOf(BigInteger.ZERO, PyInt.floorDiv(BigInteger.valueOf(now) - anchorValue, BigInteger.valueOf(period)))
        if (ticks.signum() == 0) return false
        val gained = ticks.min(cap - value)
        owned.roleAdd(valueRole, gained)
        document[anchorKey] = if (value + gained >= cap) JNull else JInt(anchorValue + ticks * BigInteger.valueOf(period))
        return true
    }

    /** Action Points and Energy refill over time. Returns one S128 of the changed values or null. */
    fun regen(owned: Owned, document: JObj, inputs: AcquisitionInputs, now: Long): Frame? {
        val changed = ArrayList<Long>()
        for (r in REGEN) {
            try {
                role(owned, r.valueRole); role(owned, r.capRole)
            } catch (e: Acquisition.Rejected) {
                continue
            }
            if (regenOne(owned, document, r.valueRole, r.capRole, r.anchorKey, prop(inputs, r.periodKey, r.default), now)) changed.add(r.valueRole)
        }
        if (changed.isEmpty()) return null
        return Acquisition.S_ROLE to Acquisition.roleUpdatePayload(changed.map { f -> Triple(f, owned.role(f).long("tag"), owned.roleBits(f)) })
    }

    /**
     * CurStage (role 13) moves to the next normal stage (field 115) while the current one has been won and the level
     * meets the next one's field 116. Returns whether it moved.
     */
    fun advanceCurStage(owned: Owned, inputs: DailyInputs, level: Long? = null): Boolean {
        val lvl: BigInteger
        var current: Long
        try {
            lvl = if (level == null) role(owned, ROLE_LEVEL) else BigInteger.valueOf(level)
            current = role(owned, ROLE_CUR_STAGE).toLong()
        } catch (e: Acquisition.Rejected) {
            return false
        }
        val recs = records(owned.state)
        var moved = false
        val seen = HashSet<Long>()
        while (current in recs && current !in seen) {
            seen.add(current)
            val row = stageRow(inputs, current)
            val following = if (row != null) stageRow(inputs, row["115"] ?: 0L) else null
            if (following == null || modeOf(row!!.getValue("115")) != 1L || BigInteger.valueOf(following["116"] ?: 0L) > lvl) break
            setRole(owned, ROLE_CUR_STAGE, row.getValue("115"))
            current = row.getValue("115")
            moved = true
        }
        return moved
    }

    // === campaign battles (group 9, owned by the campaign slice) =====================================================
    // Lead-written constants + fixed-signature stubs so Session.campaignRoute wires in without conflicts; the campaign
    // slice replaces each stub body with the port of the matching `campaign.py` function. A NotPorted keeps the step
    // waiting until it is ported.

    const val C_BATTLE = 129
    const val C_AUTO = 131
    const val C_STAGE_INFO = 133
    const val C_REENTRY = 135
    const val C_MAP_CHEST = 137
    const val C_STAR_BOX = 2785
    const val S_REPORT = 4
    const val S_STAR = 160
    const val S_FIRST_KILL = 162
    const val S_AUTO = 608
    const val S_AUTO_HELL = 610
    const val S_BOX = 3232
    const val NO_HELPER_SLOT = 6
    const val ERR_INVALID = 102
    const val ERR_RESOURCES = 4000
    const val ERR_UNITS = 5001
    const val ERR_SUPPORT = 5003
    const val ERR_HELPER_CD = 5005
    const val ERR_NO_ATTEMPT = 5006
    const val ERR_REQUIREMENTS = 5010
    const val ERR_LEVEL = 103
    const val ERR_PROGRESS = 5002
    const val ERR_AUTO = 5004
    const val ERR_NO_NEED = 5008
    const val ERR_BOX_CLAIMED = 5011

    const val S_ACHIEVEMENT = 578
    const val S_HERO = 46
    const val S_ACTIVITY = 1184
    const val ROLE_EXP = 4L
    const val ROLE_EXPLOIT = 7L
    const val ROLE_FRIEND_POINT = 11L
    const val ROLE_STAR = 15L
    const val ROLE_EQUIP_BOOK = 17L

    /** normal / elite ("Hero") / epic ("Special"). */
    val BATTLE_TYPE = mapOf(1L to 202L, 2L to 1002L, 3L to 3202L)
    const val P_ELITE_FREE = 961L
    const val P_EPIC_FREE = 962L
    const val P_ELITE_PRICE = 529L
    const val P_EPIC_PRICE = 960L
    const val P_TOKEN_ITEM = 290L
    const val P_TOKEN_COUNT = 292L
    const val ACH_MAP_DONE = 12L
    const val ACH_HELPER_BATTLES = 30L
    const val ACH_THREE_STAR_STAGES = 32L
    const val ACH_STARS = 33L
    const val HELPER_POINTS = 30L

    const val HERO_UID = 0L
    const val HERO_TEMPLATE = 1L
    const val HERO_LEVEL = 2L
    const val HERO_HP = 4L
    const val HERO_ATK = 6L
    const val HERO_DEF = 8L
    const val HERO_UNIQUE = 10L
    const val HERO_AWAKEN = 24L

    /** `POLICY` (docs/CAMPAIGN_CONTRACT.md §6) — the transaction detail's `policy` block; a fresh copy per call. */
    fun policy(): JObj = jobj("exp_multiplier" to 1.0, "record_stars" to "max", "normal_auto" to "no_battle",
        "auto_fuse" to "equipment_3star_and_below_v1", "drops" to "stage_groups_v1", "regen_anchor" to "drop_below_max",
        "first_kill" to "first_local_winner", "map_chest" to "refused")

    /** A lost battle: the S4 is sent and nothing is committed (no AP, no attempt, no record change). */
    class Lost(val plan: Plan) : Exception("battle lost")

    /** Small deterministic PRNG for the settlement rolls (drops): SplitMix64 (`campaign.SplitMix64`). */
    class SplitMix64(seed: Long) {
        var state: ULong = seed.toULong()
        fun next(): ULong {
            state += 0x9E3779B97F4A7C15uL
            var z = state
            z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9uL
            z = (z xor (z shr 27)) * 0x94D049BB133111EBuL
            return z xor (z shr 31)
        }
        fun random(): Double = (next() shr 11).toLong().toDouble() / (1L shl 53).toDouble()
        fun randint(low: Long, high: Long): Long = low + (next() % (high - low + 1).toULong()).toLong()
    }

    /** `_roles_frame(owned, fields)`: one S128 of the fields' current (field, tag, bits). */
    private fun rolesFrame(owned: Owned, fields: List<Long>): Frame =
        Acquisition.S_ROLE to Acquisition.roleUpdatePayload(fields.map { f -> Triple(f, owned.role(f).long("tag"), owned.roleBits(f)) })

    /**
     * `battle_seed(character_id, revision, stage, now, helper, slot)`: first 8 bytes (LE) of SHA-256 as an unsigned
     * u64 (the reference's `int.from_bytes(..., "little")` — a non-negative int, stored unsigned in the history detail).
     */
    fun battleSeed(characterId: String, revision: Long, stage: Long, now: Long, helper: Long, slot: Long): BigInteger {
        val digest = MessageDigest.getInstance("SHA-256").digest("$characterId|$revision|$stage|$now|$helper|$slot".toByteArray(Charsets.UTF_8))
        var v = BigInteger.ZERO
        for (i in 0 until 8) v = v.or(BigInteger.valueOf(digest[i].toLong() and 0xFF).shiftLeft(8 * i))
        return v
    }

    // --- state helpers ---------------------------------------------------------------------------------------------------

    private fun stagesSection(state: JObj): JObj = state.obj("subsystems").obj("stages").obj("stages")

    /** `set_record`: update a record's wire values or append a new one and keep the count. */
    private fun setRecord(state: JObj, stage: Long, stars: Long, b: Long, c: Long) {
        val stages = stagesSection(state)
        for (e in stages.arr("entries")) {
            if (PyDocs.long(e.asObj.arr("wire_values")[0]) == stage) {
                e.asObj["wire_values"] = jarr(stage, stars, b, c)
                return
            }
        }
        stages.arr("entries").add(jobj("wire_values" to jarr(stage, stars, b, c)))
        stages["count"] = JInt(stages.arr("entries").size.toLong())
    }

    private fun spendStamina(owned: Owned, document: JObj, amount: Long, now: Long) {
        if (role(owned, Acquisition.STAMINA) < BigInteger.valueOf(amount)) throw Acquisition.Rejected("Not enough Action Points", ERR_RESOURCES)
        owned.roleAdd(Acquisition.STAMINA, -amount)
        if (role(owned, Acquisition.STAMINA) < role(owned, ROLE_MAX_STAMINA) && PyDocs.get(document, "regen_anchor") == null) document["regen_anchor"] = JInt(now)
    }

    // --- eligibility -----------------------------------------------------------------------------------------------------

    /** `check_stage`: the client prechecks mirrored on the server. Returns (row, mode, records). */
    fun checkStage(owned: Owned, inputs: DailyInputs, stage: Long, level: Long? = null): Triple<Map<String, Long>, Long, Map<Long, List<JValue>>> {
        val row = stageRow(inputs, stage)
        val mode = modeOf(stage)
        if (row == null || mode !in BATTLE_TYPE) throw Acquisition.Rejected("Unknown stage", ERR_INVALID)
        val recs = records(owned.state)
        val lvl = level ?: role(owned, ROLE_LEVEL).toLong()
        if ((row["116"] ?: 0L) > lvl) throw Acquisition.Rejected("Not Enough Character Level", ERR_LEVEL)
        if (mode == 1L) {
            if (stage !in recs && stage != role(owned, ROLE_CUR_STAGE).toLong()) throw Acquisition.Rejected("Stage not reached yet", ERR_PROGRESS)
        } else {
            val prerequisite = row["114"] ?: -1L
            if (prerequisite > 0 && prerequisite !in recs && stage !in recs) throw Acquisition.Rejected("Stage not unlocked yet", ERR_PROGRESS)
        }
        return Triple(row, mode, recs)
    }

    /** `check_attempt`: elite / epic allow a fight when the entry is open or fewer than P961 / P962 fights today. */
    fun checkAttempt(mode: Long, record: List<JValue>?, inputs: AcquisitionInputs) {
        if (mode == 1L || record == null) return
        val b = PyDocs.long(record[1])
        val c = PyDocs.long(record[2])
        val free = prop(inputs, if (mode == 2L) P_ELITE_FREE else P_EPIC_FREE, 1)
        if (c == 0L && b >= free) throw Acquisition.Rejected("No more attempt left", ERR_NO_ATTEMPT)
    }

    fun lineup(state: JObj): List<WorldParticipants.LineupEntry> {
        val entries = WorldParticipants.lineupOf(state)
        if (entries.isEmpty()) throw Acquisition.Rejected("Not enough combat units", ERR_UNITS)
        return entries
    }

    // --- drops (POLICY stage_groups_v1) ----------------------------------------------------------------------------------

    /** `roll_drops`: stage reward groups G1 / G2 / G3; first clear always grants the 302 entry. */
    fun rollDrops(row: Map<String, Long>, rng: SplitMix64, firstClear: Boolean): List<Triple<String, Long, Long>> {
        val out = ArrayList<Triple<String, Long, Long>>()
        if ((row["202"] ?: 0L) != 0L && rng.random() * 10000 < (row["203"] ?: 0L).toDouble()) {
            val low = row["204"] ?: 1L
            val high = maxOf(row["204"] ?: 1L, row["205"] ?: 1L)
            out.add(Triple("item", row.getValue("202"), rng.randint(low, high)))
        }
        fun pick(cols: List<Triple<String, String, String>>): Pair<Long, Long>? {
            val entries = cols.mapNotNull { (t, i, wk) -> if ((row[i] ?: 0L) != 0L) Triple(row[t] ?: 0L, row.getValue(i), row[wk] ?: 0L) else null }
            val total = entries.filter { it.third > 0 }.sumOf { it.third }
            if (total == 0L) return null
            var roll = rng.random() * total
            for ((kind, ident, weight) in entries) {
                if (weight <= 0) continue
                if (roll < weight) return kind to ident
                roll -= weight
            }
            return entries.last().let { it.first to it.second }
        }
        val g2 = listOf(Triple("207", "208", "209"), Triple("210", "211", "212"), Triple("213", "214", "215"))
        if ((row["206"] ?: 0L) != 0L && rng.random() * 10000 < row.getValue("206").toDouble()) {
            val chosen = pick(g2)
            if (chosen != null) out.add(Triple(if (chosen.first == 1L) "item" else "equipment", chosen.second, 1L))
        }
        val g3 = listOf(Triple("301", "302", "303"), Triple("304", "305", "306"), Triple("307", "308", "309"))
        if (firstClear && (row["302"] ?: 0L) != 0L) {
            out.add(Triple(if ((row["301"] ?: 0L) == 1L) "item" else "equipment", row.getValue("302"), 1L))
        } else if ((row["300"] ?: 0L) != 0L && rng.random() * 10000 < row.getValue("300").toDouble()) {
            val chosen = pick(g3)
            if (chosen != null) out.add(Triple(if (chosen.first == 1L) "item" else "equipment", chosen.second, 1L))
        }
        return out
    }

    private fun dropsJson(drops: List<Triple<String, Long, Long>>): JArr =
        JArr(drops.mapTo(ArrayList()) { jarr(it.first, it.second, it.third) })

    private fun dropsFromJson(value: JValue?): List<Triple<String, Long, Long>> =
        ((value as? JArr) ?: JArr()).map { val a = it.asArr; Triple((a[0] as JStr).value, PyDocs.long(a[1]), PyDocs.long(a[2])) }

    // --- settlement ------------------------------------------------------------------------------------------------------

    private fun achievement(owned: Owned, kind: Long, value: Long): List<Frame> {
        for (e in owned.state.obj("subsystems").obj("achievements").arr("entries")) {
            val wire = e.asObj.arr("wire_values")
            if (PyDocs.long(wire[0]) == kind) {
                if (PyDocs.long(wire[2]) == value) return emptyList()
                wire[2] = JInt(value)
                return listOf(S_ACHIEVEMENT to Acquisition.achievementPayload(wire))
            }
        }
        return emptyList()
    }

    private fun achievementValue(state: JObj, kind: Long): Long {
        for (e in state.obj("subsystems").obj("achievements").arr("entries")) {
            val wire = e.asObj.arr("wire_values")
            if (PyDocs.long(wire[0]) == kind) return PyDocs.long(wire[2])
        }
        return 0
    }

    /** `_grant`: item / equipment frames of the drops in drop order, the merged Reward and the flushed pending roles. */
    private fun grant(owned: Owned, drops: List<Triple<String, Long, Long>>, reward: JObj, pending: MutableSet<Long>): List<Frame> {
        val frames = ArrayList<Frame>()
        val items = LinkedHashMap<Long, Long>()
        for (pair in reward.arr("items")) {
            val a = pair.asArr
            val template = PyDocs.long(a[0])
            items[template] = (items[template] ?: 0L) + PyDocs.long(a[1])
        }
        for ((kind, ident, count) in drops) {
            if (kind == "item") {
                frames.add(owned.grantItem(ident, count))
                items[ident] = (items[ident] ?: 0L) + count
                if (pending.isNotEmpty()) {
                    frames.add(rolesFrame(owned, pending.sorted()))
                    pending.clear()
                }
            } else {
                repeat(count.toInt()) {
                    val (_, groups) = owned.grantEquipment(ident)
                    frames.addAll(groups.getValue("add"))
                    frames.addAll(groups.getValue("book").filter { it.first != Acquisition.S_ROLE })
                    if (groups.getValue("book").any { it.first == Acquisition.S_ROLE }) pending.add(ROLE_EQUIP_BOOK)
                    reward.arr("equips").add(jarr(ident))
                }
            }
        }
        val merged = items.entries.map { it.key to it.value }.sortedWith(compareBy({ it.first }, { it.second }))
        reward["items"] = JArr(merged.mapTo(ArrayList()) { jarr(it.first, it.second) })
        return frames
    }

    /** `_pin_capped_exp`: a lineup hero at its level cap keeps EXP 0 with an S46 (else nothing). */
    private fun pinCappedExp(state: JObj, uid: Long): List<Frame> {
        val fields = state.arr("heroes").firstOrNull { f ->
            f.asArr.any { x -> PyDocs.long(x.asObj["id"]) == 0L && x.asObj.obj("value")["bits"]?.let { PyDocs.long(it) } == uid }
        }?.asArr ?: return emptyList()
        val exp = fields.firstOrNull { PyDocs.long(it.asObj["id"]) == 3L }?.asObj ?: return emptyList()
        val bits = exp.obj("value")["bits"]
        if (bits == null || PyDocs.long(bits) == 0L) return emptyList()
        exp["value"] = PyDocs.shallow(exp.obj("value")).also { it["bits"] = JInt(0) }
        return listOf(S_HERO to HeroFortify.heroPropertyUpdatePayload(uid, jarr(exp)))
    }

    /** `settle_win`: the settlement of `count` won battles of one stage. Returns (frames, reward, detail). */
    fun settleWin(owned: Owned, current: StateStore.Current, inputs: DailyInputs, stage: Long, row: Map<String, Long>,
                  mode: Long, stars: Long, now: Long, document: JObj, helper: WorldParticipants.Participant? = null,
                  drops: List<Triple<String, Long, Long>>? = null, count: Long = 1, auto: Boolean = false): Triple<List<Frame>, JObj, JObj> {
        val state = owned.state
        val record = records(state)[stage]
        val firstClear = record == null
        val frames = ArrayList<Frame>()
        val detail = jobj("first_clear" to firstClear, "count" to count, "stars" to stars)
        val levelBefore = role(owned, ROLE_LEVEL).toLong()
        // 1. the stage record (S160) — not for normal Auto-play
        val newStars = if (record != null) maxOf(stars, PyDocs.long(record[0])) else stars
        if (!(auto && mode == 1L)) {
            val b = (if (record != null) PyDocs.long(record[1]) else 0L) + 1
            setRecord(state, stage, newStars, minOf(b, 255L), 0L)
            frames.add(S_STAR to WireWriter().number('I', stage).number('B', newStars).number('B', minOf(b, 255L)).number('B', 0L).bytes())
        }
        // 2. CurStage + Star (S128 {13, 15})
        val changed = ArrayList<Long>()
        if (mode == 1L && stage == role(owned, ROLE_CUR_STAGE).toLong() && advanceCurStage(owned, inputs, levelBefore)) changed.add(ROLE_CUR_STAGE)
        val total = records(state).values.sumOf { PyDocs.long(it[0]) }
        if (total != role(owned, ROLE_STAR).toLong()) {
            setRole(owned, ROLE_STAR, total)
            changed.add(ROLE_STAR)
            if (Prestige.promote(owned, inputs).isNotEmpty()) changed.add(Prestige.ROLE_TITLE)
        }
        if (changed.isNotEmpty()) frames.add(rolesFrame(owned, changed))
        // 3. EXP (+ level-ups) and the AP debit in one S128 {3?, 4, 9}
        val exp = PyInt.truncate((count * (row["111"] ?: 0L)).toDouble() * 1.0).toLong()
        spendStamina(owned, document, count * (row["126"] ?: 0L), now)
        val (levelFrames, levels) = PlayerLevel.grantExp(owned, exp, inputs)
        val roleFields = (if (levels.signum() != 0) listOf(ROLE_LEVEL) else emptyList()) + listOf(ROLE_EXP, Acquisition.STAMINA)
        frames.add(rolesFrame(owned, roleFields))
        frames.addAll(levelFrames.filter { it.first != Acquisition.S_ROLE })
        // 4. hero EXP: stage 113 per own lineup hero, once per request
        val reward = Acquisition.emptyReward()
        reward["exp"] = JInt(exp)
        reward["exploit"] = JInt(count * (row["112"] ?: 0L))
        val heroExp = row["113"] ?: 0L
        for (entry in lineup(state)) {
            val (heroFramesRaw, grow0, hdetail) = HiddenTraining.grantHeroExp(owned, inputs, JInt(entry.uid), heroExp)
            var heroFrames = heroFramesRaw
            if (heroFrames.isEmpty() && (hdetail["withheld"] as? JStr)?.value == "at the level cap") heroFrames = pinCappedExp(state, entry.uid)
            frames.addAll(heroFrames)
            var grow = grow0
            if (grow == null && heroExp > 0) grow = jarr(entry.uid, heroExp, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L)
            if (grow != null) reward.arr("hero_grow").add(grow)
            if (Py.truthy(hdetail["levels_gained"])) frames.add(S_ACTIVITY to PlayerSections.encodeSection("game_activities", state.obj("subsystems").obj("game_activities")))
        }
        val sortedGrow = reward.arr("hero_grow").sortedBy { PyDocs.long(it.asArr[0]) }
        reward["hero_grow"] = JArr(sortedGrow.toMutableList())
        // 5. Exploit (+ Pal Points) pending, then the drops in drop order
        val dropsList = drops ?: emptyList()
        owned.roleAdd(ROLE_EXPLOIT, PyDocs.long(reward.getValue("exploit")))
        val pending = linkedSetOf(ROLE_EXPLOIT)
        if (helper != null) {
            owned.roleAdd(ROLE_FRIEND_POINT, HELPER_POINTS)
            reward["friend_point"] = JInt(HELPER_POINTS)
            pending.add(ROLE_FRIEND_POINT)
        }
        frames.addAll(grant(owned, dropsList, reward, pending))
        // 6. map completion (12), three-star stages (32), total stars (33), then helper battles (30, +1)
        val recs = records(state)
        val mapId = row["117"] ?: 0L
        if (mode == 1L && firstClear && mapStages(inputs, mapId).all { it in recs }) {
            val currentBest = achievementValue(state, ACH_MAP_DONE)
            if (mapId > currentBest) frames.addAll(achievement(owned, ACH_MAP_DONE, mapId))
        }
        frames.addAll(achievement(owned, ACH_THREE_STAR_STAGES, recs.values.count { PyDocs.long(it[0]) >= 3 }.toLong()))
        frames.addAll(achievement(owned, ACH_STARS, recs.values.sumOf { PyDocs.long(it[0]) }))
        if (helper != null) {
            val currentHelpers = achievementValue(state, ACH_HELPER_BATTLES)
            frames.addAll(achievement(owned, ACH_HELPER_BATTLES, currentHelpers + 1))
        }
        detail["exp"] = JInt(exp)
        detail["levels_gained"] = JInt(levels)
        detail["drops"] = dropsJson(dropsList)
        detail["pending_roles"] = JArr(pending.sorted().mapTo(ArrayList()) { JInt(it) })
        return Triple(frames, reward, detail)
    }

    /** `flush_pending`: the S128 of the role changes no item-stack frame flushed. */
    fun flushPending(owned: Owned, detail: JObj): List<Frame> {
        val fields = ((detail["pending_roles"] as? JArr) ?: JArr()).map { PyDocs.long(it) }
        return if (fields.isNotEmpty()) listOf(rolesFrame(owned, fields)) else emptyList()
    }

    // --- requests without a battle ---------------------------------------------------------------------------------------

    fun decodeStage(payload: ByteArray, opcode: Int): JObj {
        if (payload.size != 4) throw Acquisition.Rejected("C$opcode is u32", ERR_INVALID)
        return jobj("stage" to WireReader(payload).u32())
    }

    fun decodeBattle(payload: ByteArray): JObj {
        if (payload.size != 9) throw Acquisition.Rejected("C129 is u32 stage, u32 helper, u8 slot", ERR_INVALID)
        val r = WireReader(payload)
        val stage = r.u32(); val helper = r.u32(); val slot = r.number('B').value.toLong()
        if (slot > NO_HELPER_SLOT || (helper != 0L && slot == NO_HELPER_SLOT.toLong())) throw Acquisition.Rejected("Support position error", ERR_SUPPORT)
        return jobj("stage" to stage, "helper" to helper, "slot" to slot)
    }

    fun decodeAuto(payload: ByteArray): JObj {
        if (payload.size != 14) throw Acquisition.Rejected("C131 is u32 stage, u32 helper, u8 slot, u8 autoFuse, u32 count", ERR_INVALID)
        val r = WireReader(payload)
        val stage = r.u32(); val helper = r.u32(); val slot = r.number('B').value.toLong(); val fuse = r.number('B').value.toLong(); val count = r.u32()
        if (count < 1 || count > 9999) throw Acquisition.Rejected("Auto-play count out of range", ERR_AUTO)
        return jobj("stage" to stage, "helper" to helper, "slot" to slot, "auto_fuse" to fuse, "count" to count)
    }

    /** `first_kill_payload(stage, entry, offset)`: S162 `u32 stage, cstring name, u32 epoch`. */
    fun firstKillPayload(stage: Long, entry: JObj?, offset: Long = 0): ByteArray {
        val name: ByteArray
        val epoch: Long
        if (entry != null) {
            name = entry.str("name_hex").hexBytes()
            epoch = maxOf(0L, PyDocs.long(entry.getValue("at")) + offset)
        } else {
            name = ByteArray(0); epoch = 0L
        }
        return WireWriter().number('I', stage).raw(name).raw(byteArrayOf(0)).number('I', epoch).bytes()
    }

    /** `plan_reentry` (C135): Diamond re-entry of an elite / epic stage. */
    fun planReentry(request: JObj, owned: Owned, inputs: DailyInputs, document: JObj, now: Long, serverTime: Long?): Plan {
        val stage = request.long("stage")
        val record = records(owned.state)[stage]
        val mode = modeOf(stage)
        if ((mode != 2L && mode != 3L) || record == null) throw Acquisition.Rejected("Requirements not met", ERR_REQUIREMENTS)
        val stars = PyDocs.long(record[0]); val b = PyDocs.long(record[1]); val c = PyDocs.long(record[2])
        if (c != 0L) throw Acquisition.Rejected("No need to buy more", ERR_NO_NEED)
        val extra = vipExtra(owned, inputs, mode, now)
        val free = prop(inputs, if (mode == 2L) P_ELITE_FREE else P_EPIC_FREE, 1)
        if (b >= free + extra) throw Acquisition.Rejected("No more attempt left", ERR_NO_ATTEMPT)
        val price = minOf(b, 3L) * prop(inputs, if (mode == 2L) P_ELITE_PRICE else P_EPIC_PRICE, if (mode == 2L) 5L else 10L)
        if (role(owned, Acquisition.DIAMOND) < BigInteger.valueOf(price)) throw Acquisition.Rejected("Not enough Resources", ERR_RESOURCES)
        val frames = ArrayList(Shops.diamondAchievement(owned, price, serverTime))
        setRecord(owned.state, stage, stars, b, 1L)
        frames.add(S_STAR to WireWriter().number('I', stage).number('B', stars).number('B', b).number('B', 1L).bytes())
        frames.add(owned.roleAdd(Acquisition.DIAMOND, -price))
        return Plan(jobj("stage" to stage, "price" to price, "campaign_state_after" to document, "evidence_class" to "native_use_capture_observed"), frames)
    }

    /** `_vip_extra`: paid entries per day by VIP level (viplv 127 elite / 141 epic). */
    private fun vipExtra(owned: Owned, inputs: DailyInputs, mode: Long, now: Long? = null): Long {
        var level = role(owned, Acquisition.VIP_LEVEL).toLong()
        if (now != null) {
            try {
                level = SweepFeatures.tmpVipLevel(owned.current, level, now)
            } catch (e: Exception) {
                // ImportError / KeyError / TypeError: keep the stored VIP level
            }
        }
        val row = viplvByLevel(inputs)[level]
        return int(row?.field(if (mode == 2L) "127" else "141"))
    }

    private val viplvCache = WeakHashMap<DailyInputs, Map<Long, io.github.okexodus.openknights.gamedata.GameTable.Row>>()

    /** viplv keyed by its level column 102 (raw rows), cached per loader. */
    private fun viplvByLevel(inputs: DailyInputs): Map<Long, io.github.okexodus.openknights.gamedata.GameTable.Row> = synchronized(viplvCache) {
        viplvCache.getOrPut(inputs) {
            val m = LinkedHashMap<Long, io.github.okexodus.openknights.gamedata.GameTable.Row>()
            for (r in inputs.tableRows("viplv")) m[int(r.field("102"))] = r
            m
        }
    }

    /** `map_stars`: Σ record stars of the map's stages. */
    fun mapStars(owned: Owned, inputs: DailyInputs, mapId: Long): Long {
        val recs = records(owned.state)
        return mapStages(inputs, mapId).sumOf { s -> recs[s]?.let { PyDocs.long(it[0]) } ?: 0L }
    }

    /** `plan_star_box` (C2785): claim a three-star treasure chest. */
    fun planStarBox(request: JObj, owned: Owned, inputs: DailyInputs, document: JObj): Plan {
        val box = request.long("stage")
        val row = table(inputs, "starbox")[box] ?: throw Acquisition.Rejected("Unknown star box", ERR_INVALID)
        val boxes = (document["boxes"] as? JArr) ?: JArr()
        if (boxes.any { PyDocs.long(it) == box }) throw Acquisition.Rejected("The 3-Star Treasure Chest has been claimed", ERR_BOX_CLAIMED)
        if (mapStars(owned, inputs, row["102"] ?: 0L) < (row["103"] ?: 0L)) throw Acquisition.Rejected("Requirements not met", ERR_REQUIREMENTS)
        val reward = Acquisition.emptyReward()
        val frames = ArrayList<Frame>()
        for (k in 0 until 4) {
            val kind = row[(105 + 3 * k).toString()] ?: 0L
            val ident = row[(106 + 3 * k).toString()] ?: 0L
            val count = row[(107 + 3 * k).toString()] ?: 0L
            if (ident == 0L || count <= 0) continue
            when (kind) {
                1L -> { frames.add(owned.grantItem(ident, count)); reward.arr("items").add(jarr(ident, count)) }
                3L -> repeat(count.toInt()) {
                    val (_, groups) = owned.grantEquipment(ident)
                    frames.addAll(groups.getValue("add")); frames.addAll(groups.getValue("book"))
                    reward.arr("equips").add(jarr(ident))
                }
                else -> throw Acquisition.Rejected("Star box reward kind $kind is not supported", ERR_INVALID)
            }
        }
        val sortedItems = reward.arr("items").map { it.asArr }.sortedWith(compareBy({ PyDocs.long(it[0]) }, { PyDocs.long(it[1]) }))
        reward["items"] = JArr(sortedItems.mapTo(ArrayList()) { jarr(it[0], it[1]) })
        val allBoxes = (boxes.map { PyDocs.long(it) } + box).sorted()
        val newDoc = PyDocs.shallow(document).also { it["boxes"] = JArr(allBoxes.mapTo(ArrayList()) { JInt(it) }) }
        frames.add(S_BOX to WireWriter().number('I', box).raw(BattleReport.encodeReward(reward)).bytes())
        return Plan(jobj("box" to box, "reward" to reward, "campaign_state_after" to newDoc, "evidence_class" to "capture_observed_csv"), frames)
    }

    /** Convert only newly awarded low-star equipment, using the ordinary gear refine recipe. */
    fun autoFuseDrops(inputs: AcquisitionInputs, drops: List<Triple<String, Long, Long>>): List<Triple<String, Long, Long>> =
        drops.map { drop ->
            val (kind, template, count) = drop
            val star = if (kind == "equipment") inputs.equipStar(template) else null
            if (star == null || star !in 1L..3L) drop else {
                // Campaign equipment is granted with its default super flag of zero.
                val row = inputs.composeRow("equiprh", star, 0)
                val item = (row?.get("104") as? JInt)?.value?.toLong()
                val amount = (row?.get("105") as? JInt)?.value?.toLong()
                if (item == null || item <= 0 || amount == null || amount <= 0)
                    throw Acquisition.Rejected("No refine row for this gear", Acquisition.ERROR_WRONG_TYPE)
                Triple("item", item, amount * count)
            }
        }

    /** `plan_auto` (C131): Auto-play of a won stage, normal xN (S608) or elite / epic (S610). */
    fun planAuto(request: JObj, owned: Owned, current: StateStore.Current, inputs: DailyInputs, document: JObj,
                 now: Long, rng: SplitMix64, forced: JObj? = null): Plan {
        val stage = request.long("stage")
        val count = request.long("count")
        val forcedDrops = forced?.get("drops")
        val (row, mode, recs) = checkStage(owned, inputs, stage)
        val record = recs[stage]
        if (record == null || PyDocs.long(record[0]) <= 0) throw Acquisition.Rejected("Unable to Auto-Battle now", ERR_AUTO)
        val frames = ArrayList<Frame>()
        val reward: JObj
        val detail: JObj
        if (mode == 1L) {
            if (role(owned, Acquisition.STAMINA) < BigInteger.valueOf(count * (row["126"] ?: 0L))) throw Acquisition.Rejected("Not enough Action Points", ERR_RESOURCES)
            val rolled = ArrayList<Triple<String, Long, Long>>()
            repeat(count.toInt()) { rolled.addAll(rollDrops(row, rng, firstClear = false)) }
            val awarded = if (forcedDrops != null) dropsFromJson(forcedDrops) else rolled
            val drops = if (Py.truthy(request["auto_fuse"])) autoFuseDrops(inputs, awarded) else awarded
            val (settle, rew, det) = settleWin(owned, current, inputs, stage, row, mode, PyDocs.long(record[0]), now = now, document = document, drops = drops, count = count, auto = true)
            reward = rew; detail = det
            frames.addAll(settle)
            frames.add(S_AUTO to BattleReport.encodeReward(rew))
            frames.addAll(flushPending(owned, det))
        } else {
            if (PyDocs.long(record[2]) == 0L) throw Acquisition.Rejected("Unable to Auto-Battle now", ERR_AUTO)
            val token = prop(inputs, P_TOKEN_ITEM, 30112)
            val tokens = prop(inputs, P_TOKEN_COUNT, 5)
            frames.addAll(owned.consumeTemplate(token, tokens))
            val awarded = if (forcedDrops != null) dropsFromJson(forcedDrops) else rollDrops(row, rng, firstClear = false)
            val drops = if (Py.truthy(request["auto_fuse"])) autoFuseDrops(inputs, awarded) else awarded
            val (settle, rew, det) = settleWin(owned, current, inputs, stage, row, mode, PyDocs.long(record[0]), now = now, document = document, drops = drops, count = 1)
            reward = rew; detail = det
            frames.addAll(settle)
            frames.add(S_AUTO_HELL to WireWriter().raw(byteArrayOf(1)).raw(BattleReport.encodeReward(rew)).bytes())
            frames.addAll(flushPending(owned, det))
        }
        return Plan(jobj("stage" to stage, "count" to count, "mode" to mode, "auto" to true, "detail" to detail, "reward" to reward,
            "campaign_state_after" to document, "policy" to "no_battle", "evidence_class" to "capture_observed_policy"), frames)
    }

    // --- the battle (C129) -----------------------------------------------------------------------------------------------

    /** `helper_participant` (C129 helper): the friend / participant whose captain replaces the own hero of the slot. */
    fun helperParticipant(request: JObj, owned: Owned, worldCtx: DailyRoutes.WorldContext?, social: JObj?, now: Long,
                          inputs: DailyInputs): WorldParticipants.Participant? {
        if (!Py.truthy(request["helper"])) return null
        if (request.long("slot") >= NO_HELPER_SLOT) throw Acquisition.Rejected("Support position error", ERR_SUPPORT)
        val people = if (worldCtx != null) worldCtx.participants(owned.current).map { it as WorldParticipants.Participant }.associateBy { it.participantId } else emptyMap()
        val person = people[request.long("helper")]
        val own = role(owned, 0L).toLong()
        if (person == null || person.participantId == own || person.lineup.isEmpty()) throw Acquisition.Rejected("Support position error", ERR_SUPPORT)
        if (Friends.helperUntil(social ?: JObj(), own, person.participantId, inputs) > now) throw Acquisition.Rejected("Exceeded the Summon time limit", ERR_HELPER_CD)
        return person
    }

    private fun heroMap(fields: JArr): Map<Long, Long> {
        val m = LinkedHashMap<Long, Long>()
        for (f in fields) {
            val o = f.asObj
            m[PyDocs.long(o["id"])] = o.obj("value")["bits"]?.let { PyDocs.long(it) } ?: 0L
        }
        return m
    }

    /** `own_lineup`: engine lineup entries of the own formation (battle-mode stats). Returns (entries, unresolved). */
    private fun ownLineup(state: JObj, inputs: DailyInputs, world: JObj?): Pair<List<JValue>, JArr> {
        val heroes = LinkedHashMap<Long, Map<Long, Long>>()
        for (f in state.arr("heroes")) { val hm = heroMap(f.asArr); heroes[hm[HERO_UID] ?: 0L] = hm }
        val actors = BattleStats.battleActors(state, inputs, world)
        val captainRaw = state["captain_slot"]
        val captain = if (captainRaw == null || captainRaw == JNull) 0L else PyDocs.long(captainRaw)
        val entries = ArrayList<JValue>()
        val sorted = actors.obj("actors").entries.sortedBy { PyDocs.long(it.value.asObj.getValue("slot_id")) }
        for ((posKey, slotV) in sorted) {
            val slot = slotV.asObj
            val hero = heroes[PyDocs.long(slot.getValue("hero_uid"))] ?: emptyMap()
            entries.add(jobj("uid" to slot.getValue("hero_uid"), "packed" to (hero[HERO_TEMPLATE] ?: 0L),
                "level" to (hero[HERO_LEVEL] ?: 1L), "position" to posKey.toLong(), "awaken" to (hero[HERO_AWAKEN] ?: 0L),
                "slot_id" to slot.getValue("slot_id"), "leader" to (PyDocs.long(slot.getValue("slot_id")) == captain),
                "stats" to jobj("hp" to slot.getValue("hp"), "atk" to slot.getValue("atk"), "def" to slot.getValue("def"), "crit" to slot.getValue("crit"))))
        }
        if (entries.isNotEmpty() && entries.none { Py.truthy((it as JObj)["leader"]) }) (entries[0] as JObj)["leader"] = JBool(true)
        return entries to ((actors["unresolved"] as? JArr) ?: JArr())
    }

    /** `with_helper`: the helper's hero replaces the own hero at report position slot + 1 and is listed last. */
    private fun withHelper(entries: List<JValue>, helperEntry: JObj?, slot: Long): List<JValue> {
        if (helperEntry == null) return entries
        val kept = entries.filter { PyDocs.long((it as JObj).getValue("position")) != slot + 1 }.toMutableList()
        if (kept.isNotEmpty() && kept.none { Py.truthy((it as JObj)["leader"]) }) kept[0] = PyDocs.shallow(kept[0] as JObj).also { it["leader"] = JBool(true) }
        val full = PyDocs.shallow(helperEntry).also { it["position"] = JInt(slot + 1); it["helper"] = JBool(true); it["leader"] = JBool(false) }
        return kept + full
    }

    /** `base_hero_stats` (POLICY bot_helper_base_hero): a bot helper's template grown to its level through Fortify. */
    fun baseHeroStats(inputs: DailyInputs, template: Long, level: Long): JObj {
        val fields = inputs.freshHeroFields(1L, template)
        val config = HiddenTraining.heroExpInputs(inputs, template)
        val target = minOf(level, config.long("cap"))
        val heroexp = config.obj("heroexp")
        var need = 0L
        for (lv in 1 until target) need += HeroDictionaryProgression.expForLevel(PyDocs.long(heroexp.getValue(lv.toString())), config.long("scale_137"))
        if (need > 0) {
            val fakeState = jobj("heroes" to JArr(arrayListOf<JValue>(fields)), "items" to JArr())
            val cur = StateStore.Current(1, "", "", fakeState, ByteArray(0), 0, null, JArr(), emptyList(), JArr(), emptyList(), null, null, null, null, null, LinkedHashMap())
            HiddenTraining.grantHeroExp(Owned(cur, inputs), inputs, JInt(1L), need)
        }
        val hero = heroMap(fields)
        return jobj("hp" to (hero[HERO_HP] ?: 0L), "atk" to (hero[HERO_ATK] ?: 0L), "def" to (hero[HERO_DEF] ?: 0L), "crit" to (hero[HERO_UNIQUE] ?: 0L))
    }

    /** `helper_entry`: the engine entry of the helper participant's captain hero (own slot stats or `base_hero_stats`). */
    private fun helperEntry(person: WorldParticipants.Participant, inputs: DailyInputs, helperState: JObj?): JObj {
        val captain = person.lineup.firstOrNull { it.template == person.leaderTemplate } ?: person.lineup[0]
        val entry = jobj("uid" to 0, "packed" to captain.template, "level" to captain.level, "awaken" to captain.awaken)
        if (helperState != null) {
            val slotId = (helperState["captain_slot"]?.takeIf { it != JNull }?.let { PyDocs.long(it) }) ?: 0L
            val slot = BattleStats.slotStats(helperState, slotId, inputs, world = null, mode = "battle")
            if (Py.truthy(slot["counted"])) {
                return PyDocs.shallow(entry).also {
                    it["stats"] = jobj("hp" to slot.getValue("hp"), "atk" to slot.getValue("atk"), "def" to slot.getValue("def"), "crit" to slot.getValue("crit"))
                    it["policy"] = JStr("character_helper_captain_slot")
                }
            }
        }
        return PyDocs.shallow(entry).also {
            it["stats"] = baseHeroStats(inputs, captain.template, captain.level)
            it["policy"] = JStr("bot_helper_base_hero")
        }
    }

    /** `own_name(state)`: the character's name (role property 2, UTF-8, replacement on error). */
    fun ownName(state: JObj): String {
        val raw = state.arr("role_properties").map { it.asObj }.firstOrNull { PyDocs.long(it["id"]) == 2L }
            ?.obj("value")?.get("raw_hex")?.let { (it as? JStr)?.value } ?: ""
        return String(raw.hexBytes(), Charsets.UTF_8)
    }

    private fun encounterLevel(inputs: DailyInputs, row: Map<String, Long>): Long? {
        val monster = table(inputs, "monster")[row["999"] ?: 0L] ?: return null
        return monster["102"]
    }

    /** `plan_battle` (C129): checks, the engine battle, then settlement + S4 on a win or the S4 only on a loss. */
    fun planBattle(request: JObj, owned: Owned, current: StateStore.Current, inputs: DailyInputs, document: JObj,
                   now: Long, world: JObj?, helper: WorldParticipants.Participant?, helperState: JObj?, seed: Long,
                   forced: JObj? = null): Plan {
        val stage = request.long("stage")
        val (row, mode, recs) = checkStage(owned, inputs, stage)
        checkAttempt(mode, recs[stage], inputs)
        if (role(owned, Acquisition.STAMINA) < BigInteger.valueOf(row["126"] ?: 0L)) throw Acquisition.Rejected("Not enough Action Points", ERR_RESOURCES)
        lineup(owned.state)
        var unresolved: JArr = JArr()
        var helperPolicy: JValue = JNull
        val outcome: JObj
        val forcedReport = forced?.get("report")
        if (forcedReport != null && forcedReport != JNull) {
            val fr = forcedReport.asObj
            outcome = jobj("result" to fr.getValue("result_raw"), "stars" to fr.getValue("display_stars"),
                "report" to fr.deepCopy(), "seed" to null, "engine_version" to "forced")
        } else {
            val (entries, unres) = ownLineup(owned.state, inputs, world)
            unresolved = unres
            if (entries.isEmpty()) throw Acquisition.Rejected("Not enough combat units", ERR_UNITS)
            val extra = if (helper != null) helperEntry(helper, inputs, helperState) else null
            helperPolicy = extra?.get("policy") ?: JNull
            val withH = withHelper(entries, extra, request.long("slot"))
            val ownActors = BattleEngine.ownActorsFromStats(withH, { e -> (e as JObj).obj("stats") }, inputs)
            val side = BattleEngine.enemyActors(stage, inputs)
            outcome = BattleEngine.simulate(ownActors, side, battleType = side.long("battle_type"), background = side.long("background"),
                seed = seed, ownName = ownName(owned.state))
        }
        val report = outcome.obj("report")
        val win = PyDocs.long(outcome.getValue("result")) == 2L
        if (!win) {
            report["reward"] = Acquisition.emptyReward()
            return Plan(jobj("stage" to stage, "result" to 0, "stars" to 0, "commit" to false, "seed" to (outcome["seed"] ?: JNull),
                "engine_version" to (outcome["engine_version"] ?: JNull), "rounds" to (outcome["rounds"] ?: JNull),
                "unresolved_stats" to unresolved, "helper_policy" to helperPolicy, "evidence_class" to "engine_policy"),
                listOf(S_REPORT to BattleReport.encode(report)))
        }
        val rng = SplitMix64(seed xor 0x5CA1AB1EL)
        val firstClear = stage !in recs
        val forcedDrops = forced?.get("drops")
        val drops = if (forcedDrops != null && forcedDrops != JNull) dropsFromJson(forcedDrops) else rollDrops(row, rng, firstClear = firstClear)
        val (frames0, reward, detail) = settleWin(owned, current, inputs, stage, row, mode, PyDocs.long(outcome.getValue("stars")),
            now = now, document = document, helper = helper, drops = drops)
        val frames = ArrayList(frames0)
        report["reward"] = reward
        frames.add(S_REPORT to BattleReport.encode(report))
        frames.addAll(flushPending(owned, detail))
        val level = role(owned, ROLE_LEVEL).toLong()
        val monsterLevel = encounterLevel(inputs, row)
        return Plan(jobj("stage" to stage, "mode" to mode, "count" to 1, "result" to 2, "stars" to outcome.getValue("stars"), "commit" to true,
            "seed" to (outcome["seed"] ?: JNull), "engine_version" to (outcome["engine_version"] ?: JNull), "rounds" to (outcome["rounds"] ?: JNull),
            "unresolved_stats" to unresolved, "helper_policy" to helperPolicy, "helper" to (helper?.participantId?.let { JInt(it) } ?: JNull),
            "near_level" to (monsterLevel != null && monsterLevel >= level - 3), "detail" to detail,
            "campaign_state_after" to document, "evidence_class" to "engine_policy_capture_settlement"), frames)
    }
}
