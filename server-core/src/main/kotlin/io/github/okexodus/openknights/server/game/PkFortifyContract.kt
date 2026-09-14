package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.gamedata.GameTables
import io.github.okexodus.openknights.server.game.HeroDictionaryProgression.nativeInt
import io.github.okexodus.openknights.server.game.HeroDictionaryProgression.unpackHeroId

/**
 * The catalog inputs of `tools/pk_fortify_contract.py`: the owned-hero stat model's inputs (`hero_stat_inputs`) and the
 * whole-card Fortify planners' hero / equipment template inputs. The capture verifiers of that module (Server 17 / 121
 * and live reproductions, the native manifest) read private evidence and are not on a server path; they are not ported.
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

    /** `_exp_table(catalog, name)`: {level (101): EXP (102)} in table order as a JSON object; a repeated level is refused. */
    fun expTable(tables: GameTables, name: String): JObj {
        val table = JObj()
        for (row in tables.table(name).rows) {
            val level = nativeInt(row.field("101") ?: "").toString()
            if (level in table) throw PyValues.ValueError("Duplicate $name level")
            table[level] = JInt(nativeInt(row.field("102") ?: ""))
        }
        return table
    }

    /**
     * `hero_template_inputs(catalog, template)`: the hero row's base EXP 134, retention 404 and scale 137, the unique
     * configured cap of the packed grade / super class, the heroexp curve, property 410 (raw text) and the stat-model
     * inputs.
     */
    fun heroTemplateInputs(tables: GameTables, template: Long): JObj {
        val dims = unpackHeroId(template)
        val rows = tables.lookup("hero", dims.baseId.toString())
        if (rows.size != 1) throw PyValues.ValueError("Hero base ${dims.baseId} must resolve to exactly one row")
        val row = rows[0]
        val states = HeroDictionaryProgression.configuredStates(tables, row).filter {
            it.long("grade") == dims.grade && (it["super_class"] !is JInt || it.long("super_class") == dims.superClass)
        }
        if (states.size != 1) throw PyValues.ValueError("Packed hero $template has no unique configured cap")
        val properties = tables.lookup("property", "410")
        if (properties.size != 1) throw PyValues.ValueError("Property 410 must be unique")
        val result = jobj("template" to template, "base_id" to dims.baseId, "grade" to dims.grade,
            "base_exp_134" to nativeInt(row.field("134") ?: ""), "retention_404" to nativeInt(row.field("404") ?: ""),
            "scale_137" to nativeInt(row.field("137") ?: ""), "cap" to states[0]["cap"], "cap_row" to states[0]["row_ref"],
            "hero_row" to CatalogShapes.ref(row), "heroexp" to expTable(tables, "heroexp"),
            "property_410_raw" to properties[0].field("102")!!)
        for ((k, v) in heroStatInputs(tables, template)) result[k] = v
        return result
    }

    /** `catalog_inputs(catalog, target_template, material_templates)`: one target, one entry per distinct material template. */
    fun catalogInputs(tables: GameTables, targetTemplate: Long, materialTemplates: List<Long>): JObj =
        jobj("target" to heroTemplateInputs(tables, targetTemplate),
            "materials" to materialTemplates.distinct().map { heroTemplateInputs(tables, it) },
            "sources" to JObj().also { s -> for (name in listOf("hero", "heroexp", "jinhua", "jinhuazhujue", "property")) s[name] = CatalogShapes.source(tables, name) })

    /**
     * `equipment_template_inputs(catalog, template, grade)`: the equip row's category 106, group 306, base EXP 112, scale
     * 113 and retention 304, the unique equipjinhua cap row of (category, grade, group), the equipexp curve and property
     * 411 (raw text).
     */
    fun equipmentTemplateInputs(tables: GameTables, template: Long, grade: Long): JObj {
        val rows = tables.lookup("equip", template.toString())
        if (rows.size != 1) throw PyValues.ValueError("Equipment template $template must resolve to exactly one row")
        val row = rows[0]
        val caps = tables.table("equipjinhua").rows.filter {
            HeroDictionaryProgression.byte(it, "102") == HeroDictionaryProgression.byte(row, "106") &&
                HeroDictionaryProgression.byte(it, "103") == grade && HeroDictionaryProgression.n(it, "104") == HeroDictionaryProgression.n(row, "306")
        }
        if (caps.size != 1) throw PyValues.ValueError("Equipment $template grade $grade has no unique equipjinhua cap row")
        val properties = tables.lookup("property", "411")
        if (properties.size != 1) throw PyValues.ValueError("Property 411 must be unique")
        return jobj("template" to template, "grade" to grade, "category_106" to HeroDictionaryProgression.byte(row, "106"),
            "group_306" to HeroDictionaryProgression.n(row, "306"), "base_exp_112" to nativeInt(row.field("112") ?: ""),
            "scale_113" to nativeInt(row.field("113") ?: ""), "retention_304" to nativeInt(row.field("304") ?: ""),
            "cap" to (HeroDictionaryProgression.n(caps[0], "113") and HeroDictionaryProgression.U32), "cap_row" to CatalogShapes.ref(caps[0]),
            "equip_row" to CatalogShapes.ref(row), "equipexp" to expTable(tables, "equipexp"),
            "property_411_raw" to properties[0].field("102")!!)
    }

    /** `equipment_inputs(catalog, target, materials)`: keys are (template, grade) pairs. */
    fun equipmentInputs(tables: GameTables, target: Pair<Long, Long>, materials: List<Pair<Long, Long>>): JObj =
        jobj("target" to equipmentTemplateInputs(tables, target.first, target.second),
            "materials" to materials.distinct().map { equipmentTemplateInputs(tables, it.first, it.second) },
            "sources" to JObj().also { s -> for (name in listOf("equip", "equipexp", "equipjinhua", "property")) s[name] = CatalogShapes.source(tables, name) })
}
