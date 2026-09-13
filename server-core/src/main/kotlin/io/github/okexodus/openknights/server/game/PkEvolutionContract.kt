package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.gamedata.GameTable
import io.github.okexodus.openknights.gamedata.GameTables
import io.github.okexodus.openknights.server.game.HeroDictionaryProgression.byte
import io.github.okexodus.openknights.server.game.HeroDictionaryProgression.n
import io.github.okexodus.openknights.server.game.HeroDictionaryProgression.unpackHeroId

/**
 * The hero evolution catalog inputs of `tools/pk_evolution_contract.py`: what both evolution planners need for one
 * owned template (`evolution_inputs`), the S2240 leader of a hero set (`leader_info`) and the super-class digit a
 * leader template should carry (`leader_digit`). The capture verifier of that module is a tool, not ported.
 */
object PkEvolutionContract {
    val RAW_511_514 = listOf("511", "512", "513", "514")

    fun potentialSum(tables: GameTables, table: String, category: Long, group: Long, newGrade: Long, leader: Boolean): Long {
        var total = 0L
        for (row in tables.table(table).rows) {
            if (byte(row, "102") != category) continue
            if (!leader && n(row, "104") != group) continue
            if (byte(row, "103") < newGrade) total += n(row, "503")
        }
        return total
    }

    /**
     * `_leader_row`: the `jinhuazhujue` row of a leader's CURRENT packed grade (its S2240 progress key). A grade listed
     * twice is told apart by column 104 (the super-class predicate) = the template's packed hundreds digit.
     */
    fun leaderRow(tables: GameTables, category: Long, grade: Long, superDigit: Long): GameTable.Row? {
        var rows = tables.table("jinhuazhujue").rows.filter { byte(it, "102") == category && byte(it, "103") == grade }
        if (rows.size > 1) rows = rows.filter { n(it, "104") == superDigit }
        if (rows.size > 1) throw PyValues.ValueError("Non-unique jinhuazhujue row for grade $grade")
        return rows.firstOrNull()
    }

    /** `_leader_successor`: the next row of the key-ordered leader map (LeaderHeroSystem::getNextConfigByProgress). */
    fun leaderSuccessor(tables: GameTables, row: GameTable.Row): GameTable.Row? {
        val ordered = tables.table("jinhuazhujue").rows.sortedBy { n(it, "101") }
        val keys = ordered.map { n(it, "101") }
        val at = keys.indexOf(n(row, "101"))
        if (at < 0) throw PyValues.ValueError("${n(row, "101")} is not in list")
        return if (at + 1 < ordered.size) ordered[at + 1] else null
    }

    /** `_cost_row`: the config row whose 103 == the current grade (materials, Gold, gates). */
    fun costRow(tables: GameTables, table: String, category: Long, group: Long, currentGrade: Long, leader: Boolean, superDigit: Long = 0): JObj? {
        val matches = ArrayList<GameTable.Row>()
        if (leader) {
            leaderRow(tables, category, currentGrade, superDigit)?.let { matches.add(it) }
        }
        for (row in (if (leader) emptyList() else tables.table(table).rows)) {
            if (byte(row, "102") != category || byte(row, "103") != currentGrade) continue
            if (!leader && n(row, "104") != group) continue
            matches.add(row)
        }
        if (matches.isEmpty()) return null
        if (matches.size != 1) throw PyValues.ValueError("Non-unique $table cost row for grade $currentGrade")
        val row = matches[0]
        val materials = JArr()
        for ((itemField, qtyField) in listOf("201" to "202", "203" to "204")) {
            val item = n(row, itemField)
            val qty = n(row, qtyField)
            if (item != 0L && qty != 0L) materials.add(jarr(item, qty))
        }
        return jobj("row_ref" to CatalogShapes.ref(row), "gold" to n(row, "401"), "materials" to materials,
            "third_slot_quantity" to n(row, "304"),
            "third_slot_items" to listOf("301", "302", "303").map { n(row, it) },
            "hero_level_requirement_501" to n(row, "501"),
            "role_level_requirement" to n(row, "602"),
            "leader_field_504" to (if (leader) n(row, "504") else null),
            "advance_type_604" to n(row, "604"),
            "cap_502" to n(row, "502"), "potential_503" to n(row, "503"))
    }

