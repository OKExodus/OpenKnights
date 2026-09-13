package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.asArr
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.toHexString
import io.github.okexodus.openknights.protocol.WireReader
import io.github.okexodus.openknights.protocol.WireWriter
import io.github.okexodus.openknights.server.store.StateStore
import java.math.BigInteger
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.WeakHashMap

/**
 * The login parts of `goals.py` ("Mubiao", the 7-day target event): the per-character `goal_state` document seeded
 * once from the seed S3108, the days that open with the character's day index (local calendar days since creation + 1)
 * and level, the owned-state kinds re-read from the save, and the S3108 list payload.
 */
object Goals {
    const val C_CLAIM = 2657
    const val S_ROW = 3106
    const val S_LIST = 3108
    const val PROFILE = "goal_state_v1"
    const val WIRE_RUNNING = 2L
    const val WIRE_READY = 3L
    const val ROLE_LEVEL = 3L
    const val U32 = 0xFFFFFFFFL
    const val KIND_LEVEL = 1L
    const val KIND_HERO_LEVEL = 2L
    const val KIND_RUNES = 17L
    const val KIND_POWER = 22L
    const val KIND_ALL = 24L
    const val KIND_TIER = 25L
    val STATE_KINDS = setOf(KIND_LEVEL, KIND_HERO_LEVEL, KIND_RUNES, KIND_POWER, KIND_TIER)

    /** The request changes nothing: answer with these frames (or this S3108) and write no revision. */
    class Unchanged(val packets: List<Frame> = emptyList(), val payload: ByteArray? = null) : Exception("unchanged")

    private fun int(value: String?, default: Long = 0): Long = PyValues.digitInt(value, default)

    class Tables(val goals: Map<Long, JObj>, val days: Map<Long, JObj>)

    private val tableCache = WeakHashMap<DailyInputs, Tables>()

    /** {goals: {id: goal}, days: {type: day}} from mubiaoquest.csv / mubiao.csv (a catalog without them: no Goals). */
    fun tables(inputs: DailyInputs): Tables = synchronized(tableCache) {
        tableCache.getOrPut(inputs) {
            var goals = LinkedHashMap<Long, JObj>()
            var days = LinkedHashMap<Long, JObj>()
            try {
                for (f in inputs.tableRows("mubiao")) {
                    val ident = int(f.field("101"))
                    if (ident != 0L) days[ident] = jobj("type" to ident, "name_text" to int(f.field("102")), "min_level" to int(f.field("103")),
                        "day" to int(f.field("104")))
                }
                for (f in inputs.tableRows("mubiaoquest")) {
                    val ident = int(f.field("101"))
                    if (ident == 0L) continue
                    val rewards = listOf(108, 111, 114, 117).map { c -> listOf(int(f.field(c)), int(f.field(c + 1)), int(f.field(c + 2))) }
                    goals[ident] = jobj("id" to ident, "text" to int(f.field("102")), "type" to int(f.field("103")), "kind" to int(f.field("104")),
                        "target" to int(f.field("105")), "param" to int(f.field("106")), "show" to int(f.field("107")),
                        "rewards" to rewards.filter { it[0] != 0L && it[1] != 0L && it[2] != 0L }, "order" to int(f.field("120")))
                }
            } catch (e: Exception) {
                goals = LinkedHashMap(); days = LinkedHashMap()
            }
            Tables(goals, days)
        }
    }

    /** S3108 → [[id, wire, progress], ...] (strict length). */
    fun decodeRows(payload: ByteArray): JArr {
        if (payload.isEmpty()) throw PyValues.ValueError("S3108 is empty")
        val n = payload[0].toInt() and 0xFF
        if (payload.size != 1 + 9 * n) throw PyValues.ValueError("S3108 length does not match its count")
        val r = WireReader(payload).also { it.offset = 1 }
        return JArr((0 until n).mapTo(ArrayList()) { r.values("IBI") })
    }

    /** S3108 `u8 n, n × (u32 id, u8 wire state, u32 progress)` (sorted rows). */
    fun listPayload(rows: List<JValue>): ByteArray {
        val sorted = PyDocs.sorted(rows)
        if (sorted.size > 255) throw PyValues.ValueError("At most 255 goal rows (u8 count)")
        val w = WireWriter().raw(PyDocs.bytes(listOf(sorted.size.toLong())))
        for (row in sorted) w.values("IBI", row.asArr)
        return w.bytes()
    }

    fun rowPayload(row: JArr): ByteArray = WireWriter().values("IBI", row).bytes()

    private fun level(state: JObj): JValue {
        for (f in (state["role_properties"] as? JArr) ?: JArr()) {
            val field = f.asObj
            if (field["id"] == JInt(ROLE_LEVEL)) {
                val bits = field.obj("value")["bits"]
                return if (PyDocs.truthy(bits)) bits!! else JInt(0)
            }
        }
        return JInt(0)
    }

