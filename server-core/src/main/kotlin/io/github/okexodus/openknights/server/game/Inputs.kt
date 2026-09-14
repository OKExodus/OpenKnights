package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.gamedata.GameTable
import io.github.okexodus.openknights.gamedata.GameTables

/** Thrown by a part of the port that is not written yet: the session answers it as `not_implemented`. */
class NotPorted(what: String) : UnsupportedOperationException("not ported yet: $what")

/**
 * Catalog inputs of the acquisition transactions (`tools/pk_acquisition_inputs.py`): read-only lookups over the
 * player's game tables. Values keep the reference's shapes (dictionaries → [JObj], tuples → [JArr]) and each
 * lookup keeps its own number rule and duplicate-key rule.
 */
open class AcquisitionInputs(val tables: GameTables) {
    companion object {
        /** `_n(row, key, default)`: the stripped cell as `int()`, else the default (empty or absent). */
        fun n(row: GameTable.Row, key: String, default: Long? = 0): Long? {
            val value = PyValues.strip(row.field(key) ?: "")
            return if (value.isNotEmpty()) PyValues.parseLong(value) else default
        }

        fun n0(row: GameTable.Row, key: String): Long = n(row, key, 0)!!
    }

    /** `_single(table, key)`: the only row of `key`, else null (none or several). */
    fun single(table: String, key: Any): GameTable.Row? {
        val rows = tables.lookup(table, key.toString())
        return if (rows.size == 1) rows[0] else null
    }

    /** `_rows(table)`: every row, in CSV order. */
    fun rows(table: String): List<GameTable.Row> = tables.table(table).rows

    private val items = HashMap<Long, JObj?>()

    /** item.csv fields used by the acquisition paths, or null for an unknown template. */
    fun item(template: Long): JObj? = items.getOrPut(template) {
        val row = single("item", template) ?: return@getOrPut null
        jobj("template" to n0(row, "101"), "name_text" to n0(row, "102"), "type_104" to n0(row, "104"),
            "level_105" to n0(row, "105"), "use_106" to n0(row, "106"), "value_107" to n0(row, "107"),
            "max_stack_203" to n0(row, "203"), "vip_204" to n0(row, "204"), "key_205" to n0(row, "205"),
            "key_count_206" to n0(row, "206"), "bag_305" to n0(row, "305"), "sell_110" to n0(row, "110"),
            "source_row" to rowRef(row))
    }

    /** A row reference as the catalog gives it (`row["ref"]`). */
    fun rowRef(row: GameTable.Row): JObj =
        jobj("file" to row.table, "layer" to "apk-4.4.9", "source_id" to "${row.table}@apk-4.4.9", "csv_row" to row.csvRow)

    private var boxGroups: Map<Long, List<JObj>>? = null

    fun boxGroup(group: Long): List<JObj> {
        val groups = boxGroups ?: LinkedHashMap<Long, MutableList<JObj>>().also { out ->
            for (row in rows("box")) {
                val entry = jobj("row" to n0(row, "101"), "slot_103" to n(row, "103", null), "weight_104" to n(row, "104", null))
                for (key in listOf("105", "106", "107", "108", "109", "110", "111", "112", "113", "114", "115", "116", "117", "201", "202", "203")) {
                    entry["f$key"] = JInt(n0(row, key))
                }
                entry["csv_row"] = JInt(row.csvRow)
                out.getOrPut(n0(row, "102")) { ArrayList() }.add(entry)
            }
            boxGroups = out
        }
        return ArrayList(groups[group] ?: emptyList())
    }

    private val choose = HashMap<Long, JArr?>()

    /** The option triples (type, id, count) up to the first id 0. */
    fun chooseBox(key: Long): JArr? = choose.getOrPut(key) {
        val row = single("choosebox", key) ?: return@getOrPut null
        val options = JArr()
        for (index in 0 until 20) {
            val kind = n0(row, (201 + 3 * index).toString())
            val ident = n0(row, (202 + 3 * index).toString())
            val count = n0(row, (203 + 3 * index).toString())
            if (ident == 0L) break
            options.add(jobj("type" to kind, "id" to ident, "count" to count))
        }
        options
    }

    private val hecheng = HashMap<Long, JObj?>()

    fun recipe(key: Long): JObj? = hecheng.getOrPut(key) {
        val row = single("hecheng", key) ?: return@getOrPut null
        jobj("key" to n0(row, "101"), "kind_102" to n0(row, "102"), "target_103" to n0(row, "103"), "gold_104" to n0(row, "104"),
            "materials" to listOf("105" to "106", "107" to "108", "109" to "110", "111" to "112").map { (a, b) -> jarr(n0(row, a), n0(row, b)) },
            "mode_113" to n0(row, "113"), "substitute_115" to n0(row, "115"),
            "bonus_201_203" to listOf(n0(row, "201"), n0(row, "202"), n0(row, "203")),
            "slot_kinds_301_304" to listOf(n0(row, "301"), n0(row, "302"), n0(row, "303"), n0(row, "304")),
            "csv_row" to row.csvRow)
    }

    private var lots: List<JObj>? = null

