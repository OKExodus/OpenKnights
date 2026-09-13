package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JNull
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.asArr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.server.store.StateStore

/**
 * The login part of `alt_team.py` (the unlock / set requests belong to the alternate-team port): the S3745 of an
 * in-game-created character — its `alt_team` document's slots whose hero is still owned, and the open position count
 * (the stored count raised by the leading auto-open rows of fujiangkaiqi.csv the level has reached).
 */
object AltTeam {
    const val S_INFO = 3745
    const val PROFILE = "alt_team_v1"
    const val ROLE_LEVEL = 3L

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
        val d = if (PyDocs.truthy(doc)) doc as JObj else JObj()
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
}
