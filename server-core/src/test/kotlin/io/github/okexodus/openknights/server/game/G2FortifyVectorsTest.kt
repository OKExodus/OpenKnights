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
import io.github.okexodus.openknights.server.Entropy
import io.github.okexodus.openknights.server.store.JdbcSqlDriver
import io.github.okexodus.openknights.server.store.StateStore
import io.github.okexodus.openknights.server.store.fortifyEquipment
import io.github.okexodus.openknights.server.store.fortifyHero
import io.github.okexodus.openknights.server.store.fortifyItemsGear
import io.github.okexodus.openknights.server.store.fortifyItemsHero
import io.github.okexodus.openknights.server.store.fortifyItemsJewelry
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path

/**
 * Local only (OPENKNIGHTS_DEV_DIR + OPENKNIGHTS_ORIGINALS): replays the private group-2 "fortify" vectors — hero / gear
 * Fortify, EXP-item Fortify, the class change, Rebirth Evolve / Fortify, Reborn, the acquisition route skeleton and the
 * Fortify store transactions, run through the reference on the recorded roots' saves and systematic variations —
 * against this port with the player's APK tables, and requires JSON / byte equality (errors: the same refusal, message
 * and code).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class G2FortifyVectorsTest {
    private lateinit var dev: Path
    private lateinit var dir: Path
    private lateinit var tables: GameTables
    private lateinit var inputs: DailyInputs

    @BeforeAll
    fun setUp() {
        val devDir = System.getenv("OPENKNIGHTS_DEV_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        val originals = System.getenv("OPENKNIGHTS_ORIGINALS")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        assumeTrue(devDir != null && originals != null && Files.isDirectory(devDir.resolve("vectors-g2")),
            "OPENKNIGHTS_DEV_DIR / OPENKNIGHTS_ORIGINALS not set: local-only test skipped")
        dev = devDir!!
        dir = dev.resolve("vectors-g2")
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
    }

    private fun outcome(block: () -> Any?): Outcome = try {
        Outcome.Ok(jvalue(block()))
    } catch (e: Exception) {
        Outcome.Err(e)
    } catch (e: StackOverflowError) {
        Outcome.Err(e)
    }

    private fun codeOf(e: Throwable): Int? = when (e) {
        is Acquisition.Rejected -> e.code
        is HeroStats.ProfileUnsupported -> e.code
        is HeroFortify.FortifyRejected -> e.code
        is ItemFortify.ItemFortifyRejected -> e.code
        else -> null
    }

    private fun text(v: JValue?): String = Json.compact(v ?: JNull)

    private fun framesJson(frames: List<Frame>): JArr = JArr(frames.mapTo(ArrayList()) { jarr(it.first, it.second.toHexString()) })

    private fun Tally.check(label: String, expected: JValue?, actual: Outcome) {
        checked++
        val exp = expected as? JObj ?: return fail(label, "no expectation")
        if ("result" in exp) {
            when (actual) {
                is Outcome.Ok -> { val a = text(actual.value); val b = text(exp["result"]); if (a != b) fail(label, "expected $b but was $a") }
                is Outcome.Err -> fail(label, "expected ${text(exp["result"]).take(300)} but threw ${actual.e}")
            }
            return
        }
        when (actual) {
            is Outcome.Err -> {
                if ((exp["value_error"] as? JBool)?.value == true) {
                    if (actual.e !is IllegalArgumentException) fail(label, "expected ValueError ${exp["message"]} but threw ${actual.e}")
                    else if (actual.e.message != (exp["message"] as JStr).value) fail(label, "expected message ${exp["message"]} but was ${actual.e.message}")
                    val code = (exp["code"] as? JInt)?.value?.toInt()
                    if (code != null && codeOf(actual.e) != code) fail(label, "expected code $code but was ${codeOf(actual.e)}")
                } else if (actual.e is IllegalArgumentException && actual.e !is PyValues.ValueError && codeOf(actual.e) != null) {
                    fail(label, "expected ${exp["error"]}: ${exp["message"]} but refused with ${actual.e}")
                }
            }
            is Outcome.Ok -> fail(label, "expected ${exp["error"]}: ${exp["message"]} but was ${text(actual.value).take(300)}")
        }
    }

    private fun Tally.same(label: String, expected: JValue?, actual: JValue?) {
        checked++
        val a = text(actual)
        val b = text(expected)
        if (a != b) fail(label, "expected $b but was $a")
    }

    private fun current(input: JObj): StateStore.Current {
        fun objOrNull(v: JValue?): JObj? = (v as? JObj)?.deepCopy()
        return StateStore.Current(1, "", "", input.obj("state").deepCopy(), ByteArray(0), 0, null,
            input.arr("inventory_items").deepCopy(), emptyList(), input.arr("acquired_items").deepCopy(), emptyList(), null,
            objOrNull(input["secondary_team"]), objOrNull(input["god_skills"]), objOrNull(input["character_profile"]), null, emptyMap())
    }

    private fun longs(v: JValue?): List<Long> = (v as JArr).map { it.long }

    private fun heroesOf(state: JObj): LinkedHashMap<Long, JArr> = SecondaryTeam.ownedHeroes(state)

    private fun heroesFrom(pairs: JArr): LinkedHashMap<Long, JArr> =
        LinkedHashMap<Long, JArr>().also { m -> pairs.forEach { m[it.asArr[0].long] = it.asArr[1].asArr } }

    // --- progression ---------------------------------------------------------------------------------------------------

    @Test
    fun `progression EXP and cost arithmetic`() {
        val tally = Tally("hero_dictionary_progression_g2")
        for (c in load("hero_dictionary_progression_g2").arr("cases")) {
            val case = c.asObj
            val a = case.arr("args")
            val label = "${case.str("fn")} ${text(a).take(120)}"
            val out = when (case.str("fn")) {
                "exp_for_level" -> outcome { HeroDictionaryProgression.expForLevel(a[0].long, a[1].long) }
                "exp_for_equip_level" -> outcome { HeroFortify.expForEquipLevel(a[0].long, a[1].long) }
                "consumed_hero_exp" -> outcome { HeroDictionaryProgression.consumedHeroExp(a[0].long, a[1].long, a[2].long, a[3].long, a[4].long, a[5].long, longs(a[6])) }
                "consumed_equipment_exp" -> outcome { HeroDictionaryProgression.consumedEquipmentExp(a[0].long, a[1].long, a[2].long, a[3].long, a[4].long, longs(a[5])) }
                else -> outcome { HeroDictionaryProgression.upgradeInstanceCost(a[0].long, a[1].long, (a[2] as JStr).value) }
            }
            tally.check(label, case["expected"], out)
        }
        tally.done()
    }

    // --- catalog inputs ------------------------------------------------------------------------------------------------

    @Test
    fun `fortify and item fortify catalog inputs`() {
        val tally = Tally("pk_fortify_contract_g2 + pk_item_fortify_contract")
        for (c in load("pk_fortify_contract_g2").arr("cases")) {
            val case = c.asObj
            if (case.str("kind") == "catalog_inputs") {
                val t = case.long("target")
                tally.check("catalog_inputs $t", case["expected"], outcome { PkFortifyContract.catalogInputs(tables, t, longs(case["materials"])) })
            } else {
                val t = case.arr("target")
                val mats = case.arr("materials").map { it.asArr[0].long to it.asArr[1].long }
                tally.check("equipment_inputs ${text(t)}", case["expected"], outcome { PkFortifyContract.equipmentInputs(tables, t[0].long to t[1].long, mats) })
            }
        }
        for (c in load("pk_item_fortify_contract").arr("cases")) {
            val case = c.asObj
            when (case.str("kind")) {
                "item_exp_map" -> tally.check("item_exp_map", case["expected"], outcome {
                    JObj().also { o -> PkItemFortifyContract.itemExpMap(tables).forEach { (k, v) -> o[k.toString()] = v } }
                })
                "hero_item_inputs" -> tally.check("hero_item_inputs ${case.long("template")}", case["expected"], outcome { PkItemFortifyContract.heroItemInputs(tables, case.long("template")) })
                "gear_item_inputs" -> {
                    tally.check("gear_item_inputs ${case.long("template")} ${case.long("grade")}", case["expected"], outcome { PkItemFortifyContract.gearItemInputs(tables, case.long("template"), case.long("grade")) })
                    tally.check("DailyInputs.gear_exp ${case.long("template")} ${case.long("grade")}", case["expected"], outcome { inputs.gearExp(case.long("template"), case.long("grade")) })
                }
                "jewelry_item_inputs" -> {
                    tally.check("jewelry_item_inputs ${case.long("template")} ${case.long("grade")}", case["expected"], outcome { PkItemFortifyContract.jewelryItemInputs(tables, case.long("template"), case.long("grade")) })
                    tally.check("DailyInputs.jewel_exp ${case.long("template")} ${case.long("grade")}", case["expected"], outcome { inputs.jewelExp(case.long("template"), case.long("grade")) })
                }
            }
        }
        tally.done()
    }

    // --- hero_fortify ----------------------------------------------------------------------------------------------------

    @Test
    fun `hero and gear Fortify codecs, arithmetic and planners`() {
        val doc = load("hero_fortify")
        val saves = doc.arr("saves")
        val loader = FortifyInputs(tables)
        val heroCurve = PkFortifyContract.expTable(tables, "heroexp")
        val equipCurve = PkFortifyContract.expTable(tables, "equipexp")
        val tally = Tally("hero_fortify")
        for (c in doc.arr("cases")) {
            val case = c.asObj
            val kind = case.str("kind")
            when (kind) {
                "decode_fortify_request" -> tally.check("$kind ${case.str("payload")}", case["expected"], outcome { HeroFortify.decodeFortifyRequest(case.str("payload").hexBytes()) })
                "encode_fortify_request" -> tally.check(kind, case["expected"], outcome { HeroFortify.encodeFortifyRequest(case.obj("value")).toHexString() })
                "encode_upgrade_result" -> tally.check(kind, case["expected"], outcome { HeroFortify.encodeUpgradeResult(case.obj("value").deepCopy()).toHexString() })
                "decode_upgrade_result" -> tally.check(kind, case["expected"], outcome { HeroFortify.decodeUpgradeResult(case.str("payload").hexBytes()) })
                "equipment_record_payload" -> tally.check(kind, case["expected"], outcome { HeroFortify.equipmentRecordPayload(longs(case["values"])).toHexString() })
                "settle_hero_exp" -> { val a = longs(case["args"]); tally.check("$kind $a", case["expected"], outcome { HeroFortify.settleHeroExp(a[0], a[1], a[2], a[3], heroCurve, a[4]) }) }
                "settle_equipment_exp" -> { val a = longs(case["args"]); tally.check("$kind $a", case["expected"], outcome { HeroFortify.settleEquipmentExp(a[0], a[1], a[2], a[3], equipCurve, a[4]) }) }
                "check_supported_profile" -> tally.check(kind, case["expected"], outcome {
                    JObj().also { o -> HeroFortify.checkSupportedProfile(case.arr("fields").deepCopy()).forEach { (k, v) -> o[k.toString()] = v } }
                })
                "check_equipment_profile" -> tally.check(kind, case["expected"], outcome { HeroFortify.checkEquipmentProfile(case.arr("values")) })
                "plan_hero_fortify" -> {
                    val state = saves[case.long("save").toInt()].asObj.obj("state").deepCopy()
                    val heroes = if ("heroes" in case) heroesFrom(case.arr("heroes").deepCopy()) else heroesOf(state)
                    val args = case["inputs_args"]
                    val loaded = if (args is JArr) loader(args[0].long, longs(args[1])) else null
                    val request = case.obj("request")
                    val gold = case.int("gold")
                    val label = "$kind save ${case.long("save")} ${text(request)} gold $gold"
                    val out = outcome { HeroFortify.planHeroFortify(request, heroes, longs(case["bench"]), longs(case["deployed"]), longs(case["excluded"]), gold, loaded) }
                    tally.check(label, case["expected"], out)
                    if (out is Outcome.Ok && case["packets"] is JArr) {
                        val plan = out.value as JObj
                        tally.same("$label packets", case["packets"], framesJson(HeroFortify.heroFortifyPackets(plan, byteArrayOf(1, 2, 3),
                            TransactionPackets.goldPropertyPayload(plan.int("gold_after")))))
                    }
                }
                "plan_equipment_fortify" -> {
                    val equipment = heroesFrom(case.arr("equipment").deepCopy())
                    val args = case["inputs_args"]
                    val loaded = if (args is JArr) loader.equipment(args[0].asArr[0].long to args[0].asArr[1].long, args[1].asArr.map { it.asArr[0].long to it.asArr[1].long }) else null
                    val request = case.obj("request")
                    val gold = case.int("gold")
                    val label = "$kind ${text(request)} gold $gold"
                    val out = outcome { HeroFortify.planEquipmentFortify(request, equipment, longs(case["bag"]), longs(case["equipped"]), gold, loaded) }
                    tally.check(label, case["expected"], out)
                    if (out is Outcome.Ok && case["packets"] is JArr) {
                        val plan = out.value as JObj
                        tally.same("$label packets", case["packets"], framesJson(HeroFortify.equipmentFortifyPackets(plan, TransactionPackets.goldPropertyPayload(plan.int("gold_after")))))
                    }
                }
                else -> tally.fail(kind, "unknown case kind")
            }
        }
        tally.done()
    }

    // --- item_fortify ----------------------------------------------------------------------------------------------------

    private fun ownedByItem(pairs: JArr): Map<Long, JObj> = LinkedHashMap<Long, JObj>().also { m -> pairs.forEach { m[it.asArr[0].long] = it.asArr[1].asObj } }

    @Test
    fun `EXP-item Fortify codecs, bonus policy, settlement and planners`() {
        val doc = load("item_fortify")
        val saves = doc.arr("saves")
        val release = doc.obj("release_bonus")
        val loader = ItemFortifyInputs(tables)
        val itemMap = loader.itemMap()
        val tally = Tally("item_fortify")
        tally.same("item map size", doc["item_map_size"], JInt(itemMap.size))
        for (c in doc.arr("cases")) {
            val case = c.asObj
            val kind = case.str("kind")
            when (kind) {
                "decode_item_fortify_request" -> tally.check("$kind ${case.str("payload")}", case["expected"], outcome { ItemFortify.decodeItemFortifyRequest(case.str("payload").hexBytes()) })
                "check_bonus_policy" -> tally.check("$kind ${text(case["policy"]).take(200)}", case["expected"],
                    outcome { ItemFortify.checkBonusPolicy((case["policy"] as? JObj)?.deepCopy()) })
                "bonus_roller" -> {
                    val roll = ItemFortify.bonusRoller(case.obj("policy"), case.int("seed"))
                    tally.same("$kind ${case.int("seed")}", (case["expected"] as JObj)["result"], JArr((0 until 60).mapTo(ArrayList()) { JInt(roll()) }))
                }
                "plan_consumption" -> {
                    val reqs = HashMap<Long, Long>()
                    for (r in case.arr("requirements")) reqs[r.asArr[0].long] = r.asArr[1].long
                    val seed = case["seed"]
                    val roll = if (seed is JInt) ItemFortify.bonusRoller(release, seed.value) else null
                    val fill = case["fill_to_cap"] as? JObj
                    tally.check("$kind ${text(case).take(200)}", case["expected"], outcome {
                        ItemFortify.planConsumption(case.arr("staged").map { it.asObj.deepCopy() }, case.long("level"), case.long("exp"), case.long("cap"),
                            { l -> reqs.getValue(l) }, roll, fill)
                    })
                }
                "plan_hero_item_fortify" -> {
                    val state = saves[case.long("save").toInt()].asObj.obj("state").deepCopy()
                    val heroes = heroesOf(state)
                    val template = case["inputs_template"]
                    val loaded = if (template is JInt) (try { loader.hero(template.value.toLong()) } catch (e: Exception) { null }) else null
                    val policy = if (case.bool("policy")) release else null
                    val request = case.obj("request")
                    val gold = case.int("gold")
                    val label = "$kind save ${case.long("save")} ${text(request)} policy ${case.bool("policy")}"
                    val out = outcome { ItemFortify.planHeroItemFortify(request, heroes, itemMap, ownedByItem(case.arr("owned_by_item")), gold, loaded, policy, case.int("seed")) }
                    tally.check(label, case["expected"], out)
                    if (out is Outcome.Ok && case["packets"] is JArr) {
                        val plan = out.value as JObj
                        tally.same("$label packets", case["packets"], framesJson(ItemFortify.itemFortifyPackets(plan,
                            TransactionPackets.goldPropertyPayload(plan.int("gold_after")), byteArrayOf(9))))
                    }
                }
                "plan_gear_item_fortify", "plan_jewelry_item_fortify" -> {
                    val records = LinkedHashMap<Long, List<Long>>().also { m -> case.arr("records").forEach { m[it.asArr[0].long] = longs(it.asArr[1]) } }
                    val key = longs(case["inputs_key"])
                    val gear = kind == "plan_gear_item_fortify"
                    val loaded = try { if (gear) loader.gear(key[0], key[1]) else loader.jewelry(key[0], key[1]) } catch (e: Exception) { null }
                    val policy = if (case.bool("policy")) release else null
                    val request = case.obj("request")
                    val owned = ownedByItem(case.arr("owned_by_item"))
                    tally.check("$kind ${text(request)} policy ${case.bool("policy")}", case["expected"], outcome {
                        if (gear) ItemFortify.planGearItemFortify(request, records, itemMap, owned, case.int("gold"), loaded, policy, case.int("seed"))
                        else ItemFortify.planJewelryItemFortify(request, records, itemMap, owned, case.int("gold"), loaded, policy, case.int("seed"))
                    })
                }
                "jewelry_view" -> {
                    val state = saves[case.long("save").toInt()].asObj.obj("state")
                    tally.check("$kind save ${case.long("save")}", case["expected"], outcome {
                        JObj().also { o -> ItemFortify.jewelryView(state.arr("formation")).forEach { (u, v) -> o[u.toString()] = v.toJson() } }
                    })
                }
                "jewelry_block_with" -> tally.check(kind, case["expected"], outcome { ItemFortify.jewelryBlockWith(case.str("raw").hexBytes(), case.long("exp"), case.long("level")).toHexString() })
                "jewelry_block_decode" -> tally.check(kind, case["expected"], outcome { ItemFortify.jewelryBlockDecode(case.str("raw").hexBytes()) })
                "item_fortify_result_payload" -> tally.check(kind, case["expected"], outcome {
                    ItemFortify.itemFortifyResultPayload(case.arr("consumed").deepCopy(), case.str("grow_key"), case.arr("rows").deepCopy()).toHexString()
                })
                else -> tally.fail(kind, "unknown case kind")
            }
        }
        tally.done()
    }

    // --- owned-view planners: change_job / rebirth / reborn / acquisition_routes -------------------------------------------

    private fun ownedEffects(owned: Owned): JObj = jobj(
        "item_changes" to owned.itemChanges.map { (k, v) -> jarr(k, v) },
        "new_items" to owned.newItems.map { (k, v) -> jarr(k, v.first, v.second) },
        "role_changes" to owned.roleChanges.map { (k, v) -> jarr(k, v.first, v.second) },
        "log" to owned.log, "god_document" to owned.godDocument, "heroes" to owned.state["heroes"],
        "hero_collection" to owned.state.obj("subsystems")["hero_collection"], "achievements" to owned.state.obj("subsystems")["achievements"])

    private fun runOwned(input: JObj, planner: (Owned, StateStore.Current) -> Plan): Outcome {
        val cur = current(input)
        val owned = Owned(cur, inputs)
        return outcome {
            val plan = planner(owned, cur)
            jobj("plan" to plan.data, "packets" to framesJson(plan.packets), "owned" to ownedEffects(owned))
        }
    }

    @Test
    fun `class change, Rebirth, Reborn and the acquisition route skeleton`() {
        val doc = load("owned_planners_g2")
        val currents = doc.arr("currents")
        val evolution = EvolutionInputs(tables)
        val tally = Tally("owned_planners_g2")
        for (c in doc.arr("cases")) {
            val case = c.asObj
            val kind = case.str("kind")
            val input = (case["current"] as? JInt)?.let { currents[it.value.toInt()].asObj }
            val payload = case.strOrNull("payload")?.hexBytes()
            val label = "$kind #${case["current"]} ${case.strOrNull("payload")}"
            when (kind) {
                "change_job" -> tally.check(label, case["expected"], runOwned(input!!) { owned, cur -> ChangeJob.planChangeJob(payload!!, owned, cur, inputs, evolution) })
                "move_skills" -> tally.check("$kind ${text(case)}", case["expected"], outcome {
                    val (d, s) = ChangeJob.moveSkills(case.obj("document"), case.long("uid"), case.long("old"), case.long("new")); jarr(d, s)
                })
                "rebirth_evolve" -> tally.check(label, case["expected"], runOwned(input!!) { owned, _ -> Rebirth.planEvolve(Rebirth.decodeEvolveRequest(payload!!), owned, inputs) })
                "rebirth_fortify" -> tally.check(label, case["expected"], runOwned(input!!) { owned, _ -> Rebirth.planFortify(Rebirth.decodeFortifyRequest(payload!!), owned, inputs) })
                "reborn" -> tally.check(label, case["expected"], runOwned(input!!) { owned, _ -> Reborn.planReborn(Reborn.decodeRequest(payload!!), owned, inputs, longs(case["excluded"])) })
                "planner_for" -> {
                    val cur = current(input!!)
                    val policy = FreshProfile.DeploymentPolicy(cur.characterProfile!!)
                    val now = case.long("now")
                    val expectedRouted = case.obj("routed")
                    try {
                        val r = AcquisitionRoutes.plannerFor(case.int("opcode").toInt(), payload!!, inputs, null, null, policy, now) { now }
                        tally.same("$label routed", expectedRouted, jobj("action" to r.action, "request" to r.request))
                        tally.check(label, case["expected"], runOwned(input) { owned, cc -> r.planner(owned, cc) })
                    } catch (e: Exception) {
                        tally.check("$label routed", expectedRouted, Outcome.Err(e))
                    }
                }
                "planner_for_other" -> {
                    val cur = currents.firstOrNull { (it.asObj["character_profile"] as? JObj) != null }?.asObj?.let { current(it) }
                    val policy = FreshProfile.DeploymentPolicy(cur!!.characterProfile!!)
                    val out = try {
                        AcquisitionRoutes.plannerFor(case.int("opcode").toInt(), ByteArray(0), inputs, null, null, policy, 1) { 1 }; Outcome.Ok(JStr("none"))
                    } catch (e: NotPorted) {
                        Outcome.Ok(JStr("not ported"))
                    } catch (e: Exception) {
                        Outcome.Err(e)
                    }
                    val expected = case.obj("routed")
                    // the other acquisition opcodes wait for their group (NotPorted); a non-acquisition opcode is refused
                    if (out is Outcome.Err) tally.check("$kind ${case["opcode"]}", expected, out)
                    else { tally.checked++; if ((out as Outcome.Ok).value != JStr("not ported")) tally.fail("$kind ${case["opcode"]}", "expected not ported") }
                }
                "is_read_only" -> for (e in case.arr("expected")) {
                    val r = e.asArr
                    tally.same("is_read_only ${text(r)}", r[2], JBool(AcquisitionRoutes.isReadOnly(r[0].long.toInt(), (r[1] as JStr).value.hexBytes())))
                }
                else -> tally.fail(kind, "unknown case kind")
            }
        }
        tally.done()
    }

    // --- store transactions on copies -------------------------------------------------------------------------------------

    private class FixedEntropy(private val draws: JArr) : Entropy by io.github.okexodus.openknights.server.SystemEntropy {
        var cursor = 0
        override fun randbits(k: Int): BigInteger {
            val draw = draws[cursor++].asArr
            require(draw[0].long.toInt() == k) { "draw width $k differs" }
            return draw[1].big
        }
    }

    @Test
    fun `Fortify store transactions on copies of the saves`() {
        val doc = load("store_fortify")
        val release = doc.obj("release_bonus")
        val driver = JdbcSqlDriver()
        val fortifyInputs = FortifyInputs(tables)
        val itemInputs = ItemFortifyInputs(tables)
        val tally = Tally("store_fortify")
        val tmp = Files.createTempDirectory("g2-fortify-store")
        val saved = Entropy.current
        try {
            for ((n, c) in doc.arr("cases").withIndex()) {
                val case = c.asObj
                val method = case.str("kind")
                val copy = tmp.resolve("$n.sqlite3")
                Files.copy(dev.resolve(case.str("save")), copy)
                val store = StateStore(copy, driver)
                val before = store.read()
                val policy = FreshProfile.DeploymentPolicy(before.characterProfile!!)
                val request = case.obj("request")
                val characterId = case.str("character_id")
                val entropy = FixedEntropy(case.arr("draws"))
                Entropy.current = entropy
                val label = "$method ${case.str("save").takeLast(40)} ${text(request)} ${case["policy"]}"
                val bonus = if (case["policy"] is JStr) release else null
                val out = outcome {
                    val result = when (method) {
                        "fortify_hero" -> store.fortifyHero(request, characterId, policy, fortifyInputs, "authenticated-client", "vector")
                        "fortify_equipment" -> store.fortifyEquipment(request, characterId, policy, fortifyInputs, "authenticated-client", "vector")
                        "fortify_items_hero" -> store.fortifyItemsHero(request, characterId, policy, itemInputs, "authenticated-client", "vector", bonusPolicy = bonus)
                        "fortify_items_gear" -> store.fortifyItemsGear(request, characterId, policy, itemInputs, "authenticated-client", "vector", bonusPolicy = bonus)
                        else -> store.fortifyItemsJewelry(request, characterId, policy, itemInputs, "authenticated-client", "vector", bonusPolicy = bonus)
                    }
                    JObj(LinkedHashMap(result.result.map.filterKeys { it != "timestamp_utc" })).also { it["__plan"] = result.plan }
                }
                tally.checked++
                if (entropy.cursor != case.arr("draws").size) tally.fail(label, "drew ${entropy.cursor} of ${case.arr("draws").size} recorded seeds")
                val expected = case.obj("expected")
                if ("result" in expected && out is Outcome.Ok) {
                    val got = out.value as JObj
                    val plan = got.remove("__plan")
                    tally.same("$label result", expected["result"], got)
                    tally.same("$label plan", expected["plan"], plan)
                    val after = store.read()
                    val history = driver.open(copy, io.github.okexodus.openknights.server.store.SqlDriver.Mode.READ_ONLY).use { db ->
                        val row = db.queryOne("SELECT revision, action, detail_json, payload_sha256 FROM state_history ORDER BY revision DESC LIMIT 1")!!
                        jobj("revision" to row.long("revision"), "action" to row.string("action"), "detail_json" to row.string("detail_json"),
                            "payload_sha256" to row.string("payload_sha256"))
                    }
                    tally.same("$label after", expected["after"], jobj("state" to after.state, "inventory_items" to after.inventoryItems,
                        "acquired_items" to after.acquiredItems, "revision" to after.revision, "history" to history))
                } else {
                    tally.checked--
                    tally.check(label, expected, out)
                }
            }
        } finally {
            Entropy.current = saved
            tmp.toFile().deleteRecursively()
        }
        tally.done()
    }
}