    fun lotRows(): List<JObj> = lots ?: rows("xinniudan").map { row ->
        jobj("id" to n0(row, "101"), "lot" to n0(row, "102"), "currency_103" to n0(row, "103"), "cost_104" to n0(row, "104"),
            "count_105" to n0(row, "105"), "free_106" to n0(row, "106"), "first_107" to n0(row, "107"), "limited_108" to n0(row, "108"),
            "first_group_200" to n0(row, "200"),
            "slots" to listOf("201" to "202", "211" to "212", "221" to "222", "241" to "242").map { (g, w) -> jarr(n0(row, g), n0(row, w)) },
            "pity_400_404" to listOf("400", "401", "402", "403", "404").map { n0(row, it) },
            "bonus_901_903" to listOf("901", "902", "903").map { n0(row, it) },
            "rotation_991" to n0(row, "991"), "csv_row" to row.csvRow)
    }.also { lots = it }

    private var niudan: Map<Long, List<JObj>>? = null

    fun niudanGroup(group: Long): List<JObj> {
        val groups = niudan ?: LinkedHashMap<Long, MutableList<JObj>>().also { out ->
            for (row in rows("niudanhero")) {
                out.getOrPut(n0(row, "102")) { ArrayList() }.add(jobj("id" to n0(row, "101"), "weight" to n0(row, "104"), "hero" to n0(row, "103"),
                    "lucky_item_301" to n0(row, "301"), "lucky_count_302" to n0(row, "302"), "item_105" to n0(row, "105"), "count_106" to n0(row, "106")))
            }
            niudan = out
        }
        return ArrayList(groups[group] ?: emptyList())
    }

    /** NiuDanGroupConfig::GetGroupId: time rotation over the rows of field 105 == cycle. */
    fun rotationGroup(cycle: Long, serverTime: Long): Long? {
        val selected = rows("xinniudan_xunhuan").filter { n0(it, "105") == cycle }.sortedBy { n0(it, "101") }
        val total = selected.sumOf { n0(it, "106") }
        if (total == 0L) return null
        var t = Math.floorMod(serverTime, total)
        for (r in selected) {
            t -= n0(r, "106")
            if (t < 0) return n0(r, "102")
        }
        return null
    }

    fun heroStar(packed: Long): Long? = single("hero", Math.floorDiv(packed, 1000L))?.let { n0(it, "104") }

    fun refineAmount(star: Long, superClass: Long): JObj? {
        for (row in rows("herorh")) {
            if (n0(row, "102") == star && n0(row, "103") == superClass) return jobj("item" to n0(row, "104"), "amount" to n0(row, "105"), "row" to n0(row, "101"))
        }
        return null
    }

    fun shopVipCeiling(commodity: Long): Long? = single("shop_vip", commodity)?.let { n0(it, "127") }

    private var vip: LinkedHashMap<Long, JObj>? = null

    /** viplv.csv by VIP level (col 102; a later duplicate wins), with a zero row for an unknown level. */
    fun vipRow(level: Long): JObj {
        val table = vip ?: LinkedHashMap<Long, JObj>().also { out ->
            for (row in rows("viplv")) {
                out[n0(row, "102")] = jobj("level" to n0(row, "102"), "exp_103" to n0(row, "103"), "discount_501" to n0(row, "501"),
                    "ap_buys_107" to n0(row, "107"), "energy_buys_108" to n0(row, "108"))
            }
            vip = out
        }
        return table[level] ?: jobj("level" to level, "exp_103" to 0, "discount_501" to 0, "ap_buys_107" to 0, "energy_buys_108" to 0)
    }

    fun vipLevels(): Map<Long, JObj> { vipRow(0); return LinkedHashMap(vip!!) }

    private var vipAchieveRows: LinkedHashMap<Long, JObj>? = null

    /** vipachieve.csv rows by id (the first row of an id wins; id 0 / empty skipped). */
    fun vipAchieve(): Map<Long, JObj> = vipAchieveRows ?: LinkedHashMap<Long, JObj>().also { out ->
        for (row in rows("vipachieve")) {
            val key = PyValues.strip(row.field("101") ?: "")
            if (key.isEmpty() || key == "0") continue
            val id = PyValues.parseLong(key)
            if (id in out) continue
            fun num(c: String): Long { val v = PyValues.strip(row.field(c) ?: "0"); return if (v.isEmpty()) 0 else PyValues.parseLong(v) }
            val rewards = JArr()
            for (k in 0 until 4) if (num((116 + 3 * k).toString()) != 0L) rewards.add(jarr(num((115 + 3 * k).toString()), num((116 + 3 * k).toString()), num((117 + 3 * k).toString())))
            out[id] = jobj("id" to id, "mission1_kind" to num("108"), "mission1_value" to num("109"), "mission2_kind" to num("112"),
                "mission2_a" to num("113"), "mission2_b" to num("114"), "rewards" to rewards)
        }
        vipAchieveRows = out
    }

    fun vipAchieve(rowId: Long): JObj? = vipAchieve()[rowId]

    /** yueka.csv: the (kind, item, qty) triples of card type (102) and day (103), or null. */
    fun monthCardRewards(cardType: Long, day: Long): JArr? {
        for (row in rows("yueka")) {
            if (PyValues.strip(row.field("102") ?: "") == cardType.toString() && PyValues.strip(row.field("103") ?: "") == day.toString()) {
                val triples = JArr()
                for (k in 0 until 4) {
                    val kind = PyValues.strip(row.field((201 + 3 * k).toString()) ?: "")
                    val item = PyValues.strip(row.field((202 + 3 * k).toString()) ?: "")
                    val qty = PyValues.strip(row.field((203 + 3 * k).toString()) ?: "")
                    if (item.isNotEmpty() && item != "0" && qty.isNotEmpty() && qty != "0") {
                        triples.add(jarr(if (kind.isEmpty()) 0L else PyValues.parseLong(kind), PyValues.parseLong(item), PyValues.parseLong(qty)))
                    }
                }
                return triples
            }
        }
        return null
    }

