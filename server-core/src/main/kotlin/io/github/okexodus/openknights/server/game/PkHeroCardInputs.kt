package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.gamedata.GameTable
import io.github.okexodus.openknights.gamedata.GameTables
import io.github.okexodus.openknights.server.game.HeroDictionaryProgression.unpackHeroId

/**
 * Read-only catalog inputs of the three hero-card systems (`tools/pk_hero_card_inputs.py`): Power Up (property 156
 * cap permille, 158 / 159 Hero Stone and stones per try, fumo.csv ways), Astral Power (zhushenzhili rows,
 * zhushenzhili_group lists) and Hero Ascension (herojuexing / herojuexinglv / herojuexingneedres, zhuansheng resource
 * base, property 900044). Every value is read from the game tables; nothing is inferred.
 *
 * The reference's integer-keyed dictionaries are [JObj]s keyed by the decimal text of the key (`"None"` for a blank
 * key cell), their tuples lists.
 */
object PkHeroCardInputs {
    val POWER_UP_WAY_ITEMS = linkedMapOf(1L to 30107L, 2L to 30221L, 3L to 30222L)

    /** `_int(value, default)`: `(value or "").strip()`, blank → default, else the strict `int()`. */
    fun int(value: String?, default: Long? = null): Long? {
        val v = PyValues.strip(value ?: "")
        if (v == "") return default
        return PyValues.parseLong(v)
    }

    /** `fields[key]` of the reference: a missing column is a KeyError (not a ValueError). */
    private fun col(row: GameTable.Row, key: String): String = row.field(key) ?: throw NoSuchElementException("'$key'")

    private fun key(value: Long?): String = value?.toString() ?: "None"

    /** `_property(catalog, key)`: column 102 of the one property row. */
    fun property(tables: GameTables, key: Long): String {
        val rows = tables.lookup("property", key.toString())
        if (rows.size != 1) throw PyValues.ValueError("Property $key must resolve to exactly one row")
        return col(rows[0], "102")
    }

    /** `_hero_row(catalog, template)`: (base, the one hero row). */
    fun heroRow(tables: GameTables, template: Long): Pair<Long, GameTable.Row> {
        val base = unpackHeroId(template).baseId
        val rows = tables.lookup("hero", base.toString())
        if (rows.size != 1) throw PyValues.ValueError("Hero base $base must resolve to exactly one row")
        return base to rows[0]
    }

    // --- Power Up --------------------------------------------------------------------------------------------------

    fun powerUpInputs(tables: GameTables): JObj {
        val p158 = int(property(tables, 158))
        if (p158 != POWER_UP_WAY_ITEMS.getValue(1)) throw PyValues.ValueError("Property 158 no longer names the Hero Stone 30107")
        for (item in POWER_UP_WAY_ITEMS.values) {
            if (tables.lookup("item", item.toString()).size != 1) throw PyValues.ValueError("Power Up stone item $item is not a unique item row")
        }
        return jobj("cap_permille_156" to int(property(tables, 156)), "stone_item_158" to p158,
            "stones_per_try_159" to int(property(tables, 159)),
            "way_items" to JObj().also { o -> POWER_UP_WAY_ITEMS.forEach { (k, v) -> o[k.toString()] = JInt(v) } },
            "table_ways" to fumoWays(tables))
    }

    /**
     * `fumo_ways`: the hero rows (102 = 1) per way (103): 105 decrease / 108 increase chance per 10,000, the decrease
     * ranges {stat type: [min, max]} (201 + 106/107, then 202-204 … 208-210) and the increase ranges (211 + 109/110,
     * then 212-214 … 218-220), the stone (112) and stones per try (113).
     */
    fun fumoWays(tables: GameTables): JObj {
        val ways = JObj()
        for (f in tables.table("fumo").rows) {
            if (int(f.field("102")) != 1L) continue
            val down = JObj()
            down[key(int(f.field("201")))] = jarr(int(f.field("106")), int(f.field("107")))
            for (t in listOf(202, 205, 208)) down[key(int(f.field("$t")))] = jarr(int(f.field("${t + 1}")), int(f.field("${t + 2}")))
            val up = JObj()
            up[key(int(f.field("211")))] = jarr(int(f.field("109")), int(f.field("110")))
            for (t in listOf(212, 215, 218)) up[key(int(f.field("$t")))] = jarr(int(f.field("${t + 1}")), int(f.field("${t + 2}")))
            ways[key(int(f.field("103")))] = jobj("item" to int(f.field("112")), "stones_per_try" to int(f.field("113")),
                "decrease" to int(f.field("105")), "increase" to int(f.field("108")),
                "down" to down, "up" to up, "row" to int(f.field("101")))
        }
        return ways
    }

    // --- Astral Power (god skills) -----------------------------------------------------------------------------------

    /** zhushenzhili rows by skill id (a duplicate key is refused). */
    fun astralRows(tables: GameTables): JObj {
        val rows = JObj()
        for (f in tables.table("zhushenzhili").rows) {
            val skill = int(col(f, "101"))
            if (key(skill) in rows) throw PyValues.ValueError("Duplicate zhushenzhili key $skill")
            rows[key(skill)] = jobj("skill" to skill, "series" to int(col(f, "102")), "level" to int(col(f, "103")),
                "need_108" to int(col(f, "108"), 0), "item_109" to int(col(f, "109"), 0),
                "per_press_110" to int(col(f, "110"), 0), "buff_kind_111" to int(col(f, "111")),
                "buff_112" to int(col(f, "112"), 0))
        }
        return rows
    }

