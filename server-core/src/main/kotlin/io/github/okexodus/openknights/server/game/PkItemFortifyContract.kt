package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.gamedata.GameTables
import io.github.okexodus.openknights.server.game.HeroDictionaryProgression.n

/**
 * The catalog inputs of EXP-item Fortify (`tools/pk_item_fortify_contract.py`): the qianghua_itemexp resolution and the
 * hero / gear / jewelry target inputs (cap, EXP curve, scale, Gold property). The capture verifier of that module
 * (`verify`) reads private evidence and is not ported.
 */
object PkItemFortifyContract {
    /** `item_exp_map(catalog)`: {request template (101): {item_id 103, exp 104, category 102}}; the last duplicate wins. */
    fun itemExpMap(tables: GameTables): LinkedHashMap<Long, JObj> {
        val result = LinkedHashMap<Long, JObj>()
        for (row in tables.table("qianghua_itemexp").rows) {
            result[n(row, "101")] = jobj("item_id" to n(row, "103"), "exp" to n(row, "104"), "category" to n(row, "102"))
        }
        return result
    }

    /** `hero_item_inputs(catalog, template)`: the current-grade cap, heroexp curve, scale, cost property and stat-model inputs. */
    fun heroItemInputs(tables: GameTables, template: Long): JObj {
        val base = PkFortifyContract.heroTemplateInputs(tables, template)
        val result = jobj("class" to 1, "template" to base["template"], "grade" to base["grade"], "cap" to base["cap"],
            "scale_137" to base["scale_137"], "heroexp" to base["heroexp"], "property_410_raw" to base["property_410_raw"])
        for (key in listOf("raw_511_514", "current_potential_rate", "property_533", "hundreds_digit", "awaken_slots", "awaken_rows")) {
            result[key] = base.getValue(key)
        }
        return result
    }

    /** `gear_item_inputs(catalog, template, grade)`. */
    fun gearItemInputs(tables: GameTables, template: Long, grade: Long): JObj {
        val base = PkFortifyContract.equipmentTemplateInputs(tables, template, grade)
        return jobj("class" to 2, "template" to base["template"], "grade" to base["grade"], "cap" to base["cap"],
            "scale_113" to base["scale_113"], "equipexp" to base["equipexp"], "property_411_raw" to base["property_411_raw"])
    }

    /** `_exp_table(catalog, name)` of this module: {101: 102}, the last duplicate wins (no duplicate check). */
    private fun expTable(tables: GameTables, name: String): JObj {
        val table = JObj()
        for (row in tables.table(name).rows) table[n(row, "101").toString()] = JInt(n(row, "102"))
        return table
    }

    /**
     * `jewelry_item_inputs(catalog, template, grade)`: the jewelry_jinhua cap row selected like equipjinhua (102 = jewelry
     * 106, 103 = grade, 104 = jewelry 121; cap 113), the jewelry_exp curve x jewelry 115 / 10000, cost property 412.
     */
    fun jewelryItemInputs(tables: GameTables, template: Long, grade: Long): JObj {
        val rows = tables.lookup("jewelry", template.toString())
        if (rows.size != 1) throw PyValues.ValueError("Jewelry template $template must resolve to exactly one row")
        val row = rows[0]
        val caps = tables.table("jewelry_jinhua").rows.filter { n(it, "102") == n(row, "106") && n(it, "103") == grade && n(it, "104") == n(row, "121") }
        if (caps.size != 1) throw PyValues.ValueError("Jewelry $template grade $grade has no unique jewelry_jinhua cap row")
        val prop = tables.lookup("property", "412")
        if (prop.size != 1) throw PyValues.ValueError("Property 412 must be unique")
        return jobj("class" to 3, "template" to template, "grade" to grade, "cap" to n(caps[0], "113"),
            "cap_row" to CatalogShapes.ref(caps[0]), "category_106" to n(row, "106"), "group_121" to n(row, "121"),
            "scale_jewelry" to n(row, "115"), "jewelryexp" to expTable(tables, "jewelry_exp"),
            "property_jewelry_raw" to prop[0].field("102")!!)
    }
}

/** The lazy read-only catalog loader of EXP-item Fortify (`ItemFortifyInputs`); results are cached per key. */
class ItemFortifyInputs(val tables: GameTables) {
    private var itemMap: LinkedHashMap<Long, JObj>? = null
    private val cache = HashMap<List<Any>, JObj>()

    @Synchronized
    fun itemMap(): Map<Long, JObj> = itemMap ?: PkItemFortifyContract.itemExpMap(tables).also { itemMap = it }

    @Synchronized
    fun hero(template: Long): JObj = cache.getOrPut(listOf("hero", template)) { PkItemFortifyContract.heroItemInputs(tables, template) }

    @Synchronized
    fun gear(template: Long, grade: Long): JObj = cache.getOrPut(listOf("gear", template, grade)) { PkItemFortifyContract.gearItemInputs(tables, template, grade) }

    @Synchronized
    fun jewelry(template: Long, grade: Long): JObj = cache.getOrPut(listOf("jewelry", template, grade)) { PkItemFortifyContract.jewelryItemInputs(tables, template, grade) }
}