    /** herorh / equiprh / jewelry_ronghe row of (102 star, 103 super): cells that look like integers become integers. */
    fun composeRow(table: String, star: Long, superClass: Long): JObj? {
        for (row in rows(table)) {
            if (PyValues.strip(row.field("102") ?: "") == star.toString() && PyValues.strip(row.field("103") ?: "") == superClass.toString()) {
                val out = JObj()
                for ((k, v) in row.fields()) {
                    if (PyValues.strip(v).isEmpty()) continue
                    out[k] = if (PyValues.isDigit(PyValues.strip(v).trimStart('-'))) JInt(PyValues.parseInt(v)) else JStr(v)
                }
                return out
            }
        }
        return null
    }

    fun itemRefine(template: Long): JObj? {
        for (row in rows("item_rh")) {
            if (PyValues.strip(row.field("101") ?: "") == template.toString()) {
                return jobj("product" to PyValues.parseLong(row.field("102")!!), "per_unit" to PyValues.parseLong(row.field("103")!!))
            }
        }
        return null
    }

    fun equipStar(template: Long): Long? = single("equip", template)?.let { n0(it, "106") }
    fun jewelStar(template: Long): Long? = single("jewelry", template)?.let { n0(it, "106") }

    fun heroFields(packed: Long): Map<String, String>? = single("hero", Math.floorDiv(packed, 1000L))?.fields()

    fun composeFields(table: String, template: Long): JObj? {
        require(table == "equip" || table == "jewelry") { "Combine fields exist for equip / jewelry only" }
        val row = single(table, template) ?: return null
        val (same, other) = if (table == "equip") "501" to "500" else "123" to "122"
        return jobj("star_106" to n0(row, "106"), "same_only_499" to n0(row, "499"), "same_template" to n0(row, same), "other_template" to n0(row, other))
    }

    /** `property_value_inputs(kind, template, grade)`: the gear / jewelry evolve inputs, or null when they cannot be formed. */
    open fun propertyValueInputs(kind: String, template: Long, grade: Long): JObj? = try {
        if (kind == "gear") PkEquipEvolveContract.gearEvolveInputs(tables, template, grade)
        else PkEquipEvolveContract.jewelEvolveInputs(tables, template, grade)
    } catch (e: IllegalArgumentException) {
        null            // the reference's ValueError (unknown template, random range, non-unique rows, atoi bound)
    } catch (e: NoSuchElementException) {
        null            // a missing table (a ValueError of the reference's catalog)
    }

    private var rebornRowsCache: List<JObj>? = null

    fun rebornRows(): List<JObj> = rebornRowsCache ?: rows("zhuansheng").mapNotNull { row ->
        fun num(c: String): Long { val v = PyValues.strip(row.field(c) ?: ""); return if (v.isEmpty()) 0 else PyValues.parseLong(v) }
        if (num("101") == 0L) return@mapNotNull null
        jobj("id" to num("101"), "base_102" to num("102"), "type1_base_103" to num("103"), "base_104" to num("104"),
            "enabled_105" to num("105"), "grade_106" to num("106"), "level_107" to num("107"),
            "materials" to (0 until 4).map { k -> jarr(num((201 + 2 * k).toString()), num((202 + 2 * k).toString())) },
            "stone_type1_209" to num("209"), "stone_type0_210" to num("210"), "stone_count_211" to num("211"), "gold_213" to num("213"))
    }.also { rebornRowsCache = it }

    /** The shared owned-hero stat model's inputs at a packed template (`pk_fortify_contract.hero_stat_inputs`). */
    open fun heroStatInputs(template: Long): JObj =
        PkFortifyContract.heroStatInputs(tables, template).also { it["template"] = JInt(template) }

    /** viplv row of a VIP level: 104 Gold, 105 Diamonds, 202 item, 103 EXP (the first matching row). */
    fun vipDaily(level: Long): JObj? {
        for (row in rows("viplv")) {
            if (PyValues.strip(row.field("102") ?: "") == level.toString()) {
                fun num(c: String): Long { val v = row.field(c) ?: ""; return if (v.isEmpty()) 0 else PyValues.parseLong(v) }
                return jobj("gold_104" to num("104"), "diamond_105" to num("105"), "item_202" to num("202"), "exp_103" to num("103"))
            }
        }
        return null
    }

    private val existsCache = HashMap<Pair<String, String>, Boolean>()

    fun exists(table: String, key: Any): Boolean = existsCache.getOrPut(table to key.toString()) { single(table, key) != null }
    fun heroExists(packed: Long): Boolean = exists("hero", Math.floorDiv(packed, 1000L))

    private val propertyCache = HashMap<String, String?>()

    /** property.csv col 102 of a key (raw text), or null. */
    fun property(key: Any): String? = propertyCache.getOrPut(key.toString()) { single("property", key)?.field("102") }