    private fun createdEpoch(current: StateStore.Current): Long? {
        val profile = PyDocs.get(current.characterProfile, "document") as? JObj ?: JObj()
        val stamp = PyDocs.get(profile, "created_at_utc")
        if (!PyDocs.truthy(stamp)) return null
        return try { Summon.isoTimestamp(PyDocs.str(stamp).replace("Z", "+00:00")) } catch (e: PyValues.ValueError) { null }
    }

    private fun dayDate(epoch: Long): LocalDate = LocalDate.parse(Shops.dayOf(epoch))

    /** 1 on the start day, +1 per local midnight since (device clock). */
    fun dayIndex(document: JObj, now: Long): Long =
        ChronoUnit.DAYS.between(LocalDate.parse(document.str("start_day")), dayDate(now)) + 1

    /**
     * (document, provenance) from the character's seed S3108, or (null, null) when it has none (Goals stay off). Fresh
     * characters start on their creation day; others on today minus the last seeded day − 1.
     */
    fun seedDocument(current: StateStore.Current, seeds: SystemSeeds.SeedFrames?, inputs: DailyInputs, now: Long): Pair<JObj?, JObj?> {
        val payload = seeds?.first(S_LIST) ?: return null to null
        val rows = decodeRows(payload)
        val table = tables(inputs)
        val created = createdEpoch(current)
        val start = if (created != null) dayDate(created) else {
            val seeded = rows.map { r ->
                val type = table.goals[PyDocs.long(r.asArr[0])]?.get("type")
                (type?.let { table.days[PyDocs.long(it)] }?.get("day") ?: JInt(1)).let { PyDocs.long(it) }
            }
            LocalDate.ofEpochDay(dayDate(now).toEpochDay() - ((seeded.maxOrNull() ?: 1L) - 1))
        }
        val provenance = seeds.provenance(S_LIST)
        return jobj("profile" to PROFILE, "rows" to PyDocs.sorted(rows), "start_day" to start.toString(),
            "level_seen" to level(current.state), "seed" to provenance) to provenance
    }

    /** (document copy, seeded) — the stored document, else the seed; (null, false) when Goals are off. */
    fun documentOf(current: StateStore.Current, seeds: SystemSeeds.SeedFrames?, inputs: DailyInputs, now: Long): Pair<JObj?, Boolean> {
        val stored = PyDocs.get(current, "goal_state")
        if (stored != null) return (stored.deepCopy() as JObj) to false
        val (document, _) = seedDocument(current, seeds, inputs, now)
        return document to (document != null)
    }

    /** Runes in rune group 0 of the formation slots. */
    private fun runesWorn(state: JObj): Long {
        var total = 0L
        for (s in (state["formation"] as? JArr) ?: JArr()) {
            for (g in (s.asObj["groups"] as? JArr) ?: JArr()) {
                val group = g.asObj
                if (group["id"] == JInt(0)) total += ((group["values"] as? JArr) ?: JArr()).size
            }
        }
        return total
    }

    /** Owned-state facts, read once and only when a state kind needs them. */
    private class Facts(val state: JObj, val inputs: DailyInputs, val power: (() -> BigInteger?)?) {
        private var ownedFacts: Quests.Facts? = null
        private var powerRead = false
        private var powerValue: BigInteger? = null

        fun owned(): Quests.Facts = ownedFacts ?: Quests.ownedFacts(state, inputs).also { ownedFacts = it }

        fun power(): BigInteger? {
            if (!powerRead && power != null) { powerValue = power.invoke(); powerRead = powerValue != null }
            return powerValue
        }
    }

    /** (met, progress) of an owned-state kind (progress null = unchanged). */
    private fun stateMet(goal: JObj, facts: Facts): Pair<Boolean, Long?> {
        val kind = goal.long("kind")
        val target = goal.long("target")
        return when (kind) {
            KIND_LEVEL -> (PyDocs.compare(level(facts.state), JInt(target)) >= 0) to null
            KIND_HERO_LEVEL -> ((facts.owned().heroLevels.maxOrNull() ?: 0L) >= target) to null
            KIND_TIER -> ((facts.owned().heroStars.maxOrNull() ?: 0L) >= target) to null
            KIND_RUNES -> { val worn = runesWorn(facts.state); (worn >= target) to minOf(worn, U32) }
            KIND_POWER -> {
                val power = facts.power()
                if (power == null || power < BigInteger.valueOf(target)) false to null
                else true to power.min(BigInteger.valueOf(U32)).toLong()
            }
            else -> false to null
        }
    }

