package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.F32
import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JBool
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.SplitMix64
import io.github.okexodus.openknights.exact.asLong
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.asStr
import io.github.okexodus.openknights.gamedata.ApkTables
import io.github.okexodus.openknights.gamedata.GameTables
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path

/**
 * Local only (OPENKNIGHTS_DEV_DIR with `vectors-g9/engine.json` from the maintainer's private exporter,
 * OPENKNIGHTS_ORIGINALS with the player's APK): the whole battle engine replayed against the reference's own outputs.
 * Every f32 bit, RNG draw, enemy side, damage value and full `simulate` S4 payload must be identical.
 */
class G9EngineVectorsTest {
    private val dev: Path? = System.getenv("OPENKNIGHTS_DEV_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
    private val originals: Path? = System.getenv("OPENKNIGHTS_ORIGINALS")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
    private val failures = ArrayList<String>()
    private var checks = 0

    private fun file(): Path? = dev?.resolve("vectors-g9")?.resolve("engine.json")
    private fun available(): Boolean = dev != null && originals != null && file()?.let { Files.isRegularFile(it) } == true
    private fun inputs() = DailyInputs(GameTables(ApkTables(originals!!.resolve("com.enjoygame.hero2d.apk"))))

    private fun check(label: String, expected: Any?, actual: Any?) {
        checks++
        if (expected != actual) failures.add("$label\n  expected: ${expected.toString().take(600)}\n  actual:   ${actual.toString().take(600)}")
    }

    private fun report() {
        println("engine: $checks checks, ${failures.size} failures")
        assertTrue(failures.isEmpty()) { "engine: ${failures.size} of $checks differ:\n" + failures.take(30).joinToString("\n") }
    }

    @Test
    fun `battle engine vectors`() {
        assumeTrue(available(), "OPENKNIGHTS_DEV_DIR / OPENKNIGHTS_ORIGINALS not set: local-only test skipped")
        val doc = Json.loads(Files.readString(file()!!)).asObj
        numeric(doc.obj("numeric"))
        rng(doc.obj("rng"))
        val inputs = inputs()
        enemy(doc.arr("enemy"), inputs)
        simulate(doc.arr("simulate"), inputs)
        report()
    }

    private fun numeric(n: JObj) {
        for ((i, v) in n.arr("f32").withIndex()) {
            val o = v.asObj
            check("f32 $i", o.long("out"), F32.round(Double.fromBits(o.long("in"))).toRawBits())
        }
        for ((i, v) in n.arr("pct").withIndex()) {
            val o = v.asObj
            check("pct $i", o.long("out"), BattleEngine.pct(o.long("amount"), o.long("bps")))
        }
        for ((i, v) in n.arr("enemy_stat").withIndex()) {
            val o = v.asObj
            check("enemy_stat $i", o.long("out"),
                BattleEngine.enemyStat(o.long("ma"), o.long("slot"), o.long("mult"), o.long("pot"), o.long("gift")))
        }
        for ((i, v) in n.arr("damage_value").withIndex()) {
            val o = v.asObj
            val stat = Double.fromBits(o.long("stat"))
            val defense = Double.fromBits(o.long("defense"))
            check("damage floor $i", o.long("floor"),
                BattleEngine.damageValue(stat, defense, o.long("class_att"), o.long("class_def"), o.bool("player"),
                    o.bool("special"), o.long("outcome"), o.long("coef")).toLong())
            check("damage raw $i", o.long("raw"),
                BattleEngine.damageValue(stat, defense, o.long("class_att"), o.long("class_def"), o.bool("player"),
                    o.bool("special"), o.long("outcome"), o.long("coef"), unrounded = true).toRawBits())
        }
        for ((i, v) in n.arr("lifesteal").withIndex()) {
            val o = v.asObj
            check("lifesteal $i", o.long("out"), BattleEngine.lifesteal(Double.fromBits(o.long("dealt")), o.long("bps")))
        }
    }

    private fun rng(r: JObj) {
        for ((i, v) in r.arr("next").withIndex()) {
            val o = v.asObj
            val g = SplitMix64(BigInteger(o.str("seed")))
            val out = o.arr("out").map { it.asStr }
            check("next $i", out, (0 until out.size).map { g.nextU64().toString() })
        }
        for ((i, v) in r.arr("below").withIndex()) {
            val o = v.asObj
            val g = SplitMix64(BigInteger(o.str("seed")))
            val ns = o.arr("ns").map { it.asLong }
            check("below $i", o.arr("out").map { it.asLong }, ns.map { g.below(it) })
        }
        for ((i, v) in r.arr("chance").withIndex()) {
            val o = v.asObj
            val g = SplitMix64(BigInteger(o.str("seed")))
            val bps = o.arr("bps").map { it.asLong }
            check("chance $i", o.arr("out").map { (it as JBool).value }, bps.map { g.chance(it) })
        }
        for ((i, v) in r.arr("battle_seed").withIndex()) {
            val o = v.asObj
            val parts: Array<String> = o.arr("parts").map { it.asStr }.toTypedArray()
            check("battle_seed $i", o.str("seed"), java.lang.Long.toUnsignedString(BattleEngine.battleSeed(*parts)))
        }
    }

    private fun enemy(vectors: JArr, inputs: DailyInputs) {
        for ((i, v) in vectors.withIndex()) {
            val o = v.asObj
            val sid = o.long("stage_id")
            try {
                check("enemy $i C$sid", o["side"], BattleEngine.enemyActors(sid, inputs))
            } catch (e: Exception) {
                failures.add("enemy $i C$sid: ${e::class.simpleName}: ${e.message}")
            }
        }
    }

    private fun simulate(vectors: JArr, inputs: DailyInputs) {
        for ((i, v) in vectors.withIndex()) {
            val o = v.asObj
            val expected = o.obj("result")
            if (expected.containsKey("error")) continue
            val sid = o.long("stage_id")
            val seed = BigInteger(o.str("seed")).toLong()
            val lineup = o.arr("lineup").toList()
            val label = "simulate $i C$sid"
            try {
                val out = BattleEngine.campaignBattle(sid, lineup, { e -> e.asObj.obj("stats") }, inputs, seed, o.str("own_name"))
                check("$label payload", expected.str("payload"), out.str("payload"))
                check("$label result", expected.long("result"), out.long("result"))
                check("$label stars", expected.long("stars"), out.long("stars"))
                check("$label rounds", expected.long("rounds"), out.long("rounds"))
                check("$label value_231", expected.long("value_231"), out.long("value_231"))
                check("$label value_232", expected.long("value_232"), out.long("value_232"))
                check("$label timed_out", (expected["timed_out"] as JBool).value, (out["timed_out"] as JBool).value)
                check("$label final", expected["final"], out["final"])
                check("$label report", expected["report"], out["report"])
                check("$label seed", expected.str("seed"), (out["seed"] as JInt).value.toString())
            } catch (e: Exception) {
                failures.add("$label: ${e::class.simpleName}: ${e.message}")
            }
        }
    }
}