    /** The live S32 fresh-hero map (21 fields) — `pk_test_fixture_inject_hero.fresh_hero_fields`. */
    open fun freshHeroFields(uid: Long, template: Long): JArr = PkTestFixtureInjectHero.freshHeroFields(tables, uid, template)
    private var astralGroups: JObj? = null

    /** The initial god-skill list of a hero template (`pk_hero_card_inputs.astral_initial_skills`, groups cached). */
    open fun astralInitialSkills(template: Long): JArr {
        val groups = astralGroups ?: PkHeroCardInputs.astralGroups(tables).also { astralGroups = it }
        return PkHeroCardInputs.astralInitialSkills(tables, template, groups)
    }

    val BATTLE_TABLES = setOf("stage", "monster", "monsterability", "hero", "skill", "effect", "gift", "text", "herojuexing")
    private val battleRows = HashMap<Pair<String, String>, Map<String, String>?>()

    fun battleRow(table: String, key: Any): Map<String, String>? {
        require(table in BATTLE_TABLES) { "Not a battle table: $table" }
        return battleRows.getOrPut(table to key.toString()) { single(table, key)?.fields() }
    }

    private var battlePotentialGroups: Map<String, List<Pair<Long, Long>>>? = null

    fun battlePotential(group: Any, grade: Long): Long {
        val groups = battlePotentialGroups ?: LinkedHashMap<String, MutableList<Pair<Long, Long>>>().also { out ->
            for (row in rows("jinhua")) {
                fun num(c: String): Long { val v = PyValues.strip(row.field(c) ?: ""); return if (v.isEmpty()) 0 else PyValues.parseLong(v) }
                out.getOrPut(PyValues.strip(row.field("104") ?: "")) { ArrayList() }.add(num("103") to num("503"))
            }
            battlePotentialGroups = out
        }
        return (groups[PyValues.strip(group.toString())] ?: emptyList()).filter { it.first < grade }.sumOf { it.second }
    }

    private var battleStatTablesCache: JObj? = null

    /**
     * `battle_stat_tables()`: the config rows read by the client's GetHeroAbility / GetBattleSlotAbility /
     * HeroAddCombEffect, keyed and laid out as its loaders do: integers through C `atoi` (empty → 0, no bound), byte
     * members cut to 8 bits, map key = col 101 with key 0 skipped and the FIRST row of a repeated key kept. Built once
     * per instance; maps keyed by integers are objects whose keys are the decimal keys in first-occurrence order (the
     * reference's dictionaries, as `json.dumps` writes them).
     */
    @Synchronized
    open fun battleStatTables(): JObj {
        battleStatTablesCache?.let { return it }

        fun atoi(value: String?): Long {
            var text = value ?: ""
            var i = 0
            while (i < text.length) { val cp = text.codePointAt(i); if (!io.github.okexodus.openknights.exact.PyText.isSpace(cp)) break; i += Character.charCount(cp) }
            text = text.substring(i)
            var sign = 1L
            if (text.isNotEmpty() && (text[0] == '+' || text[0] == '-')) { sign = if (text[0] == '-') -1L else 1L; text = text.substring(1) }
            var end = 0
            while (end < text.length) { val cp = text.codePointAt(end); if (!io.github.okexodus.openknights.exact.PyText.isDigit(cp)) break; end += Character.charCount(cp) }
            return if (end > 0) sign * PyValues.parseInt(text.substring(0, end)).longValueExact() else 0L
        }

        fun keyed(table: String, key: String = "101"): LinkedHashMap<Long, GameTable.Row> {
            val out = LinkedHashMap<Long, GameTable.Row>()
            for (f in rows(table)) {
                val k = atoi(f.field(key)) and 0xFFFFFFFFL
                if (k != 0L && k !in out) out[k] = f
            }
            return out
        }

        fun byte(value: String?, signed: Boolean = false): Long {
            val b = atoi(value) and 0xFF
            return if (signed && b > 127) b - 256 else b
        }

        fun n(f: GameTable.Row, c: Int): Long = atoi(f.field(c.toString()))
        fun pairs(f: GameTable.Row, cols: List<Pair<Int, Int>>): JArr = JArr(cols.mapTo(ArrayList()) { (t, v) -> jarr(n(f, t), n(f, v)) })
        fun <V> byKey(map: Map<Long, V>, value: (Long, V) -> JValue): JObj {
            val out = JObj()
            for ((k, f) in map) out[k.toString()] = value(k, f)
            return out
        }

        val prop = JObj()
        for (key in listOf(243, 4001, 4007)) {
            val row = single("property", key)
            prop[key.toString()] = if (row == null) io.github.okexodus.openknights.exact.JNull else JInt(atoi(row.field("102")))
        }
        val sixPairs = listOf(307 to 308, 310 to 311, 313 to 314, 316 to 317, 319 to 320, 322 to 323)
        val jewelPairs = listOf(124 to 125, 126 to 127, 128 to 129, 130 to 131, 132 to 133, 134 to 135)
        val secondary = LinkedHashMap<Long, JArr>()
        for ((t, f) in keyed("fujiangshuxing", "102")) secondary[t] = jarr(t, n(f, 103))
        val tables = jobj(
            "title" to byKey(keyed("title")) { _, f -> jarr(n(f, 201), n(f, 202), n(f, 203)) },
            "technology" to byKey(keyed("technology")) { _, f -> jarr(n(f, 106), n(f, 107)) },
            "questmedal" to JArr(keyed("questmedal").entries.sortedBy { it.key }.mapTo(ArrayList()) { (k, f) ->
                jarr(k, n(f, 301), n(f, 201), n(f, 202), n(f, 203), n(f, 204)) }),
            "god_skill" to byKey(keyed("zhushenzhili")) { _, f -> jarr(n(f, 111), n(f, 112)) },
            "property" to prop,
            "totem" to byKey(keyed("totem")) { _, f ->
                jobj("group" to n(f, 104), "stats" to JArr((0 until 4).mapTo(ArrayList()) { i -> jarr(n(f, 111 + 3 * i), n(f, 112 + 3 * i), n(f, 113 + 3 * i)) })) },
            "totem_adv" to JArr(keyed("totem_jinhua").values.mapTo(ArrayList()) { f -> jarr(byte(f.field("102")), n(f, 104), n(f, 115)) }),
            "zodiac" to byKey(keyed("zodiac")) { _, f -> jarr(n(f, 106), n(f, 107), n(f, 108)) },
            "photo" to JArr(keyed("tujian").entries.mapTo(ArrayList()) { (k, f) ->
                jarr(byte(f.field("102")), k, JArr((104 until 112).map { n(f, it) }.filter { it != 0L }.mapTo(ArrayList()) { JInt(it) }),
                    pairs(f, listOf(112 to 113, 114 to 115, 116 to 117, 118 to 119))) }),
            "equip" to byKey(keyed("equip")) { _, f -> jarr(n(f, 107), byte(f.field("400")), pairs(f, sixPairs)) },
            "jewel" to byKey(keyed("jewelry")) { _, f -> jarr(n(f, 107), byte(f.field("105"), signed = true), pairs(f, jewelPairs)) },
            "gem" to byKey(keyed("baoshi")) { _, f -> jarr(n(f, 103), n(f, 104)) },
            "secondary_effect" to JArr(secondary.values.sortedWith(compareBy<JArr>({ it[0].long }, { it[1].long })).toMutableList<JValue>()),
            "combo" to JArr(keyed("hero_zuhe").entries.sortedBy { it.key }.mapTo(ArrayList()) { (k, f) ->
                jobj("key" to k, "enabled" to byte(f.field("103")), "hero" to n(f, 104),
                    "partners" to JArr(listOf(105 to 106, 107 to 108, 109 to 110, 111 to 112).mapTo(ArrayList()) { (t, i) -> jarr(byte(f.field(t.toString())), n(f, i)) }),
                    "bonuses" to pairs(f, listOf(113 to 114, 115 to 116, 117 to 118, 119 to 120, 121 to 122))) }),
            "hero_class" to byKey(keyed("hero")) { _, f -> JInt(byte(f.field("103"))) },
        )
        battleStatTablesCache = tables
        return tables
    }
}

