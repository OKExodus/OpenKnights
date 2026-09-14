package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.gamedata.GameTables
import io.github.okexodus.openknights.server.game.HeroDictionaryProgression.unpackHeroId
import java.math.BigInteger

/**
 * The fresh-hero field map of `tools/pk_test_fixture_inject_hero.py` (`fresh_hero_fields`), the layout of a freshly
 * acquired hero in the live S32 frames: fields 0-18 plus 22 / 23 (no 19-21, no 24), level 1, EXP 0, growth = the
 * catalog recompute at the template's grade, stats = the shared model at level 1, field 12 = 1000, fields 22 / 23 =
 * hero 997 / 996 x 3 (the unreborn multiplier). The tool's save editing and capture self-check are not on a server path.
 */
object PkTestFixtureInjectHero {
    /** (id, tag) of the live S32 fresh-hero map. */
    val LAYOUT = listOf(0L to 6, 1L to 6, 2L to 4, 3L to 6, 4L to 6, 5L to 4, 6L to 6, 7L to 4, 8L to 6, 9L to 4, 10L to 6, 11L to 4,
        12L to 6, 13L to 6, 14L to 2, 15L to 6, 16L to 6, 17L to 6, 18L to 6, 22L to 6, 23L to 6)
    const val UNREBORN_MULTIPLIER = 3L

    /** `fresh_hero_fields(catalog, uid, template)`: the 21-field typed map of a new hero. */
    fun freshHeroFields(tables: GameTables, uid: Long, template: Long): JArr {
        val inputs = PkFortifyContract.heroStatInputs(tables, template)
        val grade = unpackHeroId(template).grade
        val full = HeroStats.recomputeGrow(inputs.arr("raw_511_514").map { it.long }, inputs.long("current_potential_rate"))
            .map { BigInteger.valueOf(it) }
        val permille = HeroStats.statPermille(template, inputs, BigInteger.ZERO)
        val stats = HeroStats.baseStats(full, BigInteger.ONE, grade, permille, List(4) { BigInteger.ZERO })
        val wireGrow = HeroStats.wireGrowBits(full, listOf(16, 16, 16, 16))
        val row = tables.lookup("hero", unpackHeroId(template).baseId.toString())[0]
        fun cell(key: String): BigInteger { val v = row.field(key); return if (v.isNullOrEmpty()) BigInteger.ZERO else PyValues.parseInt(v) }
        val addAtk = cell("997") * BigInteger.valueOf(UNREBORN_MULTIPLIER)
        val addDef = cell("996") * BigInteger.valueOf(UNREBORN_MULTIPLIER)
        val values = mapOf<Long, Any>(0L to uid, 1L to template, 2L to 1, 3L to 0, 4L to stats[0], 5L to wireGrow[0], 6L to stats[1],
            7L to wireGrow[1], 8L to stats[2], 9L to wireGrow[2], 10L to stats[3], 11L to wireGrow[3], 12L to 1000, 13L to 0, 14L to 0,
            15L to 0, 16L to 0, 17L to 0, 18L to 0, 22L to addAtk, 23L to addDef)
        return JArr(LAYOUT.mapTo(ArrayList()) { (id, tag) -> jobj("id" to id, "value" to jobj("tag" to tag, "bits" to values.getValue(id))) })
    }
}
