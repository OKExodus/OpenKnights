package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JNull
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.asArr
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.protocol.WireReader
import io.github.okexodus.openknights.protocol.WireWriter
import io.github.okexodus.openknights.server.store.StateStore

/**
 * The alternate team ("Alt Hero" slots) of in-game-created characters (`alt_team.py`). At login the S3745: the
 * `alt_team` document's slots whose hero is still owned and the open position count (the stored count raised by the
 * leading auto-open rows of fujiangkaiqi.csv the level has reached). C3777 opens the next position (its level, its
 * Gold / Diamond cost → [S128], S3746 u8 new max); C3779 places an owned hero (u32 uid, u32 position → S40, [S38 the
 * replaced hero], S3748 u8 position + u32 uid). Both run as acquisition transactions; the Diamond-spending side frames
 * of the capture are not generated.
 */
object AltTeam {
    const val C_UNLOCK = 3777
    const val C_SET = 3779
    const val S_INFO = 3745
    const val S_MAX_OPEN = 3746
    const val S_SET = 3748
    const val S_BENCH_REMOVE = 40
    const val S_BENCH_ADD = 38
    const val PROFILE = "alt_team_v1"
    val CURRENCY = mapOf(90001L to Acquisition.GOLD, 90003L to Acquisition.DIAMOND)
    const val ROLE_LEVEL = 3L
    const val ERROR = 102

    private fun n(value: String?): Long = PyValues.digitInt(value, 0)

    /** {position 1-based: {level, item, amount, auto}} of fujiangkaiqi.csv. */
    fun openRows(inputs: DailyInputs): Map<Long, JObj> {
        val rows = LinkedHashMap<Long, JObj>()
        for (f in inputs.tableRows("fujiangkaiqi")) {
            val position = n(f.field("102"))
            if (position != 0L) rows[position] = jobj("level" to n(f.field("103")), "item" to n(f.field("104")), "amount" to n(f.field("105")),
                "auto" to n(f.field("106")))
        }
        return rows
    }

    private fun level(state: JObj): JValue = PyDocs.role(state, ROLE_LEVEL, JInt(0)) ?: JNull

    fun documentOf(current: StateStore.Current): JObj {
        val doc = PyDocs.get(current, "alt_team")
        val d = if (Py.truthy(doc)) doc as JObj else JObj()
        return jobj("profile" to PROFILE, "max_open" to PyDocs.int(d["max_open"] ?: JInt(0)),
            "slots" to JArr(((d["slots"] ?: JArr()) as JArr).mapTo(ArrayList()) { JArr(it.asArr.toMutableList()) }))
    }

    /** The stored count, raised by the leading auto-open rows (106 = 1) the player's level has reached. */
    fun maxOpen(document: JObj, state: JObj, rows: Map<Long, JObj>): Long {
        var count = document.long("max_open")
        val lvl = level(state)
        for (position in rows.keys.sorted()) {
            val row = rows.getValue(position)
            if (position == count + 1 && row.long("auto") != 0L && PyDocs.compare(lvl, JInt(row.long("level"))) >= 0) count = position
        }
        return count
    }

    /** [(position 0-based, uid)] of the slots whose hero is still owned, by position. */
    fun liveSlots(document: JObj, state: JObj): List<Pair<JValue, JValue>> {
        val heroes = SecondaryTeam.ownedHeroes(state)
        return document.arr("slots").map { it.asArr }.map {
            if (it.size != 2) throw PyValues.ValueError("not enough values to unpack")
            it[0] to it[1]
        }.filter { (_, u) -> u is JInt && u.value.bitLength() < 64 && u.value.toLong() in heroes }
            .sortedWith { a, b -> val c = PyDocs.compare(a.first, b.first); if (c != 0) c else PyDocs.compare(a.second, b.second) }
    }

    /** S3745 `u8 n, n × (u8 position, hero fields), u8 max open positions`. */
    fun infoPayload(current: StateStore.Current, rows: Map<Long, JObj>): ByteArray {
        val state = current.state
        val document = documentOf(current)
        val heroes = SecondaryTeam.ownedHeroes(state)
        val entries = liveSlots(document, state).map { (p, u) -> PyDocs.long(p) to heroes.getValue(PyDocs.long(u)) }
        return SecondaryTeam.encodeSecondaryTeam(entries, maxOpen(document, state, rows))
    }