    /**
     * Advance one document in place. Returns the ids to push, in frame order (kind-24 rows first, then the changed rows
     * ascending, then the rows of newly opened days ascending). Order inside one pass: counter events → level-up → open
     * due days → owned-state kinds → "complete all" rows. (The login passes no counter events.)
     */
    fun evaluate(document: JObj, state: JObj, inputs: DailyInputs, now: Long, power: (() -> BigInteger?)? = null): List<Long> {
        val table = tables(inputs)
        val goals = table.goals
        val days = table.days
        val rows = LinkedHashMap<Long, JArr>()
        for (r in document.arr("rows")) rows[PyDocs.long(r.asArr[0])] = r.asArr
        val changed = LinkedHashSet<Long>()
        val completed = LinkedHashSet<Long>()
        fun running(ident: Long) = rows.getValue(ident)[1] == JInt(WIRE_RUNNING)
        val existing = rows.keys.toList()
        val lvl = level(state)
        if (PyDocs.compare(lvl, document["level_seen"] ?: lvl) > 0) {
            for (ident in existing) {
                val goal = goals[ident]
                if (goal != null && goal.long("kind") == KIND_LEVEL && running(ident)) {
                    rows.getValue(ident)[2] = JInt(minOf(PyDocs.int(rows.getValue(ident)[2]) + BigInteger.ONE, BigInteger.valueOf(U32)))
                    changed.add(ident)
                }
            }
        }
        document["level_seen"] = lvl
        val opened = ArrayList<Long>()
        val today = dayIndex(document, now)
        val servedTypes = rows.keys.mapNotNull { goals[it]?.long("type") }.toSet()
        for ((kindType, day) in days.entries.sortedBy { it.key }) {
            if (kindType in servedTypes || day.long("day") > today || PyDocs.compare(lvl, JInt(day.long("min_level"))) < 0) continue
            for (ident in goals.entries.filter { it.value.long("type") == kindType }.map { it.key }.sorted()) {
                rows[ident] = jarr(ident, WIRE_RUNNING, 0)
                opened.add(ident)
            }
        }
        val facts = Facts(state, inputs, power)
        for (ident in rows.keys.sorted()) {
            val goal = goals[ident]
            if (goal == null || goal.long("kind") !in STATE_KINDS || !running(ident)) continue
            val (met, progress) = stateMet(goal, facts)
            if (progress != null && JInt(progress) != rows.getValue(ident)[2]) {
                rows.getValue(ident)[2] = JInt(progress)
                changed.add(ident)
            }
            if (met) {
                rows.getValue(ident)[1] = JInt(WIRE_READY)
                changed.add(ident)
                if (ident !in opened) completed.add(ident)
            }
        }
        val first = ArrayList<Long>()
        for (ident in rows.keys.sorted()) {
            val goal = goals[ident]
            if (goal == null || goal.long("kind") != KIND_ALL || !running(ident)) continue
            val others = rows.keys.filter { it != ident && goals[it]?.get("type") == goal["type"] }
            val counted = others.count { it in completed }
            if (counted != 0) rows.getValue(ident)[2] = JInt(minOf(PyDocs.int(rows.getValue(ident)[2]) + BigInteger.valueOf(counted.toLong()), BigInteger.valueOf(U32)))
            if (others.isNotEmpty() && others.all { PyDocs.compare(rows.getValue(it)[1], JInt(WIRE_READY)) >= 0 }) rows.getValue(ident)[1] = JInt(WIRE_READY)
            if (counted != 0 || rows.getValue(ident)[1] != JInt(WIRE_RUNNING)) {
                if (ident !in opened) {
                    first.add(ident)
                    changed.remove(ident)
                }
            }
        }
        document["rows"] = PyDocs.sorted(rows.values)
        return first + changed.filter { it !in opened }.sorted() + opened.sorted()
    }

    /**
     * Login refresh (`goal_refresh`): seed once, open due days, re-read the owned-state kinds. Returns the plan with the
     * S3108 payload; throws [Unchanged] (payload = S3108) when nothing changes, [Unchanged] (payload null) when Goals are
     * off for this character. `power(current)`: the character's Power, read only when a Power row needs it.
     */
    fun planLogin(owned: Owned, current: StateStore.Current, seeds: SystemSeeds.SeedFrames?, inputs: DailyInputs, now: Long,
                  power: ((StateStore.Current) -> BigInteger?)? = null): Plan {
        val (document, seeded) = documentOf(current, seeds, inputs, now)
        if (document == null) throw Unchanged(payload = null)
        val before = PyDocs.sortedDump(document)
        val ids = evaluate(document, owned.state, inputs, now, power?.let { p -> { p(current) } })
        val payload = listPayload(document.arr("rows"))
        if (!seeded && PyDocs.sortedDump(document) == before) throw Unchanged(payload = payload)
        return Plan(jobj("goal_state_after" to document, "seeded" to seeded, "changed_rows" to ids, "s3108_hex" to payload.toHexString(),
            "day_index" to dayIndex(document, now), "evidence_class" to "native_use_capture_rules_policy_days"), emptyList())
    }

    fun listFrame(document: JObj): Frame = S_LIST to listPayload(document.arr("rows"))
}