    fun gradeRow(tables: GameTables, table: String, category: Long, group: Long, grade: Long, leader: Boolean): GameTable.Row? =
        tables.table(table).rows.firstOrNull { byte(it, "102") == category && byte(it, "103") == grade && (leader || n(it, "104") == group) }

    /** `_next_progress`: leader field B (S2240), the config key (101) of the row for the new grade. */
    fun nextProgress(tables: GameTables, table: String, category: Long, newGrade: Long, leader: Boolean): Long? {
        if (!leader) return null
        return tables.table(table).rows.firstOrNull { byte(it, "102") == category && byte(it, "103") == newGrade }?.let { n(it, "101") }
    }

    /** `evolution_inputs(catalog, template)`: the catalog values both evolution planners need for one owned template. */
    fun evolutionInputs(tables: GameTables, template: Long): JObj {
        val dims = unpackHeroId(template)
        val rows = tables.lookup("hero", dims.baseId.toString())
        if (rows.size != 1) throw PyValues.ValueError("Hero base ${dims.baseId} must resolve to exactly one row")
        val row = rows[0]
        val leader = byte(row, "143") != 0L
        val table = if (leader) "jinhuazhujue" else "jinhua"
        val category = byte(row, "104")
        val group = n(row, "132")
        val newGrade = dims.grade + 1
        val heroClass = byte(row, "103")
        val costRow = costRow(tables, table, category, group, dims.grade, leader, dims.hundredsDigit)
        if (costRow != null) {
            val items = costRow.arr("third_slot_items").map { it.long }
            costRow["third_slot_item"] = JInt(if (heroClass in 1..3 && items[(heroClass - 1).toInt()] != 0L) items[(heroClass - 1).toInt()] else 0L)
        }
        var nextRow = gradeRow(tables, table, category, group, newGrade, leader)
        // SUPER-EVOLUTION (leader): the row after the current one repeats the grade with the super-class predicate
        // 104 = 1; the packed hundreds digit rises, the grade stays.
        var superUp: JObj? = null
        if (leader && costRow != null) {
            val current = leaderRow(tables, category, dims.grade, dims.hundredsDigit)!!
            val after = leaderSuccessor(tables, current)
            if (after != null && byte(after, "102") == category && byte(after, "103") == dims.grade && n(after, "104") != dims.hundredsDigit) {
                val templateAfter = dims.baseId * 1000 + n(after, "104") * 100 + dims.grade
                superUp = jobj("template" to templateAfter, "new_grade" to dims.grade,
                    "leader_progress_key" to n(after, "101"), "marker_604" to n(current, "604"),
                    "potential_rate" to potentialSum(tables, table, category, group, dims.grade, leader),
                    "stat_inputs" to PkFortifyContract.heroStatInputs(tables, templateAfter),
                    "cap_502" to n(after, "502"), "row_ref" to CatalogShapes.ref(after))
                nextRow = after
            }
        }
        // Star-up at the last configured tier of a star: the preview template from hero field 144 (next-star base);
        // a leader's new digit is the super-class predicate of the row that follows in the key-ordered leader map.
        var starUp: JObj? = null
        val nextBase = n(row, "144")
        if (costRow != null && nextRow == null && nextBase != 0L) {
            val newRows = tables.lookup("hero", nextBase.toString())
            if (newRows.size == 1) {
                val newRow = newRows[0]
                var digit = dims.hundredsDigit
                if (leader) {
                    val successor = leaderSuccessor(tables, leaderRow(tables, category, dims.grade, digit)!!)
                    if (successor != null && byte(successor, "102") == byte(newRow, "104")) digit = n(successor, "104")
                }
                val newTemplate = nextBase * 1000 + digit * 100 + 1
                val newCategory = byte(newRow, "104")
                starUp = jobj("next_base" to nextBase, "template" to newTemplate, "new_grade" to 1,
                    "raw_511_514" to RAW_511_514.map { n(newRow, it) },
                    "hero_class_103" to byte(newRow, "103"), "category" to newCategory,
                    "is_leader" to (byte(newRow, "143") != 0L),
                    "stat_inputs" to PkFortifyContract.heroStatInputs(tables, newTemplate),
                    "leader_progress_key" to nextProgress(tables, table, newCategory, 1, leader),
                    "cap_502" to n(gradeRow(tables, table, newCategory, n(newRow, "132"), 1, leader) ?: row, "502"),
                    "marker_604" to n(row, "604"), "hero_row_ref" to CatalogShapes.ref(newRow))
            }
        }
        val out = jobj("star_up" to starUp, "super_up" to superUp)
        out.putAll(PkFortifyContract.heroStatInputs(tables, template))
        val tail = jobj(
            "template" to template, "base_id" to dims.baseId, "grade" to dims.grade, "is_leader" to leader,
            "hero_class_103" to heroClass,
            "raw_511_514" to RAW_511_514.map { n(row, it) },
            "potential_rate" to potentialSum(tables, table, category, group, newGrade, leader),
            "current_grade_row" to costRow,
            "next_grade_row_exists" to (nextRow != null),
            "next_grade_cap_502" to nextRow?.let { n(it, "502") },
            "leader_next_progress" to nextProgress(tables, table, category, newGrade, leader),
            "hero_row_ref" to CatalogShapes.ref(row), "config_table" to table,
            "sources" to jobj("hero" to CatalogShapes.source(tables, "hero")).also { it[table] = CatalogShapes.source(tables, table) })
        out.putAll(tail)
        return out
    }