    /** C3777: open the next position — its level, its cost (Gold / Diamonds) → S128, S3746. */
    fun planUnlock(payload: ByteArray, owned: Owned, current: StateStore.Current, inputs: DailyInputs): Plan {
        if (payload.isNotEmpty()) throw Acquisition.Rejected("C3777 carries no body", ERROR)
        val rows = openRows(inputs)
        val document = documentOf(current)
        val count = maxOpen(document, owned.state, rows)
        val row = rows[count + 1] ?: throw Acquisition.Rejected("Every alternate position is already open", ERROR)
        if (PyDocs.compare(level(owned.state), JInt(row.long("level"))) < 0) throw Acquisition.Rejected("Player level below the position's requirement", ERROR)
        val frames = ArrayList<Frame>()
        if (row.long("item") != 0L && row.long("amount") != 0L) {
            val field = CURRENCY[row.long("item")] ?: throw Acquisition.Rejected("Unknown alternate position cost item ${row.long("item")}", ERROR)
            frames.add(owned.roleAdd(field, -row.long("amount")))
        }
        document["max_open"] = JInt(count + 1)
        return Plan(jobj("position" to count + 1, "cost_item" to row["item"], "cost" to row["amount"], "alt_team_after" to document,
            "evidence_class" to "native_use_table_capture_order_policy"),
            frames + listOf(S_MAX_OPEN to PyDocs.bytes(listOf(count + 1))))
    }

    /** C3779: u32 uid, u32 position (uid nonzero, position at most 254). */
    fun decodeSet(payload: ByteArray): Pair<Long, Long> {
        if (payload.size != 8) throw Acquisition.Rejected("C3779 carries u32 uid, u32 position", ERROR)
        val r = WireReader(payload)
        val uid = r.u32()
        val position = r.u32()
        if (uid == 0L || position > 254) throw Acquisition.Rejected("C3779 uid / position out of range", ERROR)
        return uid to position
    }

    /** C3779: place an owned hero in an open position (replacing its hero) → S40, [S38], S3748. */
    fun planSet(payload: ByteArray, owned: Owned, current: StateStore.Current, inputs: DailyInputs, rules: SecondaryTeam.NativeLineupRules? = null): Plan {
        val (uid, position) = decodeSet(payload)
        val rows = openRows(inputs)
        val document = documentOf(current)
        val state = owned.state
        if (position + 1 > maxOpen(document, state, rows)) throw Acquisition.Rejected("The alternate position is not open", ERROR)
        val heroes = SecondaryTeam.ownedHeroes(state)
        if (uid !in heroes) throw Acquisition.Rejected("Hero is not owned", ERROR)
        val slots = liveSlots(document, state)
        if (slots.any { (p, u) -> u == JInt(uid) && p != JInt(position) }) throw Acquisition.Rejected("Hero already holds another alternate position", ERROR)
        if ((state["formation"] as? JArr ?: JArr()).any { (it as JObj)["hero_uid"] == JInt(uid) }) throw Acquisition.Rejected("Hero is in the main lineup", ERROR)
        if (rules != null) {
            val references = slots.map { (p, u) -> jobj("position" to p, "hero_uid" to u) }
            try {
                rules.check(state, references, heroes.getValue(uid), position)      // CheckHeroLineup type 2 (70600 / 70601)
            } catch (e: IllegalArgumentException) {
                throw Acquisition.Rejected(e.message ?: "", ERROR)
            }
        }
        val old = slots.firstOrNull { (p, _) -> p == JInt(position) }?.second
        val newSlots = slots.filter { (p, _) -> p != JInt(position) }.map { (p, u) -> jarr(p, u) } + listOf(jarr(position, uid))
        document["slots"] = PyDocs.sorted(newSlots)
        val stored = document.long("max_open")
        val computed = maxOpen(document, state, rows)
        document["max_open"] = JInt(if (computed > stored) computed else stored)
        val packets = ArrayList<Frame>()
        packets.add(S_BENCH_REMOVE to WireWriter().u8(1).u32(uid).bytes())
        if (old != null && old != JInt(uid)) packets.add(S_BENCH_ADD to WireWriter().u8(1).number('I', old).bytes())
        packets.add(S_SET to WireWriter().u8(position.toInt()).u32(uid).bytes())
        return Plan(jobj("position" to position, "hero_uid" to uid, "previous_uid" to old, "alt_team_after" to document,
            "evidence_class" to "native_use_capture_order_policy"), packets)
    }
}
