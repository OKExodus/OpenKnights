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
import io.github.okexodus.openknights.server.store.JdbcSqlDriver
import io.github.okexodus.openknights.server.store.SqlDriver
import io.github.okexodus.openknights.server.store.StateStore
import io.github.okexodus.openknights.server.store.formationTransaction
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path

/**
 * Local only (OPENKNIGHTS_DEV_DIR + OPENKNIGHTS_ORIGINALS): replays the private group-3 "formation" vectors — the
 * catalog inputs, the codecs, every planner on the recorded roots' saves and systematic variations, and the store
 * transaction as chains of requests on copies of the saves — against this port with the player's APK tables, and
 * requires JSON / byte equality (errors: the same refusal, message and code).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class G3FormationVectorsTest {
    private lateinit var dev: Path
    private lateinit var dir: Path
    private lateinit var tables: GameTables
    private lateinit var inputs: FormationInputs

    @BeforeAll
    fun setUp() {
        val devDir = System.getenv("OPENKNIGHTS_DEV_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        val originals = System.getenv("OPENKNIGHTS_ORIGINALS")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        assumeTrue(devDir != null && originals != null && Files.isRegularFile(devDir.resolve("vectors-g3").resolve("store_formation.json")),
            "OPENKNIGHTS_DEV_DIR / OPENKNIGHTS_ORIGINALS not set: local-only test skipped")
        dev = devDir!!
        dir = dev.resolve("vectors-g3")
        tables = GameTables(ApkTables(originals!!.resolve("com.enjoygame.hero2d.apk")))
        inputs = FormationInputs(tables)
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

    private fun codeOf(e: Throwable): Int? = (e as? EquipFormation.FormationRejected)?.code

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
                    if (code == null && codeOf(actual.e) != null) fail(label, "expected a plain ValueError but refused with code ${codeOf(actual.e)}")
                } else if (codeOf(actual.e) != null) {
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

    private fun longs(v: JValue?): List<Long> = (v as JArr).map { it.long }

    // --- catalog inputs ------------------------------------------------------------------------------------------------

    @Test
    fun `formation catalog inputs`() {
        val tally = Tally("pk_equip_formation_contract")
        for (c in load("pk_equip_formation_contract").arr("cases")) {
            val case = c.asObj
            val kind = case.str("kind")
            val label = "$kind ${case["config"] ?: case["template"] ?: ""}"
            val out = outcome {
                when (kind) {
                    "gem_rows" -> JArr(inputs.gemRows().entries.mapTo(ArrayList()) { (k, r) -> jarr(k, r.type, r.next, r.cost) })
                    "equip_position" -> inputs.equipPosition(case.long("config"))
                    "jewel_position" -> inputs.jewelPosition(case.long("config"))
                    else -> inputs.isLeader(case["template"])
                }
            }
            tally.check(label, case["expected"], out)
        }
        tally.done()
    }

    // --- codecs --------------------------------------------------------------------------------------------------------

    @Test
    fun `formation codecs and state helpers`() {
        val tally = Tally("equip_formation_codecs")
        for (c in load("equip_formation_codecs").arr("cases")) {
            val case = c.asObj
            val kind = case.str("kind")
            val label = "$kind ${text(case).take(160)}"
            val out = outcome {
                when (kind) {
                    "decode_request" -> EquipFormation.decodeRequest(case.long("opcode").toInt(), case.str("payload").hexBytes())
                    "encode_request" -> EquipFormation.encodeRequest(case.long("opcode").toInt(), case.obj("value")).toHexString()
                    "counted_uids" -> EquipFormation.countedUids(longs(case["uids"])).toHexString()
                    "record_list" -> EquipFormation.recordList(case.arr("records").map { it.asArr }).toHexString()
                    "gem_bag_frame" -> framesJson(listOf(EquipFormation.gemBagFrame(case.long("gem_id"), case.long("count"))))
                    "gem_group_frame" -> framesJson(listOf(EquipFormation.gemGroupFrame(case.long("slot"), case.long("group"), longs(case["ids"]))))
                    "combine_reward_frame" -> framesJson(listOf(EquipFormation.combineRewardFrame(case.long("produced"), case.long("count"))))
                    "jewel_block" -> EquipFormation.jewelBlock(case.arr("record"), case.str("tail").hexBytes()).toHexString()
                    "jewel_block_record" -> EquipFormation.jewelBlockRecord(case.str("raw").hexBytes()).let { (r, t) -> jarr(r, t.toHexString()) }
                    "gem_counts" -> JArr(EquipFormation.gemCounts(case.obj("section")).entries.mapTo(ArrayList()) { jarr(it.key, it.value) })
                    "gems_section_with" -> EquipFormation.gemsSectionWith(LinkedHashMap<Long, Long>().also { m -> case.arr("counts").forEach { m[it.asArr[0].long] = it.asArr[1].long } })
                    "decode_jewel_list" -> JArr(EquipFormation.decodeJewelList(case.str("payload").hexBytes()).toMutableList<JValue>())
                    else -> EquipFormation.seedJewelryDocument(case.str("payload").hexBytes(), "session_login_s3072")
                }
            }
            tally.check(label, case["expected"], out)
        }
        tally.done()
    }

    // --- planners ------------------------------------------------------------------------------------------------------

    private fun viewOf(exported: JObj, gemTypes: Map<Long, Long>): EquipFormation.View {
        val equipment = LinkedHashMap<Long, JArr>()
        exported.arr("equipment").forEach { equipment[it.asArr[0].long] = it.asArr[1].asArr.deepCopy() }
        val heroes = LinkedHashMap<Long, Map<Long, JValue?>>()
        exported.arr("heroes").forEach { h ->
            heroes[h.asArr[0].long] = LinkedHashMap<Long, JValue?>().also { m -> h.asArr[1].asArr.forEach { m[it.asArr[0].long] = it.asArr[1] } }
        }
        return EquipFormation.View(exported.arr("formation").deepCopy(), exported.getValue("captain_slot"), exported.arr("offline_hero_uids").deepCopy(),
            exported.arr("bag_equipment_uids").deepCopy(), equipment, heroes, exported.obj("gems").deepCopy(), gemTypes, exported.arr("jewel_list").deepCopy())
    }

    private fun planCall(opcode: Int, request: JObj, view: EquipFormation.View, leaders: List<Long>, excluded: List<Long>): EquipFormation.FormationPlan =
        when (opcode) {
            67 -> EquipFormation.planLineup(request, view, excludedUids = excluded, leaderUids = leaders)
            35 -> EquipFormation.planPosition(request, view)
            65 -> EquipFormation.planCaptain(request, view)
            79 -> EquipFormation.planEquip(request, view, inputs::equipPosition)
            2625 -> EquipFormation.planJewel(request, view, inputs::jewelPosition)
            1217 -> EquipFormation.planRuneEquip(request, view)
            1219 -> EquipFormation.planRuneUnequip(request, view)
            else -> EquipFormation.planRuneCombine(request, view, inputs.gemRows())
        }

    private fun planJson(plan: EquipFormation.FormationPlan): JObj {
        val v = plan.view
        return jobj("data" to plan.data, "packets" to framesJson(plan.packets), "view" to jobj("formation" to v.formation,
            "captain_slot" to v.captainSlot, "offline_hero_uids" to v.offlineHeroUids, "bag_equipment_uids" to v.bagEquipmentUids,
            "gems" to v.gems, "jewel_list" to v.jewelList))
    }

    @Test
    fun `formation planners on the saves and their variations`() {
        val doc = load("equip_formation_planners")
        val gemTypes = inputs.gemTypes()
        val tally = Tally("equip_formation_planners")
        val views = doc.arr("views").map { it.asObj }
        for (view in views) {
            val built = viewOf(view.obj("view"), gemTypes)
            val leaders = built.heroes.filter { (_, values) -> inputs.isLeader(values[1L]) }.keys.sorted()
            tally.same("leaders ${view.str("name")}", view["leaders"], jvalue(leaders))
        }
        for (c in doc.arr("cases")) {
            val case = c.asObj
            val view = views[case.long("view").toInt()]
            val built = viewOf(view.obj("view"), gemTypes)
            val before = text(planJson(EquipFormation.FormationPlan(built, JObj(), emptyList())))
            val opcode = case.long("opcode").toInt()
            val label = "${view.str("name").takeLast(48)} C$opcode ${text(case["request"])}"
            val excluded = (case["excluded"] as? JArr)?.map { it.long } ?: emptyList()
            val out = outcome { planJson(planCall(opcode, case.obj("request"), built, longs(view["leaders"]), excluded)) }
            tally.check(label, case["expected"], out)
            if (text(planJson(EquipFormation.FormationPlan(built, JObj(), emptyList()))) != before) tally.fail(label, "the planner changed its input view")
        }
        tally.done()
    }

    // --- store transaction ---------------------------------------------------------------------------------------------

    @Test
    fun `formation store transaction chains on copies of the saves`() {
        val doc = load("store_formation")
        val driver = JdbcSqlDriver()
        val tally = Tally("store_formation")
        val tmp = Files.createTempDirectory("g3-formation-store")
        try {
            for ((n, c) in doc.arr("cases").withIndex()) {
                val case = c.asObj
                val copy = tmp.resolve("$n.sqlite3")
                Files.copy(dev.resolve(case.str("save")), copy)
                val store = StateStore(copy, driver)
                val steps = case.arr("steps").map { it.asObj }
                val expected = case.arr("expected")
                for ((i, step) in steps.withIndex()) {
                    val opcode = step.long("opcode").toInt()
                    val label = "${case.str("save").takeLast(40)} #$i C$opcode ${step.str("payload")}"
                    val policy = if ("policy" in step) null else FreshProfile.DeploymentPolicy(store.read().characterProfile!!)
                    val characterId = step.strOrNull("character_id") ?: case.str("character_id")
                    val served = (step["served"] as? JStr)?.value?.hexBytes()
                    val out = outcome {
                        val request = EquipFormation.decodeRequest(opcode, step.str("payload").hexBytes())
                        val result = store.formationTransaction(opcode, request, characterId, policy, inputs, "authenticated-client",
                            "Native opcode$opcode formation change", servedJewelList = served, servedJewelSource = step.strOrNull("source"))
                        val summary = JObj(LinkedHashMap(result.result.map.filterKeys { it != "timestamp_utc" })).also { it["action"] = JStr(result.action) }
                        jobj("result" to summary, "plan" to jobj("data" to result.plan.data, "packets" to framesJson(result.plan.packets)))
                    }
                    val exp = expected[i].asObj
                    if ("result" in exp && out is Outcome.Ok) {
                        val got = out.value as JObj
                        tally.same("$label result", exp["result"], got["result"])
                        tally.same("$label plan", exp["plan"], got["plan"])
                        val after = store.read()
                        val history = driver.open(copy, SqlDriver.Mode.READ_ONLY).use { db ->
                            val row = db.queryOne("SELECT revision, action, detail_json, payload_sha256 FROM state_history ORDER BY revision DESC LIMIT 1")!!
                            jobj("revision" to row.long("revision"), "action" to row.string("action"), "detail_json" to row.string("detail_json"),
                                "payload_sha256" to row.string("payload_sha256"))
                        }
                        tally.same("$label after", exp["after"], jobj("state" to after.state, "revision" to after.revision,
                            "jewelry_list" to after.jewelryList, "history" to history))
                    } else {
                        tally.check(label, exp, out)
                    }
                }
            }
        } finally {
            tmp.toFile().deleteRecursively()
        }
        tally.done()
    }
}
