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
import io.github.okexodus.openknights.server.store.StateStore
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path

/**
 * Local only (OPENKNIGHTS_DEV_DIR + OPENKNIGHTS_ORIGINALS): replays the private group-2 "evolve" vectors — hero
 * evolution, Power Up, Ascension, Astral Power, the hero-card inputs and the alternate team, run through the reference
 * on the recorded saves, generated heroes and systematic variations — against this port with the player's APK
 * tables, and requires JSON / byte equality (refusals: the same message and code).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class G2EvolveVectorsTest {
    private lateinit var dir: Path
    private lateinit var tables: GameTables
    private lateinit var inputs: DailyInputs
    private lateinit var evolution: EvolutionInputs
    private lateinit var cards: HeroCardInputs

    @BeforeAll
    fun setUp() {
        val dev = System.getenv("OPENKNIGHTS_DEV_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        val originals = System.getenv("OPENKNIGHTS_ORIGINALS")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        assumeTrue(dev != null && originals != null && Files.isDirectory(dev.resolve("vectors-g2")),
            "OPENKNIGHTS_DEV_DIR / OPENKNIGHTS_ORIGINALS not set: local-only test skipped")
        dir = dev!!.resolve("vectors-g2")
        tables = GameTables(ApkTables(originals!!.resolve("com.enjoygame.hero2d.apk")))
        inputs = DailyInputs(tables)
        evolution = EvolutionInputs(tables)
        cards = HeroCardInputs(tables)
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
    }

    private fun outcome(block: () -> Any?): Outcome = try {
        Outcome.Ok(jvalue(block()))
    } catch (e: Exception) {
        Outcome.Err(e)
    }

    private fun codeOf(e: Throwable): Int? = when (e) {
        is Acquisition.Rejected -> e.code
        is HeroStats.ProfileUnsupported -> e.code
        is HeroEvolution.EvolutionRejected -> e.code
        is HeroPowerUp.PowerUpRejected -> e.code
        is HeroAscension.AscensionRejected -> e.code
        is GodSkills.GodSkillRejected -> e.code
        is SecondaryTeam.Rejected -> e.code
        else -> null
    }

    private fun text(v: JValue?): String = Json.compact(v ?: JNull)

    private fun framesJson(frames: List<Frame>): JArr = JArr(frames.mapTo(ArrayList()) { jarr(it.first, it.second.toHexString()) })

    private fun planJson(plan: Plan, frames: List<Frame>): JObj = jobj("plan" to plan.data, "frames" to framesJson(frames))

    /** Compares one reference outcome record with the port's outcome. */
    private fun Tally.check(label: String, expected: JValue?, actual: Outcome) {
        checked++
        val exp = expected as? JObj ?: return fail(label, "no expectation")
        if ("result" in exp) {
            when (actual) {
                is Outcome.Ok -> { val a = text(actual.value); val b = text(exp["result"]); if (a != b) fail(label, "expected $b\n  but was $a") }
                is Outcome.Err -> fail(label, "expected ${text(exp["result"]).take(300)} but threw ${actual.e}")
            }
            return
        }
        when (actual) {
            is Outcome.Err -> {
                val valueError = (exp["value_error"] as? JBool)?.value == true
                if (valueError) {
                    if (actual.e !is IllegalArgumentException) fail(label, "expected ValueError ${exp["message"]} but threw ${actual.e}")
                    else if (actual.e.message != (exp["message"] as JStr).value) fail(label, "expected message ${exp["message"]} but was ${actual.e.message}")
                    val code = (exp["code"] as? JInt)?.value?.toInt()
                    if (code != null && codeOf(actual.e) != code) fail(label, "expected code $code but was ${codeOf(actual.e)}")
                } else if (actual.e is IllegalArgumentException) {
                    fail(label, "expected ${exp["error"]} (not a ValueError) but threw ${actual.e}")
                }
            }
            is Outcome.Ok -> fail(label, "expected ${exp["error"]}: ${exp["message"]} but was ${text(actual.value).take(300)}")
        }
    }

    private fun big(v: JValue?): BigInteger? = (v as? JInt)?.value

    private fun heroes(list: JArr): LinkedHashMap<Long, JArr> {
        val out = LinkedHashMap<Long, JArr>()
        for (e in list) out[e.asArr[0].long] = e.asArr[1].asArr.deepCopy()
        return out
    }

    private fun <V : JValue> view(list: JArr): LinkedHashMap<Long, V> {
        val out = LinkedHashMap<Long, V>()
        @Suppress("UNCHECKED_CAST")
        for (e in list) out[e.asArr[0].long] = e.asArr[1].deepCopy() as V
        return out
    }

    private fun policy(doc: JObj, name: String): JObj = doc.obj("policies").obj(name)

    // --- hero evolution -----------------------------------------------------------------------------------------------

    @Test
    fun `hero evolution`() {
        val doc = load("hero_evolution")
        val tally = Tally("hero_evolution")
        for ((template, expected) in doc.obj("catalog_inputs")) {
            tally.check("catalog_inputs $template", jobj("result" to expected),
                outcome { JObj().also { o -> evolution(template.toLong()).forEach { (k, v) -> if (k != "sources") o[k] = v } } })
        }
        val activity = doc.str("activity").hexBytes()
        val gold = doc.str("gold_payload").hexBytes()
        for ((index, c) in doc.arr("cases").withIndex()) {
            val case = c.asObj
            val fn = case.strOrNull("fn")
            val label = "#$index ${fn ?: case.str("kind")}"
            when (fn) {
                null -> {
                    val heroes = heroes(case.arr("heroes"))
                    val items = view<JObj>(case.arr("items"))
                    val template = case["template"].takeIf { it is JInt }?.long
                    val test = if ((case["test_policy"] as JBool).value) policy(doc, "evolution") else null
                    val role = big(case["role_level"])
                    val target = case.long("target_uid")
                    tally.check(label, case["expect"], outcome {
                        val loaded = template?.let { evolution(it) }
                        if (case.str("kind") == "ordinary") {
                            val plan = HeroEvolution.planOrdinaryEvolution(jobj("target_uid" to target), heroes, big(case["gold"]), items, loaded, test, role)
                            planJson(plan, HeroEvolution.ordinaryEvolutionPackets(plan, activity, gold))
                        } else {
                            val plan = HeroEvolution.planLeaderEvolution(JObj(), heroes, target, big(case["gold"]), items, loaded, test, role)
                            planJson(plan, HeroEvolution.leaderEvolutionPackets(plan, activity, gold))
                        }
                    })
                }
                "decode_ordinary_request" -> tally.check(label, case["expect"], outcome { HeroEvolution.decodeOrdinaryRequest(case.str("payload").hexBytes()) })
                "decode_leader_request" -> tally.check(label, case["expect"], outcome { HeroEvolution.decodeLeaderRequest(case.str("payload").hexBytes()) })
                "evolve_template" -> tally.check(label, case["expect"], outcome { HeroEvolution.evolveTemplate(case.long("template")) })
                "evolved_stats" -> tally.check(label, case["expect"], outcome {
                    HeroEvolution.evolvedStats(case.arr("grow").map { it.long }, case.long("level"), case.long("grade"))
                })
                "check_test_policy" -> tally.check(label, case["expect"], outcome { HeroEvolution.checkTestPolicy(case["policy"]) != null })
                else -> tally.fail(label, "unknown vector")
            }
        }
        tally.done()
    }

    // --- Power Up -----------------------------------------------------------------------------------------------------

    @Test
    fun `Power Up`() {
        val doc = load("hero_power_up")
        val tally = Tally("hero_power_up")
        val power = cards.powerUp()
        for ((index, c) in doc.arr("cases").withIndex()) {
            val case = c.asObj
            val fn = case.str("fn")
            val label = "#$index $fn"
            when (fn) {
                "plan_train" -> {
                    val policy = when (case.str("policy")) {
                        "labeled" -> policy(doc, "power-up")
                        "none" -> null
                        else -> jobj("document" to jobj("profile" to "x", "class" to "y"))
                    }
                    tally.check(label, case["expect"], outcome {
                        val template = case["template"].takeIf { it is JInt }?.long
                        val plan = HeroPowerUp.planTrain(case.obj("request"), heroes(case.arr("heroes")), view(case.arr("items")),
                            template?.let { cards.heroStats(it) }, power, policy, big(case["rng_seed"]), case.arr("excluded").map { it.long })
                        planJson(plan, HeroPowerUp.trainPackets(plan))
                    })
                }
                "plan_save" -> tally.check(label, case["expect"], outcome {
                    val plan = HeroPowerUp.planSave(case.obj("request"), heroes(case.arr("heroes")), cards.heroStats(case.long("template")),
                        case["pending"] as? JObj)
                    planJson(plan, HeroPowerUp.savePackets(plan))
                })
                "decode_open_request" -> tally.check(label, case["expect"], outcome { HeroPowerUp.decodeOpenRequest(case.str("payload").hexBytes()) })
                "decode_train_request" -> tally.check(label, case["expect"], outcome { HeroPowerUp.decodeTrainRequest(case.str("payload").hexBytes()) })
                "decode_save_request" -> tally.check(label, case["expect"], outcome { HeroPowerUp.decodeSaveRequest(case.str("payload").hexBytes()) })
                "result_payload" -> tally.check(label, case["expect"], outcome {
                    HeroPowerUp.resultPayload(case.long("uid"), case.arr("deltas").map { it.long }).toHexString()
                })
                "dev_caps" -> tally.check(label, case["expect"], outcome {
                    HeroPowerUp.devCaps(case.arr("stats").map { big(it)!! }, case.arr("dev").map { big(it)!! }, case.long("cap"))
                })
                "check_policy" -> tally.check(label, case["expect"], outcome { HeroPowerUp.checkPolicy(case["policy"]) != null })
                "table_way" -> tally.check(label, case["expect"], outcome { HeroPowerUp.tableWay(power, case.long("way")) })
                "roll" -> tally.check(label, case["expect"], outcome { HeroPowerUp.roll(big(case["seed"])!!, case.long("count"), case.obj("distribution")) })
                else -> tally.fail(label, "unknown vector")
            }
        }
        tally.done()
    }

    // --- Ascension ----------------------------------------------------------------------------------------------------

    @Test
    fun `hero Ascension`() {
        val doc = load("hero_ascension")
        val tally = Tally("hero_ascension")
        val gold = doc.str("gold_payload").hexBytes()
        for ((index, c) in doc.arr("cases").withIndex()) {
            val case = c.asObj
            val fn = case.strOrNull("fn")
            val label = "#$index ${fn ?: "plan_ascension"}"
            when (fn) {
                null -> {
                    if (case["load_error"] != null) {
                        tally.check("$label inputs", case["load_error"], outcome { cards.ascension(case.arr("heroes")[0].asArr[1].asArr.let { f ->
                            f.map { it.asObj }.first { it.long("id") == 1L }.obj("value").long("bits") }) })
                        continue
                    }
                    tally.check(label, case["expect"], outcome {
                        val template = case["template"].takeIf { it is JInt }?.long
                        val plan = HeroAscension.planAscension(case.obj("request"), heroes(case.arr("heroes")), view(case.arr("items")),
                            big(case["gold"]), big(case["role_level"]), template?.let { cards.ascension(it) },
                            if ((case["policy"] as JBool).value) policy(doc, "ascension") else null,
                            case.arr("bench").map { it.long }, case.arr("deployed").map { it.long }, case.arr("excluded").map { it.long })
                        planJson(plan, HeroAscension.ascensionPackets(plan, gold))
                    })
                }
                "decode_request" -> tally.check(label, case["expect"], outcome { HeroAscension.decodeRequest(case.str("payload").hexBytes()) })
                "awaken_requirement_key" -> tally.check(label, case["expect"], outcome {
                    val a = case.arr("args")
                    HeroAscension.awakenRequirementKey((a[0] as JBool).value, a[1].long, a[2].long)
                })
                "check_material_policy" -> tally.check(label, case["expect"], outcome { HeroAscension.checkMaterialPolicy(case["policy"]) != null })
                else -> tally.fail(label, "unknown vector")
            }
        }
        tally.done()
    }

    // --- Astral Power -------------------------------------------------------------------------------------------------

    @Test
    fun `Astral Power`() {
        val doc = load("god_skills")
        val tally = Tally("god_skills")
        val documents = doc.arr("documents")
        val rows = cards.astral().obj("rows")
        for ((index, c) in doc.arr("cases").withIndex()) {
            val case = c.asObj
            val fn = case.strOrNull("fn")
            val label = "#$index ${fn ?: "plan_upgrade"}"
            when (fn) {
                null -> tally.check(label, case["expect"], outcome {
                    val document = documents[case.long("document").toInt()].asObj.deepCopy()
                    val plan = GodSkills.planUpgrade(case.obj("request"), document, case.arr("owned").map { it.long }, view(case.arr("items")), rows)
                    val frames = GodSkills.upgradePackets(plan)
                    val data = JObj(LinkedHashMap(plan.data.map))
                    data["document_after"] = jobj("sha256" to GodSkills.documentChecksum(plan.data.obj("document_after")))
                    jobj("plan" to data, "frames" to framesJson(frames))
                })
                "decode_upgrade_request" -> tally.check(label, case["expect"], outcome { GodSkills.decodeUpgradeRequest(case.str("payload").hexBytes()) })
                else -> tally.fail(label, "unknown vector")
            }
        }
        tally.done()
    }

    // --- hero-card inputs ---------------------------------------------------------------------------------------------

    @Test
    fun `hero-card inputs`() {
        val doc = load("pk_hero_card_inputs")
        val tally = Tally("pk_hero_card_inputs")
        val groups = PkHeroCardInputs.astralGroups(tables)
        for ((index, c) in doc.arr("cases").withIndex()) {
            val case = c.asObj
            val fn = case.str("fn")
            val label = "#$index $fn"
            when (fn) {
                "power_up_inputs" -> tally.check(label, case["expect"], outcome { PkHeroCardInputs.powerUpInputs(tables) })
                "astral_rows" -> tally.check(label, case["expect"], outcome { PkHeroCardInputs.astralRows(tables) })
                "astral_groups" -> tally.check(label, case["expect"], outcome { PkHeroCardInputs.astralGroups(tables) })
                "astral_initial_skills" -> {
                    tally.check("$label ${case.long("template")}", case["expect"], outcome { PkHeroCardInputs.astralInitialSkills(tables, case.long("template"), groups) })
                    tally.check("$label ${case.long("template")} (acquisition inputs)", case["expect"], outcome { inputs.astralInitialSkills(case.long("template")) })
                }
                "ascension_inputs" -> tally.check("$label ${case.long("template")}", case["expect"], outcome { PkHeroCardInputs.ascensionInputs(tables, case.long("template")) })
                else -> tally.fail(label, "unknown vector")
            }
        }
        tally.done()
    }

    // --- alternate team -----------------------------------------------------------------------------------------------

    private fun current(input: JObj): StateStore.Current {
        val docs = LinkedHashMap<String, JValue?>()
        for ((k, v) in input.obj("documents")) docs[k] = if (v == JNull) null else v.deepCopy()
        fun objOrNull(v: JValue?): JObj? = (v as? JObj)?.deepCopy()
        return StateStore.Current(1, "", "", input.obj("state").deepCopy(), ByteArray(0), 0, null,
            input.arr("inventory_items").deepCopy(), emptyList(), input.arr("acquired_items").deepCopy(), emptyList(), null, null,
            objOrNull(input["god_skills"]), objOrNull(input["character_profile"]), null, docs)
    }

    @Test
    fun `alternate team`() {
        val doc = load("alt_team")
        val tally = Tally("alt_team")
        val saves = doc.arr("saves")
        val rules = SecondaryTeam.loadNativeLineupRules(tables)
        for ((index, c) in doc.arr("cases").withIndex()) {
            val case = c.asObj
            val fn = case.str("fn")
            val label = "#$index $fn"
            when (fn) {
                "load_native_lineup_rules" -> tally.check(label, case["expect"], outcome {
                    jobj("flags" to rules.heroFlags.map { (k, v) -> jarr(k, v) }, "reborn" to rules.rebornRows.map { jarr(it.first, it.second, it.third) })
                })
                "unlock", "set" -> {
                    val entry = saves[case.long("save").toInt()].asObj.deepCopy()
                    for (f in entry.obj("state").arr("role_properties")) {
                        val field = f.asObj
                        when (field.long("id")) {
                            3L -> field.obj("value")["bits"] = case.getValue("level")
                            6L -> field.obj("value")["bits"] = case.getValue("gold")
                            8L -> field.obj("value")["bits"] = case.getValue("diamonds")
                        }
                    }
                    entry.obj("documents")["alt_team"] = case.getValue("doc")
                    val cur = current(entry)
                    val owned = Owned(cur, inputs)
                    tally.check(label, case["expect"], outcome {
                        val payload = case.str("payload").hexBytes()
                        val plan = if (fn == "unlock") AltTeam.planUnlock(payload, owned, cur, inputs)
                            else AltTeam.planSet(payload, owned, cur, inputs, rules = if ((case["rules"] as JBool).value) rules else null)
                        jobj("plan" to plan.data, "frames" to framesJson(plan.packets),
                            "role_changes" to JObj().also { o -> owned.roleChanges.forEach { (k, v) -> o[k.toString()] = jobj("before" to v.first, "after" to v.second) } })
                    })
                }
                "check" -> tally.check(label, case["expect"], outcome {
                    val state = case.obj("state")
                    val hero = SecondaryTeam.ownedHeroes(state).getValue(case.long("hero_uid"))
                    rules.check(state, case.arr("references").map { it.asObj }, hero, case.long("position"))
                    null
                })
                else -> tally.fail(label, "unknown vector")
            }
        }
        tally.done()
    }
}