    /**
     * `leader_info(catalog, heroes)`: the owned leader for the S2240 (uid, config key) startup reply: the one owned
     * hero of a leader-class base (hero 143 != 0) and the `jinhuazhujue` key (101) of the row of its CURRENT packed grade.
     * `heroes` maps owned uid → field list (save order). Null when not exactly one leader-class hero is owned, or its
     * row has no key.
     */
    fun leaderInfo(tables: GameTables, heroes: Map<Long, JArr>): JObj? {
        val leaders = ArrayList<JObj>()
        for ((uid, fields) in heroes) {
            val template = HeroFortify.heroUidOf(fields, 1)
            val dims = unpackHeroId(template)
            val rows = tables.lookup("hero", dims.baseId.toString())
            if (rows.size != 1 || byte(rows[0], "143") == 0L) continue
            val category = byte(rows[0], "104")
            val current = leaderRow(tables, category, dims.grade, dims.hundredsDigit)
            val key = current?.let { n(it, "101") }
            leaders.add(jobj("uid" to uid, "template" to template, "grade" to dims.grade, "progress_key" to key))
        }
        if (leaders.size != 1 || leaders[0]["progress_key"] == io.github.okexodus.openknights.exact.JNull) return null
        return leaders[0]
    }

    /**
     * `leader_digit(catalog, template, super_reached)`: the super-class digit a leader template should carry at its
     * (star, grade): the one row's 104 when the grade is listed once, else 1 only when the super step to this very
     * template was taken. Null when the template is not a leader or the grade has no row.
     */
    fun leaderDigit(tables: GameTables, template: Long, superReached: Boolean): Long? {
        val dims = unpackHeroId(template)
        val rows = tables.lookup("hero", dims.baseId.toString())
        if (rows.size != 1 || byte(rows[0], "143") == 0L) return null
        val category = byte(rows[0], "104")
        val listed = tables.table("jinhuazhujue").rows.filter { byte(it, "102") == category && byte(it, "103") == dims.grade }
        if (listed.isEmpty()) return null
        if (listed.size == 1) return n(listed[0], "104")
        return if (superReached) 1 else 0
    }
}

/**
 * `EvolutionInputs`: the lazy catalog loader the server holds (the reference keeps two, one for the evolutions and the
 * leader repair, one for S2240 and Power; their results are identical). [invoke] caches per template.
 */
class EvolutionInputs(val tables: GameTables) {
    private val cache = HashMap<Long, JObj>()

    @Synchronized
    operator fun invoke(template: Long): JObj = cache.getOrPut(template) { PkEvolutionContract.evolutionInputs(tables, template) }

    fun leaderInfo(heroes: Map<Long, JArr>): JObj? = PkEvolutionContract.leaderInfo(tables, heroes)

    fun leaderDigit(template: Long, superReached: Boolean): Long? = PkEvolutionContract.leaderDigit(tables, template, superReached)
}
