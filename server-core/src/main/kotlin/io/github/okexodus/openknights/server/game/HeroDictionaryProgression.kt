package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.F32
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JNull
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.PyText
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.gamedata.GameTable
import io.github.okexodus.openknights.gamedata.GameTables
import io.github.okexodus.openknights.gamedata.SupportedInput
import java.math.BigInteger

/**
 * The hero-progression helpers of `tools/hero_dictionary_progression.py` that the server paths use: the client's
 * bounded `atoi` over table cells, binary32 rounding, the packed hero id and the evolve-row selectors
 * (`configured_states`, `potential_rows`). The client-preview functions of that module (basic attribute, EXP and
 * development previews, dictionary tables) are not on a server path and are not ported.
 */
object HeroDictionaryProgression {
    const val U32 = 0xFFFFFFFFL
    val STAT_FIELDS = listOf("511", "512", "513", "514")
    val STAT_NAMES = listOf("hp", "attack", "defense", "unique")

    private val INT32_MIN = BigInteger.valueOf(Int.MIN_VALUE.toLong())
    private val INT32_END = BigInteger.ONE.shiftLeft(31)

    /**
     * `native_int(raw)`: the decimal prefix `^\s*([+-]?\d+)` (the reference's Unicode whitespace and decimal digits)
     * as an integer, 0 when there is none (like `atoi`); a value outside the signed 32-bit range is refused.
     */
    fun nativeInt(raw: String): Long {
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            if (!PyText.isSpace(cp)) break
            i += Character.charCount(cp)
        }
        val start = i
        if (i < raw.length && (raw[i] == '+' || raw[i] == '-')) i++
        val digitsStart = i
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            if (!PyText.isDecimal(cp)) break
            i += Character.charCount(cp)
        }
        val value = if (i > digitsStart) PyValues.parseInt(raw.substring(start, i)) else BigInteger.ZERO
        if (value < INT32_MIN || value >= INT32_END) throw PyValues.ValueError("atoi input outside bounded signed 32-bit domain")
        return value.toLong()
    }

    /** `_n(row, field)`: [nativeInt] of the row's cell (empty when the table has no such field). */
    fun n(row: GameTable.Row, field: Any): Long = nativeInt(row.field(field.toString()) ?: "")

    /** `_byte(row, field)`: the low 8 bits of [n]. */
    fun byte(row: GameTable.Row, field: Any): Long = n(row, field) and 255

    /** `f32(value)`: one arithmetic result rounded to binary32; a non-finite result is refused. */
    fun f32(value: Double): Double {
        val result = F32.round(value)
        if (!result.isFinite()) throw PyValues.ValueError("non-finite float32 result outside preview domain")
        return result
    }

    /** `_trunc_u32(value)`: FCVTZU of a value inside [0, 2^32). */
    fun truncU32(value: Double): Long = F32.truncU32(value)

    /** The parts of a packed hero id (`unpack_hero_id`): base id, grade (% 100), hundreds digit and super class. */
    data class HeroId(val baseId: Long, val grade: Long, val hundredsDigit: Long, val superClass: Long) {
        fun toJson(): JObj = jobj("base_id" to baseId, "grade" to grade, "hundreds_digit" to hundredsDigit, "super_class" to superClass)
    }

    fun unpackHeroId(packedId: BigInteger): HeroId {
        if (packedId.signum() < 0 || packedId > BigInteger.valueOf(U32)) throw PyValues.ValueError("packed hero ID must be uint32")
        val p = packedId.toLong()
        val digit = (p / 100) % 10
        return HeroId(p / 1000, p % 100, digit, digit % 3)
    }

    fun unpackHeroId(packedId: Long): HeroId = unpackHeroId(BigInteger.valueOf(packedId))

    /**
     * `configured_states(catalog, hero_row)`: every evolve row the cap selector accepts for this hero (leader:
     * `jinhuazhujue` by category and super class 0..2; ordinary: `jinhua` by category and group), grade <= 99, sorted by
     * (grade, super class, CSV row). Each state carries its catalog row (`row`), as the reference's dictionaries do.
     */
    fun configuredStates(tables: GameTables, heroRow: GameTable.Row): List<JObj> {
        val leader = byte(heroRow, "143") != 0L
        val tableName = if (leader) "jinhuazhujue" else "jinhua"
        val result = ArrayList<JObj>()
        for (row in tables.table(tableName).rows) {
            if (byte(row, "102") != byte(heroRow, "104")) continue
            if (!leader && n(row, "104") != n(heroRow, "132")) continue
            if (byte(row, "103") > 99) continue
            val superClass: Long? = if (leader) byte(row, "104") else null
            if (leader && superClass!! > 2) continue
            result.add(jobj(
                "grade" to byte(row, "103"), "super_class" to superClass,
                "branch" to (if (leader) "leader" else "ordinary"), "row" to CatalogShapes.row(row),
                "row_ref" to CatalogShapes.ref(row), "cap" to (n(row, "502") and U32),
                "selector" to jobj(
                    "hero104_raw" to (heroRow.field("104") ?: ""),
                    "hero132_raw" to (heroRow.field("132") ?: ""),
                    "hero143_raw" to (heroRow.field("143") ?: ""),
                    "table102_raw" to (row.field("102") ?: ""),
                    "table103_raw" to (row.field("103") ?: ""),
                    "table104_raw" to (row.field("104") ?: ""),
                    "predicate" to (if (leader) "byte102=hero104; byte103=packed%100; byte104=((packed//100)%10)%3"
                        else "byte102=hero104; int104=hero132; byte103=packed%100")),
                "status" to "configured", "reachability" to "unknown"))
        }
        return result.sortedWith(compareBy<JObj>({ it.long("grade") }, { (it["super_class"] as? JInt)?.value?.toLong() ?: 0L },
            { it.obj("row_ref").long("csv_row") }))
    }

    /** `potential_rows(catalog, hero_row, grade)`: the earlier-grade rate rows (a leader's sum ignores the class). */
    fun potentialRows(tables: GameTables, heroRow: GameTable.Row, grade: Long): List<GameTable.Row> {
        val leader = byte(heroRow, "143") != 0L
        val category = byte(heroRow, "104")
        val group = n(heroRow, "132")
        return tables.table(if (leader) "jinhuazhujue" else "jinhua").rows.filter { row ->
            byte(row, "102") == category && byte(row, "103") < grade && (leader || n(row, "104") == group)
        }
    }
}

