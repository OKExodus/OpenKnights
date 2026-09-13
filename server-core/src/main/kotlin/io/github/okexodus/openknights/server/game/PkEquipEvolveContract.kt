package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.gamedata.GameTable
import io.github.okexodus.openknights.gamedata.GameTables
import io.github.okexodus.openknights.server.game.HeroDictionaryProgression.n

/**
 * The gear / jewelry evolve catalog inputs of `tools/pk_equip_evolve_contract.py`: the `equipjinhua` /
 * `jewelry_jinhua` rows of an item's group, the row of its grade and the next one, the potential sums and the
 * property of the super factor. The capture verifier and `predict` of that module are tools, not ported.
 */
object PkEquipEvolveContract {
    val OBSERVED_GEAR_ROWS = setOf(801L)
    val OBSERVED_JEWEL_ROWS = setOf(715L)
    const val CLIENT_JEWEL_FOURTH_ITEM = 10729L

    fun byte(row: GameTable.Row, field: String): Long = n(row, field) and 0xFF

    /** `_row(row, jewel=...)`: one evolve row as the planners read it. */
    fun row(row: GameTable.Row, jewel: Boolean): JObj {
        val materials = listOf(jarr(n(row, "105"), n(row, "106")), jarr(n(row, "107"), n(row, "108")), jarr(n(row, "109"), n(row, "110")))
        val out = jobj("ref" to CatalogShapes.ref(row), "key_101" to n(row, "101"), "category_102" to byte(row, "102"),
            "grade_103" to byte(row, "103"), "group_104" to n(row, "104"),
            "item_level_112" to n(row, "112"), "cap_113" to n(row, "113"), "potential_114" to n(row, "114"),
            "evolvable_115" to byte(row, "115"), "role_level_116" to n(row, "116"), "upstar_117" to byte(row, "117"))
        if (jewel) {
            val fourth = jarr(n(row, "200"), n(row, "111"))
            out["materials"] = JArr((materials + listOf(fourth)).toMutableList<io.github.okexodus.openknights.exact.JValue>())
            out["gold_111"] = JInt(0)
            out["fourth_item_200"] = fourth[0]
            out["fourth_count_111"] = fourth[1]
        } else {
            out["materials"] = JArr(materials.toMutableList())
            out["gold_111"] = JInt(n(row, "111"))
        }
        return out
    }

    fun groupRows(tables: GameTables, table: String, category: Long, group: Long, jewel: Boolean): List<JObj> =
        tables.table(table).rows.filter { byte(it, "102") == category && n(it, "104") == group }.map { row(it, jewel) }

    fun select(rows: List<JObj>, grade: Long): JObj? {
        val matches = rows.filter { it.long("grade_103") == grade }
        if (matches.size > 1) throw PyValues.ValueError("Grade $grade matches several evolve rows")
        return matches.firstOrNull()
    }

    fun property(tables: GameTables, pid: Long): Long {
        val rows = tables.lookup("property", pid.toString())
        if (rows.size != 1) throw PyValues.ValueError("Property $pid must be unique")
        return n(rows[0], "102")
    }

    private fun potentialSum(rows: List<JObj>, below: Long): Long = rows.filter { it.long("grade_103") < below }.sumOf { it.long("potential_114") }

    fun gearEvolveInputs(tables: GameTables, template: Long, grade: Long): JObj {
        val rows = tables.lookup("equip", template.toString())
        if (rows.size != 1) throw PyValues.ValueError("Equipment template $template must resolve to exactly one row")
        val equip = rows[0]
        val category = n(equip, "106")
        val group = n(equip, "306")
        val groupRows = groupRows(tables, "equipjinhua", category and 0xFF, group, jewel = false)
        val row = select(groupRows, grade)
        val nextRow = select(groupRows, grade + 1)
        return jobj("class" to "gear", "template" to template, "grade" to grade, "equip_row" to CatalogShapes.ref(equip),
            "category_106" to category, "group_306" to group,
            "base_108" to n(equip, "108"), "growth_109" to n(equip, "109"),
            "row" to row, "next_row" to nextRow,
            "potential_before" to potentialSum(groupRows, grade),
            "potential_after" to potentialSum(groupRows, grade + 1),
            "property_912" to property(tables, 912),
            "observed_row" to (row != null && row.long("key_101") in OBSERVED_GEAR_ROWS))
    }

    fun jewelEvolveInputs(tables: GameTables, template: Long, grade: Long): JObj {
        val rows = tables.lookup("jewelry", template.toString())
        if (rows.size != 1) throw PyValues.ValueError("Jewelry template $template must resolve to exactly one row")
        val jewel = rows[0]
        val category = n(jewel, "106") and 0xFF
        val group = n(jewel, "121")
        val baseMin = n(jewel, "108")
        val baseMax = n(jewel, "109")
        val ratioMin = n(jewel, "110")
        val ratioMax = n(jewel, "111")
        // GetJewelPropertyValue draws rand() unless max == min or max == min + 1.
        if ((baseMax != baseMin && baseMax != baseMin + 1) || (ratioMax != ratioMin && ratioMax != ratioMin + 1)) {
            throw PyValues.ValueError("Jewelry $template has a random base/ratio range; unsupported")
        }
        val groupRows = groupRows(tables, "jewelry_jinhua", category, group, jewel = true)
        val row = select(groupRows, grade)
        val nextRow = select(groupRows, grade + 1)
        if (row != null && row.long("evolvable_115") != 0L && row.long("fourth_count_111") > 0 &&
            row.long("fourth_item_200") != CLIENT_JEWEL_FOURTH_ITEM) {
            throw PyValues.ValueError("Jewelry evolve row's fourth item differs from the client's hard-coded 10729")
        }
        return jobj("class" to "jewelry", "template" to template, "grade" to grade, "jewelry_row" to CatalogShapes.ref(jewel),
            "category_106" to category, "group_121" to group,
            "base_108" to baseMin, "ratio_110" to ratioMin,
            "row" to row, "next_row" to nextRow,
            "potential_before" to potentialSum(groupRows, grade),
            "potential_after" to potentialSum(groupRows, grade + 1),
            "property_956" to property(tables, 956),
            "observed_row" to (row != null && row.long("key_101") in OBSERVED_JEWEL_ROWS))
    }
}

/** `EquipEvolveInputs`: the lazy, cached evolve inputs the server reads (one cache per instance). */
class EquipEvolveInputs(val tables: GameTables) {
    private val cache = HashMap<Triple<String, Long, Long>, JObj>()

    fun gear(template: Long, grade: Long): JObj =
        cache.getOrPut(Triple("gear", template, grade)) { PkEquipEvolveContract.gearEvolveInputs(tables, template, grade) }

    fun jewelry(template: Long, grade: Long): JObj =
        cache.getOrPut(Triple("jewelry", template, grade)) { PkEquipEvolveContract.jewelEvolveInputs(tables, template, grade) }
}