/** The daily / social systems' catalog inputs (`tools/pk_daily_inputs.py`), on top of the acquisition inputs. */
open class DailyInputs(tables: GameTables) : AcquisitionInputs(tables) {
    companion object {
        /** `_int(value, default)`. */
        fun int(value: String?, default: Long = 0): Long = PyValues.digitInt(value, default)
        fun int(row: GameTable.Row, key: String, default: Long = 0): Long = int(row.field(key), default)

        val GUILD_PERMISSIONS = linkedMapOf("approve" to "103", "kick" to "104", "kickable" to "105", "transfer" to "106", "mail" to "107",
            "notice" to "113", "tech" to "114", "rename" to "120")
    }

    fun tableRows(table: String): List<GameTable.Row> = rows(table)

    fun palaceRow(day: Long): JObj? {
        for (f in rows("canbainvwang")) {
            if (int(f, "102") == day) {
                val bonus = JArr()
                for (base in listOf(105, 108)) {
                    val kind = int(f, base.toString()); val item = int(f, (base + 1).toString()); val count = int(f, (base + 2).toString())
                    if (item != 0L && count != 0L) bonus.add(jarr(kind, item, count))
                }
                return jobj("id" to int(f, "101"), "day" to day, "bonus_flag" to int(f, "103"), "gold" to int(f, "104"), "bonus" to bonus)
            }
        }
        return null
    }

    fun palaceBonusDays(): List<Long> = rows("canbainvwang").filter { int(it, "103") == 1L }.map { int(it, "102") }

    fun timeGift(row: Long): JObj? = rows("timegift").firstOrNull { int(it, "101") == row }?.let { f ->
        jobj("row" to row, "wait" to int(f, "102"), "kind" to int(f, "103"), "item" to int(f, "104"))
    }

    fun checkInMilestone(count: Long): JObj? = rows("qiandao").firstOrNull { int(it, "102") == count }?.let { f ->
        val items = listOf(104 to 201, 106 to 202, 108 to 203, 110 to 204).map { (i, q) -> int(f, i.toString()) to int(f, q.toString()) }
        jobj("row" to int(f, "101"), "count" to count, "items" to items.filter { it.first != 0L && it.second != 0L }.map { jarr(it.first, it.second) })
    }

    fun titleSalary(title: Long): JObj? = rows("title").firstOrNull { int(it, "101") == title }?.let { f ->
        jobj("title" to title, "gold" to int(f, "106"), "diamond" to int(f, "107"))
    }

