package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.gamedata.GameTables
import io.github.okexodus.openknights.server.game.HeroDictionaryProgression.n

/**
 * The catalog inputs of equipment / jewelry / runes / main formation (`tools/pk_equip_formation_contract.py`
 * `FormationInputs`): lazy read-only lookups, cached per key. The capture replay verifier of that module (`verify`,
 * `view_from_s18`, `replay_retained`) reads private evidence and is not ported.
 */
class FormationInputs(val tables: GameTables) {
    /** One baoshi row: 103 type, 105 next level id, 106 combine count. */
    data class GemRow(val type: Long, val next: Long, val cost: Long)

    private var gems: LinkedHashMap<Long, GemRow>? = null
    private val equip = HashMap<Long, Long?>()
    private val jewel = HashMap<Long, Long?>()
    private val leader = HashMap<Long, Boolean>()

    /** `gem_rows()`: baoshi {101 id: row} in table order; a later duplicate id overwrites the row. */
    @Synchronized
    fun gemRows(): Map<Long, GemRow> = gems ?: LinkedHashMap<Long, GemRow>().also { rows ->
        for (row in tables.table("baoshi").rows) rows[n(row, "101")] = GemRow(n(row, "103"), n(row, "105"), n(row, "106"))
        gems = rows
    }

    /** `gem_types()`: {id: type}. */
    fun gemTypes(): Map<Long, Long> = LinkedHashMap<Long, Long>().also { m -> gemRows().forEach { (id, row) -> m[id] = row.type } }

    /** `equip_position(config)`: equip field 104 - 1 for exactly one row with 104 >= 1, else null. */
    @Synchronized
    fun equipPosition(config: Long): Long? {
        if (!equip.containsKey(config)) equip[config] = position(tables.lookup("equip", config.toString()))
        return equip[config]
    }

    /** `jewel_position(config)`: jewelry field 104 - 1, the same rule. */
    @Synchronized
    fun jewelPosition(config: Long): Long? {
        if (!jewel.containsKey(config)) jewel[config] = position(tables.lookup("jewelry", config.toString()))
        return jewel[config]
    }

    private fun position(rows: List<io.github.okexodus.openknights.gamedata.GameTable.Row>): Long? =
        if (rows.size == 1 && n(rows[0], "104") >= 1) n(rows[0], "104") - 1 else null

    /** `is_leader(template)`: the hero row of `template // 1000` has field 143 != 0; a falsy template is not. */
    @Synchronized
    fun isLeader(template: JValue?): Boolean {
        if (!Py.truthy(template)) return false
        val base = (template as JInt).value.divide(java.math.BigInteger.valueOf(1000)).toLong()
        return leader.getOrPut(base) {
            val rows = tables.lookup("hero", base.toString())
            rows.size == 1 && n(rows[0], "143") != 0L
        }
    }
}
