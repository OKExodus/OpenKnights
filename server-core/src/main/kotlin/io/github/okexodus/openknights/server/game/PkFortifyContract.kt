package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.gamedata.GameTables
import io.github.okexodus.openknights.server.game.HeroDictionaryProgression.nativeInt
import io.github.okexodus.openknights.server.game.HeroDictionaryProgression.unpackHeroId

/**
 * The owned-hero stat model's catalog inputs of `tools/pk_fortify_contract.py` (`hero_stat_inputs`). The fortify
 * planners' own inputs of that module (hero / equipment template inputs, the capture verifier) belong to the fortify
 * group and are not ported here.
 */
object PkFortifyContract {
    /**
     * `hero_stat_inputs(catalog, template)`: at the hero's CURRENT packed grade, the raw growth 511-514, the potential
     * sum (field 503 over the earlier grades, the selector evolution uses), property 533 and the herojuexing awaken slots
     * joined to herojuexingskill (kind 301 / permille 302, secondary 303 / 304). Missing or ambiguous rows are reported,
     * never repaired.
     */
    fun heroStatInputs(tables: GameTables, template: Long): JObj {
        val dims = unpackHeroId(template)
        val rows = tables.lookup("hero", dims.baseId.toString())
        if (rows.size != 1) throw PyValues.ValueError("Hero base ${dims.baseId} must resolve to exactly one row")
        val row = rows[0]
        val raw = listOf("511", "512", "513", "514").map { nativeInt(row.field(it) ?: "") }
        val potential = HeroDictionaryProgression.potentialRows(tables, row, dims.grade)
            .sumOf { nativeInt(it.field("503") ?: "") } and 0xFFFFFFFFL
        val p533 = tables.lookup("property", "533")
        val property533: Long? = if (p533.size == 1) nativeInt(p533[0].field("102") ?: "") else null
        val slots = JArr()
        val awakenRows = tables.lookup("herojuexing", dims.baseId.toString())
        if (awakenRows.size == 1) {
            val fields = awakenRows[0]
            for (index in 0 until 16) {
                val valueRaw = fields.field((201 + 2 * index).toString()) ?: ""
                val typeRaw = fields.field((202 + 2 * index).toString()) ?: ""
                if (valueRaw == "" && typeRaw == "") continue
                val entry = jobj("slot" to index + 1, "value" to nativeInt(valueRaw), "type" to nativeInt(typeRaw))
                if ((entry["type"] as JInt).value.toLong() == 4L) {
                    val skill = tables.lookup("herojuexingskill", valueRaw)
                    if (skill.size == 1) {
                        val sf = skill[0]
                        entry["kind"] = JInt(nativeInt(sf.field("301") ?: ""))
                        entry["permille"] = JInt(nativeInt(sf.field("302") ?: ""))
                        entry["kind2"] = JInt(nativeInt(sf.field("303") ?: ""))
                        entry["permille2"] = JInt(nativeInt(sf.field("304") ?: ""))
                    } else {
                        entry["unresolved_skill_rows"] = JInt(skill.size)
                    }
                }
                slots.add(entry)
            }
        }
        return jobj("raw_511_514" to raw, "current_potential_rate" to potential, "property_533" to property533,
            "hundreds_digit" to dims.hundredsDigit, "awaken_slots" to slots, "awaken_rows" to awakenRows.size)
    }
}