    /** zhushenzhili_group: group (102) → its initial skills (105), sorted. */
    fun astralGroups(tables: GameTables): JObj {
        val groups = LinkedHashMap<String, MutableList<Long>>()
        for (f in tables.table("zhushenzhili_group").rows) {
            val skill = int(col(f, "105")) ?: continue
            groups.getOrPut(key(int(col(f, "102")))) { ArrayList() }.add(skill)
        }
        return JObj().also { o -> groups.forEach { (g, skills) -> o[g] = JArr(skills.sorted().mapTo(ArrayList()) { JInt(it) }) } }
    }

    /** The initial god-skill list of an owned hero: hero field 991 → group → its skills at progress 0. */
    fun astralInitialSkills(tables: GameTables, template: Long, groups: JObj? = null): JArr {
        val (_, hero) = heroRow(tables, template)
        val group = int(hero.field("991"))
        val all = groups ?: astralGroups(tables)
        if (group == null || group.toString() !in all) throw PyValues.ValueError("Hero template $template has no zhushenzhili_group via field 991")
        return JArr(all.arr(group.toString()).mapTo(ArrayList()) { jarr(it, 0) })
    }

    // --- Hero Ascension (awaken) -------------------------------------------------------------------------------------

    fun ascensionInputs(tables: GameTables, template: Long): JObj {
        val (base, hero) = heroRow(tables, template)
        val statInputs = PkFortifyContract.heroStatInputs(tables, template)
        statInputs["template"] = JInt(template)
        val lvRows = JObj()
        for (f in tables.table("herojuexinglv").rows) {
            lvRows[key(int(col(f, "101")))] = jobj("role_level_201" to int(col(f, "201"), 0), "hero_level_203" to int(col(f, "203"), 0),
                "text_202" to int(col(f, "202")))
        }
        val needRows = JObj()
        for (f in tables.table("herojuexingneedres").rows) {
            val materials = JArr()
            for ((i, q) in listOf("201" to "202", "203" to "204", "205" to "206")) {
                val item = int(col(f, i))
                if (item != null && item != 0L) materials.add(jarr(int(col(f, i)), int(col(f, q), 0)))
            }
            needRows[key(int(col(f, "101")))] = jobj("key" to int(col(f, "101")), "category_102" to int(col(f, "102")),
                "level_103" to int(col(f, "103")), "leader_104" to int(col(f, "104"), 0), "materials" to materials,
                "hero_count_301" to int(col(f, "301"), 0), "gold_401" to int(col(f, "401"), 0))
        }
        // Formula::GetHeroAwakeResourseCard walks RebornConfig (zhuansheng): a reborn base (104) maps to its original
        // base (102); any other base is its own resource card.
        val reborn = tables.table("zhuansheng").rows.filter { int(it.field("104")) == base }
        val resourceBase = if (reborn.size == 1) int(col(reborn[0], "102")) else base
        return jobj("template" to template, "base_id" to base, "is_leader" to (int(hero.field("143"), 0) != 0L),
            "category_104" to int(hero.field("104")), "resource_base" to resourceBase, "stat_inputs" to statInputs,
            "awaken_slots" to statInputs["awaken_slots"], "awaken_rows" to statInputs["awaken_rows"],
            "lv_rows" to lvRows, "need_rows" to needRows,
            "open_role_level_900044" to int(property(tables, 900044)))
    }

    /** Formula::GetHeroAwakeReqId: leader ? level × 100 | 1 : category × 10000 + level × 100. */
    fun awakenRequirementKey(isLeader: Boolean, category: Long, level: Long): Long = if (isLeader) (level * 100) or 1 else category * 10000 + level * 100
}

/**
 * `HeroCardInputs`: the lazy read-only catalog loader the server holds (`snapshot.hero_card_inputs`); every result is
 * cached (the reference caches per key and hands out the same objects).
 */
class HeroCardInputs(val tables: GameTables) {
    private val cache = HashMap<String, Any>()

    @Synchronized
    fun powerUp(): JObj = cache.getOrPut("power_up") { PkHeroCardInputs.powerUpInputs(tables) } as JObj

    /** `{"rows": astral_rows, "groups": astral_groups}`. */
    @Synchronized
    fun astral(): JObj = cache.getOrPut("astral") { jobj("rows" to PkHeroCardInputs.astralRows(tables), "groups" to PkHeroCardInputs.astralGroups(tables)) } as JObj

    fun astralInitial(template: Long): JArr = PkHeroCardInputs.astralInitialSkills(tables, template, astral().obj("groups"))

    /** `hero_stat_inputs(template)` plus `"template"`. */
    @Synchronized
    fun heroStats(template: Long): JObj = cache.getOrPut("stats $template") {
        PkFortifyContract.heroStatInputs(tables, template).also { it["template"] = JInt(template) }
    } as JObj

    @Synchronized
    fun ascension(template: Long): JObj = cache.getOrPut("ascension $template") { PkHeroCardInputs.ascensionInputs(tables, template) } as JObj
}