/**
 * The shapes the reference's catalog (`hero_dictionary_common.Catalog` over `apk_tables.ApkTables`) gives a row and a
 * table source, for the input dictionaries that embed them.
 */
object CatalogShapes {
    const val LAYER = "apk-4.4.9"
    const val CONFIG_PREFIX = "assets/data/config/"

    /** `row["ref"]`. */
    fun ref(row: GameTable.Row): JObj =
        jobj("file" to row.table, "layer" to LAYER, "source_id" to "${row.table}@$LAYER", "csv_row" to row.csvRow)

    /** The whole catalog row: `{"ref", "key", "cells", "fields"}`. */
    fun row(row: GameTable.Row): JObj {
        val fields = JObj()
        for ((k, v) in row.fields()) fields[k] = JStr(v)
        return jobj("ref" to ref(row), "key" to row.key, "cells" to row.cells, "fields" to fields)
    }

    /** `catalog.table(name)["source"]` of the release table source. */
    fun source(tables: GameTables, name: String): JObj {
        val table = tables.table(name)
        val file = table.name
        return jobj("file" to file, "layer" to LAYER, "source_id" to "$file@$LAYER", "priority" to 0,
            "path" to "apk:$CONFIG_PREFIX$file", "sha256" to (SupportedInput.bundled.tables[file] ?: JNull),
            "records" to table.rows.size, "headers" to table.headers)
    }
}
