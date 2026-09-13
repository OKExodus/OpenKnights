package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JBool
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JNull
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.asArr
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.hexBytes
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.jvalue
import io.github.okexodus.openknights.exact.toHexString
import io.github.okexodus.openknights.gamedata.ApkTables
import io.github.okexodus.openknights.gamedata.GameTables
import io.github.okexodus.openknights.server.DeviceClock
import io.github.okexodus.openknights.server.store.StateStore
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path

/**
 * Local only (OPENKNIGHTS_DEV_DIR + OPENKNIGHTS_ORIGINALS): replays the private group-1 Power vectors — every
 * character save of the recorded roots and systematic variations, run through the reference — against this port with
 * the player's APK tables, and requires JSON / byte equality (errors: the same refusal, message and code).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class G1PowerVectorsTest {
    private lateinit var dir: Path
    private lateinit var tables: GameTables
    private lateinit var inputs: DailyInputs

    @BeforeAll
    fun setUp() {
        val dev = System.getenv("OPENKNIGHTS_DEV_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        val originals = System.getenv("OPENKNIGHTS_ORIGINALS")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        assumeTrue(dev != null && originals != null && Files.isDirectory(dev.resolve("vectors-g1")),
            "OPENKNIGHTS_DEV_DIR / OPENKNIGHTS_ORIGINALS not set: local-only test skipped")
        dir = dev!!.resolve("vectors-g1")
        tables = GameTables(ApkTables(originals!!.resolve("com.enjoygame.hero2d.apk")))
        inputs = DailyInputs(tables)
    }

    // --- replay helpers -----------------------------------------------------------------------------------------------

    private fun load(name: String): JObj = Json.loads(Files.readAllBytes(dir.resolve("$name.json"))).asObj

    private class Tally(val what: String) {
        var checked = 0
        val failures = ArrayList<String>()
        fun fail(label: String, detail: String) { failures.add("$label: $detail") }
        fun done() {
            println("$what: ${checked - failures.size}/$checked match")
            if (failures.isNotEmpty()) fail<Unit>("$what: ${failures.size} of $checked differ:\n  " + failures.take(12).joinToString("\n  ") { it.take(1500) })
        }
    }

    private sealed class Outcome {
        class Ok(val value: JValue) : Outcome()
        class Err(val e: Throwable) : Outcome()
        class Unch(val u: SweepFeatures.Unchanged) : Outcome()
    }

    private fun outcome(block: () -> Any?): Outcome = try {
        Outcome.Ok(jvalue(block()))
    } catch (u: SweepFeatures.Unchanged) {
        Outcome.Unch(u)
    } catch (e: Exception) {
        Outcome.Err(e)
    } catch (e: StackOverflowError) {
        Outcome.Err(e)
    }

    private fun codeOf(e: Throwable): Int? = when (e) {
        is Acquisition.Rejected -> e.code
        is HeroStats.ProfileUnsupported -> e.code
        is HeroEvolution.EvolutionRejected -> e.code
        else -> null
    }

    private fun text(v: JValue?): String = Json.compact(v ?: JNull)

    private fun framesJson(frames: List<Frame>): JArr = JArr(frames.mapTo(ArrayList()) { jarr(it.first, it.second.toHexString()) })

    /** Compares one reference `run(...)` record with the port's outcome. */
    private fun Tally.check(label: String, expected: JValue?, actual: Outcome, normalize: (JValue) -> JValue = { it }) {
        checked++
        val exp = expected as? JObj ?: return fail(label, "no expectation")
        when {
            "result" in exp -> when (actual) {
                is Outcome.Ok -> { val a = text(normalize(actual.value)); val b = text(exp["result"]); if (a != b) fail(label, "expected $b but was $a") }
                is Outcome.Err -> fail(label, "expected ${text(exp["result"]).take(300)} but threw ${actual.e}")
                is Outcome.Unch -> fail(label, "expected a result but was unchanged")
            }
            "unchanged" in exp -> when (actual) {
                is Outcome.Unch -> {
                    val a = text(jobj("packets" to framesJson(actual.u.packets), "fields" to actual.u.fields))
                    val b = text(exp["unchanged"])
                    if (a != b) fail(label, "expected unchanged $b but was $a")
                }
                is Outcome.Ok -> fail(label, "expected unchanged but was ${text(actual.value).take(300)}")
                is Outcome.Err -> fail(label, "expected unchanged but threw ${actual.e}")
            }
            else -> when (actual) {
                is Outcome.Err -> {
                    val valueError = (exp["value_error"] as? JBool)?.value == true
                    if (valueError) {
                        if (actual.e !is IllegalArgumentException) fail(label, "expected ValueError ${exp["message"]} but threw ${actual.e}")
                        else if (actual.e.message != (exp["message"] as JStr).value) fail(label, "expected message ${exp["message"]} but was ${actual.e.message}")
                        val code = (exp["code"] as? JInt)?.value?.toInt()
                        if (code != null && codeOf(actual.e) != code) fail(label, "expected code $code but was ${codeOf(actual.e)}")
                    }   // struct.error / IndexError / KeyError / TypeError / OverflowError of the reference: any failure here
                }
                is Outcome.Ok -> fail(label, "expected ${exp["error"]}: ${exp["message"]} but was ${text(actual.value).take(300)}")
                is Outcome.Unch -> fail(label, "expected ${exp["error"]} but was unchanged")
            }
        }
    }

    private fun Tally.same(label: String, expected: JValue?, actual: JValue?) {
        checked++
        val a = text(actual)
        val b = text(expected)
        if (a != b) fail(label, "expected $b but was $a")
    }

    private fun current(input: JObj): StateStore.Current {
        val docs = LinkedHashMap<String, JValue?>()
        for ((k, v) in input.obj("documents")) docs[k] = if (v == JNull) null else v.deepCopy()
        fun objOrNull(v: JValue?): JObj? = (v as? JObj)?.deepCopy()
        return StateStore.Current(1, "", "", input.obj("state").deepCopy(), ByteArray(0), 0, null,
            input.arr("inventory_items").deepCopy(), emptyList(), input.arr("acquired_items").deepCopy(), emptyList(), null, null,
            objOrNull(input["god_skills"]), objOrNull(input["character_profile"]), null, docs)
    }

    private fun freshSystems(document: JObj): Map<Int, List<ByteArray>> {
        val out = LinkedHashMap<Int, List<ByteArray>>()
        for ((k, v) in document.obj("fresh_systems")) out[k.toInt()] = v.asArr.map { (it as JStr).value.hexBytes() }
        return out
    }

    private fun parseFloat(text: String): Double = when (text) {
        "inf" -> Double.POSITIVE_INFINITY; "-inf" -> Double.NEGATIVE_INFINITY; "nan" -> Double.NaN
        else -> java.lang.Double.parseDouble(text)
    }

    private fun hexFloat(x: Double): String = java.lang.Double.toHexString(x)

    // --- battle_stats -------------------------------------------------------------------------------------------------

    @Test
    fun `battle stats and the universal Power`() {
        val doc = load("battle_stats")
        val tally = Tally("battle_stats")
        tally.same("battle_stat_tables", doc["battle_stat_tables"], inputs.battleStatTables())
        val fresh = freshSystems(doc)
        val leaderInputs = EvolutionInputs(tables)
        for (c in doc.arr("cases")) {
            val case = c.asObj
            val name = case.str("name")
            val input = case.obj("input")
            val expected = case.obj("expected")
            val payloads = outcome { framesJson(BattleStats.statPayloadsOf(fresh, leaderInputs, current(input)).map { it.key to it.value }) }
            if (expected["payloads"] is JArr) tally.check("$name payloads", jobj("result" to expected["payloads"]), payloads)
            else tally.check("$name payloads", expected["payloads"], payloads)
            val world = outcome { BattleStats.statWorldOf(fresh, leaderInputs, current(input)) }
            tally.check("$name world", expected["world"], world)
            if ("team_power" in expected && world is Outcome.Ok) {
                val cur = current(input)
                tally.check("$name team_power", expected["team_power"], outcome { BattleStats.teamPower(cur.state, inputs, world.value as JObj) })
            }
            tally.check("$name power", expected["power"], outcome { BattleStats.powerOf(fresh, leaderInputs, current(input), inputs) })
            tally.same("$name participant_power", expected["participant_power"], jvalue(BattleStats.participantPower(fresh, leaderInputs, inputs)(current(input))))
        }
        for (c in doc.arr("extra")) {
            val case = c.asObj
            val args = case["args"]
            when (case.str("kind")) {
                "combat" -> { val a = args!!.asArr.map { it.long }; tally.same("combat $a", case["expected"], JInt(BattleStats.combat(a[0], a[1], a[2], a[3], a[4], a[5]))) }
                "fmadd32" -> {
                    val a = args!!.asArr.map { parseFloat((it as JStr).value) }
                    tally.checked++
                    val got = BattleStats.fmadd32(a[0], a[1], a[2])
                    val want = parseFloat(case.str("expected"))
                    if (got.toRawBits() != want.toRawBits()) tally.fail("fmadd32 $a", "expected ${hexFloat(want)} but was ${hexFloat(got)}")
                }
                "city_bar" -> tally.same("city_bar $args", case["expected"], JInt(BattleStats.cityBarDisplay(args!!.asArr[0].long)))
                "world_from_payloads" -> {
                    val payloads = LinkedHashMap<Int, ByteArray>()
                    for ((k, v) in args!!.asObj) payloads[k.toInt()] = (v as JStr).value.hexBytes()
                    tally.check("world_from_payloads $args", case["expected"], outcome { BattleStats.worldFromPayloads(payloads) })
                }
                "decode_totem_init" -> tally.check("decode_totem_init $args", case["expected"],
                    outcome { BattleStats.decodeTotemInit((args!!.asArr[0] as JStr).value.hexBytes()) })
            }
        }
        for (c in doc.arr("partial_worlds")) {
            val case = c.asObj
            val name = case.str("name")
            val world = case["world"].let { if (it == null || it == JNull) null else it as JObj }
            tally.check("$name partial team_power ${text(world)}", case["team_power"],
                outcome { BattleStats.teamPower(case.obj("state").deepCopy(), inputs, world?.deepCopy()) })
            tally.check("$name partial lineup_battle ${text(world)}", case["lineup_battle"],
                outcome { BattleStats.lineupStats(case.obj("state").deepCopy(), inputs, world?.deepCopy(), mode = "battle") })
            val uids = BattleStats.heroFields(case.obj("state")).keys.take(3)
            for ((i, uid) in uids.withIndex()) {
                tally.check("$name hero_ability $uid", case.arr("hero_ability")[i],
                    outcome { BattleStats.heroAbility(case.obj("state").deepCopy(), uid, inputs, world?.deepCopy()) })
            }
        }
        tally.done()
    }

    // --- evolution / fortify / progression / equip inputs --------------------------------------------------------------

    @Test
    fun `evolution contract inputs, leader info and leader digit`() {
        val tally = Tally("pk_evolution_contract")
        for (c in load("pk_evolution_contract").arr("cases")) {
            val case = c.asObj
            when (case.str("kind")) {
                "evolution_inputs" -> { val t = case.long("template"); tally.check("evolution_inputs $t", case["expected"], outcome { PkEvolutionContract.evolutionInputs(tables, t) }) }
                "leader_digit" -> { val t = case.long("template"); val r = case.bool("super_reached")
                    tally.check("leader_digit $t $r", case["expected"], outcome { PkEvolutionContract.leaderDigit(tables, t, r) }) }
                "leader_info" -> {
                    val heroes = LinkedHashMap<Long, JArr>()
                    for (h in case.arr("heroes")) heroes[h.asArr[0].long] = h.asArr[1].asArr
                    tally.check("leader_info ${case.str("save")}", case["expected"], outcome { PkEvolutionContract.leaderInfo(tables, heroes) })
                }
            }
        }
        tally.done()
    }

    @Test
    fun `fortify contract hero stat inputs`() {
        val tally = Tally("pk_fortify_contract")
        for (c in load("pk_fortify_contract").arr("cases")) {
            val case = c.asObj
            val t = case.long("template")
            tally.check("hero_stat_inputs $t", case["expected"], outcome { PkFortifyContract.heroStatInputs(tables, t) })
            tally.check("inputs.hero_stat_inputs $t", case["acquisition"], outcome { inputs.heroStatInputs(t) })
        }
        tally.done()
    }

    @Test
    fun `hero dictionary progression helpers`() {
        val tally = Tally("hero_dictionary_progression")
        val heroRows = tables.table("hero").rows
        for (c in load("hero_dictionary_progression").arr("cases")) {
            val case = c.asObj
            val args = case["args"]?.asArr
            when (case.str("kind")) {
                "native_int" -> tally.check("native_int ${args!![0]}", case["expected"], outcome { HeroDictionaryProgression.nativeInt((args[0] as JStr).value) })
                "f32" -> {
                    val x = parseFloat((args!![0] as JStr).value)
                    val exp = case.obj("expected")
                    val got = outcome { HeroDictionaryProgression.f32(x) }
                    if ("result" in exp) {
                        tally.checked++
                        val want = parseFloat(exp.str("result"))
                        if (got !is Outcome.Ok || (got.value as io.github.okexodus.openknights.exact.JFloat).value.toRawBits() != want.toRawBits()) tally.fail("f32 $x", "expected $want")
                    } else tally.check("f32 $x", exp, got)
                }
                "trunc_u32" -> { val x = parseFloat((args!![0] as JStr).value); tally.check("trunc_u32 $x", case["expected"], outcome { HeroDictionaryProgression.truncU32(x) }) }
                "unpack_hero_id" -> { val v = args!![0].long; tally.check("unpack_hero_id $v", case["expected"], outcome { HeroDictionaryProgression.unpackHeroId(v).toJson() }) }
                "configured_states" -> {
                    val row = heroRows[case.long("csv_row").toInt() - 2]
                    val full = case.bool("full")
                    val states = HeroDictionaryProgression.configuredStates(tables, row).map { s -> if (full) s else JObj(LinkedHashMap(s.filterKeys { it != "row" })) }
                    tally.same("configured_states ${row.key}", case["expected"], JArr(states.toMutableList()))
                }
                "potential_rows" -> {
                    val row = heroRows[case.long("csv_row").toInt() - 2]
                    val refs = HeroDictionaryProgression.potentialRows(tables, row, case.long("grade")).map { CatalogShapes.ref(it) }
                    tally.same("potential_rows ${row.key} ${case.long("grade")}", case["expected"], JArr(refs.toMutableList()))
                }
            }
        }
        tally.done()
    }

    @Test
    fun `equip evolve contract inputs and item property values`() {
        val tally = Tally("pk_equip_evolve_contract + equip_evolve")
        for (c in load("pk_equip_evolve_contract").arr("cases")) {
            val case = c.asObj
            val kind = case.str("kind"); val t = case.long("template"); val g = case.long("grade")
            tally.check("$kind $t $g", case["expected"], outcome {
                if (kind == "gear") PkEquipEvolveContract.gearEvolveInputs(tables, t, g) else PkEquipEvolveContract.jewelEvolveInputs(tables, t, g) })
            tally.check("property_value_inputs $kind $t $g", case["acquisition"], outcome { inputs.propertyValueInputs(kind, t, g) })
        }
        for (c in load("equip_evolve").arr("cases")) {
            val case = c.asObj
            val a = case.obj("args")
            fun v(k: String) = a.long(k)
            tally.check("${case.str("kind")} ${text(a)}", case["expected"], outcome {
                if (case.str("kind") == "equip") EquipEvolve.equipPropertyValue(v("level"), v("super_flag"), v("extra"), v("base_108"), v("growth_109"), v("potential"), v("property_912"))
                else EquipEvolve.jewelPropertyValue(v("level"), v("super_flag"), v("extra"), v("base_108"), v("ratio_110"), v("potential"), v("property_956"))
            })
        }
        tally.done()
    }

    // --- hero stats / hero evolution ------------------------------------------------------------------------------------

    @Test
    fun `hero stat model`() {
        val tally = Tally("hero_stats")
        val evolution = EvolutionInputs(tables)
        val acquisitionReject = HeroStats.Reject { m, code -> Acquisition.Rejected(m, code) }
        val evolutionReject = HeroStats.Reject { m, code -> HeroEvolution.EvolutionRejected(m, code) }
        for (c in load("hero_stats").arr("cases")) {
            val case = c.asObj
            when (case.str("kind")) {
                "resolve_profile" -> {
                    val template = (case["template"] as? JInt)?.value?.toLong()
                    val fields = case.arr("fields")
                    val label = "resolve_profile ${case.str("save")} $template ${case.str("inputs_from")}"
                    if (case.str("inputs_from") == "evolution") {
                        val ev = try { template?.let { evolution(it) } } catch (e: Exception) { null }
                        tally.check(label, case["expected"], outcome { HeroStats.resolveProfile(fields.deepCopy(), ev, if ("expected_evolution_reject" in case) acquisitionReject else HeroStats.PROFILE_UNSUPPORTED) })
                        val evExp = case["expected_evolution_reject"]
                        if (evExp != null && evExp != JNull) tally.check("$label evolution", evExp, outcome { HeroStats.resolveProfile(fields.deepCopy(), ev, evolutionReject, 1014) })
                    } else {
                        val si = try { template?.let { inputs.heroStatInputs(it) } } catch (e: Exception) { null }
                        tally.check(label, case["expected"], outcome { HeroStats.resolveProfile(fields.deepCopy(), si) })
                    }
                }
                "recompute_grow" -> tally.check("recompute_grow ${case["raw"]} ${case["rate"]}", case["expected"],
                    outcome { HeroStats.recomputeGrow(case.arr("raw").map { it.long }, case.long("rate")) })
                "base_stats" -> {
                    val a = case.arr("args")
                    tally.check("base_stats ${text(a)}", case["expected"], outcome {
                        HeroStats.baseStats(a[0].asArr.map { it.big }, a[1].big, a[2].long, a[3].asArr.map { it.long }, a[4].asArr.map { it.big }) })
                }
                "awaken_permille" -> { val a = case.arr("args"); tally.check("awaken_permille ${a[0]}", case["expected"], outcome { HeroStats.awakenPermille(a[0].big, a[1] as JArr) }) }
                "stat_permille" -> { val a = case.arr("args"); tally.check("stat_permille ${text(a)}", case["expected"], outcome { HeroStats.statPermille(a[0].long, a[1] as JObj, a[2].big) }) }
            }
        }
        tally.done()
    }

    @Test
    fun `hero evolution parts`() {
        val tally = Tally("hero_evolution")
        for (c in load("hero_evolution").arr("cases")) {
            val case = c.asObj
            val a = case.arr("args")
            when (case.str("kind")) {
                "leader_info_payload" -> tally.check("leader_info_payload ${text(a)}", case["expected"],
                    outcome { HeroEvolution.leaderInfoPayload(a[0].long, a[1].long).toHexString() })
                "check_test_policy" -> tally.check("check_test_policy ${text(a)}", case["expected"], outcome { HeroEvolution.checkTestPolicy(a[0]) })
            }
        }
        tally.done()
    }

    // --- leader repair ---------------------------------------------------------------------------------------------------

    @Test
    fun `leader digit login repair`() {
        val tally = Tally("leader_repair")
        val evolution = EvolutionInputs(tables)
        for (c in load("leader_repair").arr("cases")) {
            val case = c.asObj
            if ("pairs" in case) {
                val pairs = case.arr("pairs").map { p -> val a = p.asArr; (a[0] as? JInt)?.value?.toLong() to (a[1] as? JInt)?.value?.toLong() }
                tally.same("super_reached_templates", case["expected"], jvalue(LeaderRepair.superReachedTemplates(pairs).sorted()))
                continue
            }
            val name = "${case.str("name")} ${case.str("variant")}"
            val input = case.obj("input")
            val pairs = input.arr("history_pairs").map { p -> val a = p.asArr; (a[0] as? JInt)?.value?.toLong() to (a[1] as? JInt)?.value?.toLong() }
            tally.same("$name super_reached", case["super_reached_templates"], jvalue(LeaderRepair.superReachedTemplates(pairs).sorted()))
            val reached = case.arr("reached").map { it.long }.toSet()
            tally.check("$name repair_target", case["repair_target"], outcome {
                LeaderRepair.repairTarget(Owned(current(input), inputs), evolution, reached)?.let { jarr(it.second, it.third) } })
            val cur = current(input)
            tally.check("$name plan", case["plan"], outcome { LeaderRepair.planRepair(Owned(cur, inputs), evolution, reached).data })
            if (case["heroes_after"] != null && case["heroes_after"] != JNull) tally.same("$name heroes_after", case["heroes_after"], cur.state["heroes"])
        }
        tally.done()
    }

    // --- sweep features -----------------------------------------------------------------------------------------------------

    @Test
    fun `sweep features login frames and the warehouse repair`() {
        val doc = load("sweep_features")
        val tally = Tally("sweep_features")
        val seeds = SystemSeeds.SeedFrames(freshSystems(load("battle_stats")), "fresh_systems_template")
        val nows = listOf(1_757_000_000L, 1_757_030_399L, 1_760_000_000L)
        val offsets = listOf(0, -18000, 19800)
        fun startup(s: SweepFeatures.StartupFrames) = jobj("totems" to s.totems.toHexString(), "album" to s.album.toHexString(), "provenance" to s.provenance)
        fun planJson(p: Plan) = jobj("plan" to p.data, "packets" to framesJson(p.packets))
        for (c in doc.arr("cases")) {
            val case = c.asObj
            val name = case.str("name")
            val input = case.obj("input")
            val e = case.obj("expected")
            tally.check("$name startup_frames", e["startup_frames"], outcome { startup(SweepFeatures.startupFrames(current(input), seeds)) })
            tally.check("$name startup_frames_no_seeds", e["startup_frames_no_seeds"], outcome { startup(SweepFeatures.startupFrames(current(input), null)) })
            tally.check("$name album_document", e["album_document"], outcome { SweepFeatures.albumDocument(current(input), seeds) })
            tally.check("$name totem_document", e["totem_document"], outcome { SweepFeatures.totemDocument(current(input), seeds) })
            tally.check("$name tmp_vip_document", e["tmp_vip_document"], outcome { SweepFeatures.tmpVipDocument(current(input), seeds) })
            tally.same("$name real_vip_level", e["real_vip_level"], JInt(SweepFeatures.realVipLevel(current(input))))
            for ((i, now) in nows.withIndex()) {
                tally.check("$name tmp_vip_frame $now", e.arr("tmp_vip_frames")[i], outcome { SweepFeatures.tmpVipFrame(current(input), seeds, now).let { jarr(it.first, it.second.toHexString()) } })
            }
            var k = 0
            for (vip in listOf(0L, 5L)) for (now in nows) {
                tally.check("$name tmp_vip_level $vip $now", e.arr("tmp_vip_level")[k++], outcome { SweepFeatures.tmpVipLevel(current(input), vip, now, seeds) })
            }
            for ((oi, offset) in offsets.withIndex()) {
                val previous = DeviceClock.active
                DeviceClock.active = DeviceClock(null, { nows[0] }, { offset })
                try {
                    for ((ni, now) in nows.withIndex()) {
                        tally.check("$name after_query $offset $now", e.arr("after_query_frames")[oi].asArr[ni],
                            outcome { framesJson(SweepFeatures.afterQueryFrames(inputs, current(input), now)) })
                    }
                } finally { DeviceClock.active = previous }
            }
            for (d in e.arr("after_query_frames_doc_day")) {
                val day = d.asObj
                val offset = day.long("offset").toInt()
                val now = day.long("now")
                val previous = DeviceClock.active
                DeviceClock.active = DeviceClock(null, { now }, { offset })
                try {
                    tally.check("$name after_query on its day $offset $now", day["expected"], outcome { framesJson(SweepFeatures.afterQueryFrames(inputs, current(input), now)) })
                } finally { DeviceClock.active = previous }
            }
            tally.check("$name capacity_repair_needed", e["capacity_repair_needed"], outcome { SweepFeatures.capacityRepairNeeded(current(input).state, inputs) })
            val cur = current(input)
            tally.check("$name plan_capacity_repair", e["plan_capacity_repair"], outcome { planJson(SweepFeatures.planCapacityRepair(Owned(cur, inputs), inputs)) })
            tally.same("$name state_after", e["state_after"], jobj("item_capacity_values" to (cur.state["item_capacity_values"] ?: JNull),
                "buildings" to ((cur.state["subsystems"] as? JObj)?.get("buildings") ?: JNull)))
        }
        for (c in doc.arr("extra")) {
            val case = c.asObj
            when (case.str("kind")) {
                "capacity" -> {
                    val label = case.str("label")
                    val input = case.obj("input")
                    val cur = current(input)
                    tally.check("$label needed", case["needed"], outcome { SweepFeatures.capacityRepairNeeded(cur.state, inputs) })
                    tally.check("$label plan", case["plan"], outcome { planJson(SweepFeatures.planCapacityRepair(Owned(cur, inputs), inputs)) })
                    tally.same("$label state_after", case["state_after"], jobj("item_capacity_values" to cur.state["item_capacity_values"],
                        "buildings" to cur.state.obj("subsystems")["buildings"]))
                    tally.check("$label warehouse_slot", case["warehouse_slot"], outcome { planJson(SweepFeatures.planWarehouseSlot(ByteArray(0), Owned(current(input), inputs), inputs)) })
                    tally.check("$label warehouse_slot_nonempty", case["warehouse_slot_nonempty"],
                        outcome { SweepFeatures.planWarehouseSlot(byteArrayOf(1), Owned(current(input), inputs), inputs).data })
                }
                "place" -> {
                    val packets = case.arr("packets").map { p -> p.asArr[0].long.toInt() to (p.asArr[1] as JStr).value.hexBytes() }
                    tally.same("place ${text(case["packets"])}", case["expected"], framesJson(SweepFeatures.placeStartupFrames(packets, byteArrayOf(0xaa.toByte()), byteArrayOf(0xbb.toByte()))))
                }
                "decode_album" -> tally.check("decode_album ${case["raw"]}", case["expected"], outcome { SweepFeatures.decodeAlbum(case.str("raw").hexBytes()) })
                "decode_totems" -> tally.check("decode_totems ${case["raw"]}", case["expected"], outcome { SweepFeatures.decodeTotems(case.str("raw").hexBytes()) })
                "tmp_vip_payload" -> { val a = case.arr("args"); tally.check("tmp_vip_payload ${text(a)}", case["expected"], outcome { SweepFeatures.tmpVipPayload(a[0].long.toInt(), a[1].long).toHexString() }) }
                "tmp_vip_view" -> { val a = case.arr("args"); tally.check("tmp_vip_view ${text(a)}", case["expected"],
                    outcome { SweepFeatures.tmpVipView(a[0] as JObj, a[1].long, a[2].long).let { jarr(it.first, it.second) } }) }
                "tmp_vip_end_frames" -> { val a = case.arr("args"); tally.same("tmp_vip_end_frames ${text(a)}", case["expected"], framesJson(SweepFeatures.tmpVipEndFrames(null, a[0].long, a[1].long))) }
                "rebirth_shop_stopgap" -> {
                    tally.same("rebirth_shop_stopgap", case["expected"], JStr(SweepFeatures.rebirthShopStopgap(inputs).toHexString()))
                    tally.same("rebirth_shop_stopgap none", case["none"], JStr(SweepFeatures.rebirthShopStopgap(null).toHexString()))
                }
            }
        }
        tally.done()
    }

}