    fun dailyActivity(ident: Long): JObj? = rows("dailyactivities").firstOrNull { int(it, "101") == ident }?.let { f ->
        jobj("id" to ident, "max" to int(f, "103"), "points" to int(f, "104"))
    }

    fun dailyActivityGift(ident: Long): JObj? = rows("dailyactivties_gift").firstOrNull { int(it, "101") == ident }?.let { f ->
        jobj("id" to ident, "threshold" to int(f, "102"), "kind" to int(f, "103"), "item" to int(f, "104"), "count" to int(f, "105"))
    }

    fun royalDoorLevel(level: Long): JObj? = rows("lv_yijiezhimen").firstOrNull { int(it, "101") == level }?.let { f ->
        fun triple(b: Int) = listOf(int(f, b.toString()), int(f, (b + 1).toString()), int(f, (b + 2).toString()))
        val next = int(f, "102")
        jobj("level" to level, "next_exp" to (if (next == 0L) null else next),
            "daily" to listOf(triple(103), triple(106)).filter { it[1] != 0L && it[2] != 0L }.map { jarr(*it.toTypedArray()) },
            "level_up" to listOf(triple(109), triple(112)).filter { it[1] != 0L && it[2] != 0L }.map { jarr(*it.toTypedArray()) })
    }

    private var questCache: LinkedHashMap<Long, JObj>? = null

    /** quest.csv rows by id (the first row of an id wins). */
    fun quests(): Map<Long, JObj> = questCache ?: LinkedHashMap<Long, JObj>().also { cache ->
        for (f in rows("quest")) {
            val key = int(f, "101")
            if (key != 0L && key !in cache) {
                cache[key] = jobj("id" to key, "reward_mode" to int(f, "106"), "min_level" to int(f, "108"), "kind" to int(f, "109"),
                    "param" to int(f, "110"), "target" to int(f, "111"), "show_progress" to int(f, "113"), "exp" to int(f, "114"),
                    "gold" to int(f, "115"), "honor" to int(f, "116"), "item_flag" to int(f, "117"), "item" to int(f, "118"),
                    "item_count" to int(f, "119"), "prerequisite" to int(f, "120"), "story" to int(f, "121"), "requires" to int(f, "125"),
                    "points" to int(f, "124"))
            }
        }
        questCache = cache
    }

    fun quest(ident: Long): JObj? = quests()[ident]

    fun bountyStar(stars: Long): JObj? = rows("quest_bounty").firstOrNull { int(it, "102") == stars }?.let { f ->
        jobj("stars" to stars, "weight" to int(f, "103"), "exp_bonus" to int(f, "104"), "gold_bonus" to int(f, "105"),
            "honor_bonus" to int(f, "106"), "points_bonus" to int(f, "107"), "auto_seconds" to int(f, "108"), "expedite_price" to int(f, "109"))
    }

    fun combineRecipe(recipe: Long): JObj? = rows("equip_hecheng").firstOrNull { int(it, "101") == recipe }?.let { f ->
        jobj("recipe" to recipe, "result" to int(f, "102"),
            "materials" to listOf(103, 106, 109, 112).map { b -> jarr(int(f, b.toString()), int(f, (b + 1).toString()), int(f, (b + 2).toString())) },
            "cost_item" to int(f, "115"), "cost" to int(f, "116"))
    }

    private var roleExpCache: LinkedHashMap<Long, Long>? = null

    fun roleExp(level: Long): Long? {
        val cache = roleExpCache ?: LinkedHashMap<Long, Long>().also { out ->
            for (f in rows("roleexp")) { val key = int(f, "101"); if (key != 0L) out[key] = int(f, "102") }
            roleExpCache = out
        }
        return cache[level]
    }

    fun maxRoleLevel(): Long { roleExp(1); return roleExpCache!!.keys.max() }

    private val keyedCache = HashMap<Pair<String, String>, LinkedHashMap<Long, GameTable.Row>>()

    /** `_keyed(table, key_col)`: {int(key): row} (a later duplicate wins; key 0 skipped). */
    fun keyed(table: String, keyCol: String = "101"): Map<Long, GameTable.Row> = keyedCache.getOrPut(table to keyCol) {
        LinkedHashMap<Long, GameTable.Row>().also { out -> for (f in rows(table)) { val k = int(f, keyCol); if (k != 0L) out[k] = f } }
    }

    fun collectRow(n: Long): JObj? = keyed("zhengshou")[n]?.let { f ->
        jobj("n" to n, "cost_base" to int(f, "102"), "gold" to int(f, "103"), "honor" to int(f, "104"), "f105" to int(f, "105"),
            "x2" to int(f, "201"), "x4" to int(f, "202"), "x10" to int(f, "203"))
    }

    fun collectRows(): Int = keyed("zhengshou").size

    fun vipCastle(vip: Long): JObj = rows("viplv").firstOrNull { int(it, "102", -1) == vip }?.let { f ->
        jobj("gold_bonus" to int(f, "113"), "honor_bonus" to int(f, "114"), "free" to int(f, "128"))
    } ?: jobj("gold_bonus" to 0, "honor_bonus" to 0, "free" to 0)

    fun vipMaxFriend(vip: Long): Long = rows("viplv").firstOrNull { int(it, "102", -1) == vip }?.let { int(it, "112") } ?: 0

    fun building(ident: Long): JObj? = keyed("building")[ident]?.let { f ->
        jobj("id" to ident, "upgradable" to (int(f, "103") == 1L), "cost_factor" to int(f, "106"), "unlock_level" to int(f, "109"),
            "max_level" to int(f, "110"))
    }

    fun buildings(): List<JObj> = keyed("building").keys.sorted().map { building(it)!! }

    fun technology(ident: Long): JObj? = keyed("technology")[ident]?.let { f ->
        jobj("id" to ident, "requirement" to int(f, "103"), "cost_factor" to int(f, "105"), "order" to int(f, "110"))
    }

    fun technologies(): List<JObj> = keyed("technology").keys.sorted().map { technology(it)!! }

    fun guildTech(ident: Long): JObj? = keyed("juntuan_technolegy")[ident]?.let { f ->
        jobj("id" to ident, "guild_only" to (int(f, "103") == 1L), "guild_cost" to int(f, "105"), "donate_factor" to int(f, "111"),
            "honor_factor" to int(f, "112"))
    }

    private var guildLevelsCache: List<JObj>? = null

    fun guildLevels(): List<JObj> = guildLevelsCache ?: rows("juntuan_dengji").filter { int(it, "102") != 0L }.map { f ->
        val seats = JObj(intKeys = true)
        for ((p, s) in listOf("104" to "105", "106" to "107", "108" to "109")) if (int(f, p) != 0L) seats[int(f, p).toString()] = JInt(int(f, s))
        jobj("level" to int(f, "102"), "shop" to int(f, "111"), "popularity" to int(f, "112"), "members" to int(f, "113"), "seats" to seats)
    }.sortedBy { it.long("level") }.also { guildLevelsCache = it }

    fun guildLevel(level: Long): JObj = guildLevels().firstOrNull { it.long("level") == level } ?: guildLevels().last()
    fun guildMaxLevel(): Long = guildLevels().last().long("level")
    fun guildShopUnlock(commodity: Long): Long? = guildLevels().filter { it.long("shop") == commodity }.minOfOrNull { it.long("level") }

    fun guildBadge(badge: Long): JObj? = keyed("juntuan_junhui")[badge]?.let { f ->
        jobj("id" to badge, "level" to int(f, "102"), "next" to int(f, "111"), "cost" to int(f, "103"), "metals" to int(f, "112"), "members" to int(f, "106"))
    }

    fun guildPosition(position: Long): JObj? = keyed("juntuan_quanxian")[position]?.let { f ->
        val row = JObj()
        for ((name, col) in GUILD_PERMISSIONS) row[name] = io.github.okexodus.openknights.exact.JBool(int(f, col) == 1L)
        row["position"] = JInt(position); row["name_text"] = JInt(int(f, "102")); row["leader_rule"] = io.github.okexodus.openknights.exact.JBool(int(f, "109") == 1L)
        row["apply_from"] = JInt(int(f, "111")); row["apply_contribution"] = JInt(int(f, "112")); row["salary"] = JInt(int(f, "115"))
        row["wage_item"] = JInt(int(f, "118")); row["wage_count"] = JInt(int(f, "119"))
        row
    }

    fun guildBoss(level: Long): JObj? = keyed("juntuan_boss", "102")[level]?.let { f ->
        jobj("level" to level, "hp" to (int(f, "104") * 1000 + int(f, "105") + int(f, "106") + int(f, "107")), "resources" to int(f, "108"),
            "reset_price" to int(f, "109"), "resets" to int(f, "110"))
    }

    fun guildTasks(): List<JObj> = keyed("quest_juntuan").keys.sorted().map { guildTask(it)!! }

    fun guildTask(ident: Long): JObj? = keyed("quest_juntuan")[ident]?.let { f ->
        jobj("id" to ident, "name_text" to int(f, "102"), "group" to int(f, "104"), "kind" to int(f, "106"), "category" to int(f, "107"),
            "required" to int(f, "108"), "exp" to int(f, "109"), "gold" to int(f, "110"), "contribution" to int(f, "111"),
            "item" to int(f, "113"), "item_count" to int(f, "114"))
    }

    fun guildTaskStars(): List<JObj> = keyed("questjuntuan_star").entries.sortedBy { it.key }.map { (i, f) ->
        jobj("star" to i, "multiplier" to int(f, "102"), "weight" to int(f, "103"))
    }

    fun guildTaskStar(star: Long): JObj = guildTaskStars().firstOrNull { it.long("star") == star } ?: guildTaskStars()[0]

    fun itemCategory(template: Long): Long = single("item", template)?.let { int(it, "215") } ?: 0

    fun alchemyRows(): List<JObj> = keyed("lianjin").entries.sortedBy { it.key }.map { (i, f) ->
        jobj("id" to i, "weight_a" to int(f, "102"), "gold" to int(f, "104"), "extra_gold" to int(f, "105"), "extra_item" to int(f, "106"),
            "extra_count" to int(f, "107"), "weight_b" to int(f, "108"))
    }

    /** `prop(key, default)`: property col 102 through `_int`. */
    fun prop(key: Any, default: Long = 0): Long = int(property(key) ?: "", default)

    fun donateItem(template: Long): JObj? {
        val row = single("item", template) ?: return null
        if (int(row, "209") != 1L) return null
        val cap = int(row, "212")
        return jobj("template" to template, "door_exp" to int(row, "210"), "essence" to int(row, "211"), "daily_cap" to (if (cap == 0L) 9999 else cap))
    }

    fun donateGoldCap(): Long = int(property(908) ?: "").let { if (it == 0L) 500 else it }

    fun royalDoorTask(task: Long): JObj? = rows("quest_yijiezhimen").firstOrNull { int(it, "102") == task }?.let { f ->
        jobj("task" to task, "essence_item" to int(f, "107"), "essence" to int(f, "103"), "door_exp" to int(f, "104"))
    }

    fun trainingRoom(row: Long): JObj? = keyed("xiuxing")[row]?.let { f ->
        jobj("row" to row, "type" to int(f, "102"), "seats" to int(f, "201"), "vip" to int(f, "203"), "create_price" to int(f, "204"),
            "boost" to int(f, "301"), "full_boost" to int(f, "302"), "creator_boost" to int(f, "303"), "lifetime" to int(f, "304"),
            "duration" to int(f, "401"), "add_time_price" to int(f, "501"), "min_level" to int(f, "502"), "max_level" to int(f, "503"),
            "exp_rate" to int(f, "601"), "honor_rate" to int(f, "602"))
    }

    fun trainingRooms(): List<JObj> = keyed("xiuxing").keys.sorted().map { trainingRoom(it)!! }

    fun titleBonus(title: Long): JObj {
        val f = keyed("title")[title]
        return jobj("hp" to (f?.let { int(it, "201") } ?: 0), "unique" to (f?.let { int(it, "202") } ?: 0), "atk" to (f?.let { int(it, "203") } ?: 0))
    }

    fun forgeExp(): Long = rows("qianchuibailian").firstOrNull()?.let { int(it, "103") } ?: 41_160

    private val gearExpCache = HashMap<Pair<Long, Long>, JObj>()
    private val jewelExpCache = HashMap<Pair<Long, Long>, JObj>()

    /** Gear cap / EXP curve / scale, the item-Fortify inputs (`pk_item_fortify_contract.gear_item_inputs`), cached. */
    open fun gearExp(template: Long, grade: Long): JObj =
        gearExpCache.getOrPut(template to grade) { PkItemFortifyContract.gearItemInputs(tables, template, grade) }

    /** Jewelry cap / EXP curve / scale (`pk_item_fortify_contract.jewelry_item_inputs`), cached. */
    open fun jewelExp(template: Long, grade: Long): JObj =
        jewelExpCache.getOrPut(template to grade) { PkItemFortifyContract.jewelryItemInputs(tables, template, grade) }

    fun exploreRow(ident: Long): JObj? = keyed("yingxiongyuanzheng")[ident]?.let { f ->
        val boxes = listOf(201, 205, 209).map { b ->
            jobj("kind" to int(f, b.toString()), "item" to int(f, (b + 1).toString()), "count" to int(f, (b + 2).toString()), "weight" to int(f, (b + 3).toString()))
        }
        jobj("id" to ident, "name_text" to int(f, "102"), "stars" to int(f, "103"), "gold_weight" to int(f, "104"), "diamond_weight" to int(f, "105"),
            "duration" to int(f, "106"), "cost_kind" to int(f, "107"), "cost_item" to int(f, "108"), "cost_count" to int(f, "109"),
            "hero_exp" to int(f, "110"), "boxes" to boxes, "robbed_weight" to int(f, "301"), "met_weight" to int(f, "302"), "gift_weight" to int(f, "303"))
    }

    fun exploreRows(): List<JObj> = keyed("yingxiongyuanzheng").keys.sorted().map { exploreRow(it)!! }

    /** text.csv col 102 of a text id ('' when absent or 0). */
    fun text(ident: Long): String = if (ident == 0L) "" else single("text", ident)?.field("102") ?: ""

    fun heroIsLeader(template: Long): Boolean {
        val row = if (template != 0L) single("hero", Math.floorDiv(template, 1000L)) else null
        return row != null && int(row, "143") != 0L
    }

    fun resetDiamondRow(key: Long): JObj? = keyed("resetdiamond", "701")[key]?.let { f ->
        jobj("key" to key, "a" to int(f, "704"), "b_sacrifice" to int(f, "705"), "c_sacrifice" to int(f, "706"),
            "b_reforge" to int(f, "707"), "c_reforge" to int(f, "708"))
    }

    open fun cardPropertyValue(kind: String, record: JArr): Long = throw NotPorted("DailyInputs.card_property_value")

    fun heroName(template: Long): String {
        val row = if (template != 0L) single("hero", Math.floorDiv(template, 1000L)) else null
        return row?.let { text(int(it, "140")) } ?: ""
    }

    fun arenaReward(rank: Long): JObj? {
        var best: Pair<Long, GameTable.Row>? = null
        for (f in rows("arena")) {
            val key = int(f, "101")
            if (key != 0L && key <= rank && (best == null || key > best.first)) best = key to f
        }
        val (key, f) = best ?: return null
        return jobj("key" to key, "gold" to int(f, "102"), "reputation" to int(f, "103"), "item" to int(f, "104"), "count" to int(f, "105"))
    }
}

@Suppress("unused")
private val unusedValue: JValue? = null
