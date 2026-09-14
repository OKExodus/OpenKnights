package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
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
import io.github.okexodus.openknights.exact.sha256Hex
import io.github.okexodus.openknights.exact.toHexString
import io.github.okexodus.openknights.gamedata.ApkTables
import io.github.okexodus.openknights.gamedata.GameTables
import io.github.okexodus.openknights.server.store.StateStore
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path

/**
 * Local only (OPENKNIGHTS_DEV_DIR with `vectors-g9/campaign_*.json` from the maintainer's private exporter,
 * OPENKNIGHTS_ORIGINALS with the player's APK): the campaign battle routes + the two battle_stats additions replayed
 * on the reference's own outputs. Every frame, plan, reward, detail, document, refusal and byte payload must be
 * identical.
 */
class G9CampaignVectorsTest {
    private val dev: Path? = System.getenv("OPENKNIGHTS_DEV_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
    private val originals: Path? = System.getenv("OPENKNIGHTS_ORIGINALS")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
    private val failures = ArrayList<String>()
    private var checks = 0
    private val NOW = 1789000000L

    private fun file(name: String): Path? = dev?.resolve("vectors-g9")?.resolve("campaign_$name.json")
    private fun available(name: String): Boolean = dev != null && originals != null && file(name)?.let { Files.isRegularFile(it) } == true
    private fun vectors(name: String): JObj = Json.loads(Files.readString(file(name)!!)).asObj
    private fun inputs() = DailyInputs(GameTables(ApkTables(originals!!.resolve("com.enjoygame.hero2d.apk"))))

    private fun check(label: String, expected: Any?, actual: Any?) {
        checks++
        if (expected != actual) failures.add("$label\n  expected: ${expected.toString().take(1400)}\n  actual:   ${actual.toString().take(1400)}")
    }

    private fun compact(v: JValue?): String = Json.dumps(v ?: JNull, itemSeparator = ",", keySeparator = ":")
    private fun sha(v: JValue?): String = sha256Hex(compact(v).toByteArray(Charsets.UTF_8)).take(24)

    private lateinit var blobs: JObj
    private lateinit var saves: JObj
    private fun framesOf(recorded: JValue): List<String> = recorded.asArr.map { f -> "${f.asArr[0]}:${blobs.str((f.asArr[1] as JStr).value)}" }
    private fun framesOf(frames: List<Frame>): List<String> = frames.map { "${it.first}:${it.second.toHexString()}" }
    private fun save(key: String): JObj = saves.obj(key)

    private fun isError(recorded: JValue?): Boolean =
        (recorded as? JObj)?.let { it.containsKey("error") && it.containsKey("message") && it.containsKey("value_error") } == true

    private fun report(name: String) {
        println("$name: $checks checks, ${failures.size} failures")
        assertTrue(failures.isEmpty()) { "$name: ${failures.size} of $checks checks differ:\n" + failures.take(25).joinToString("\n") }
    }

    private fun load(name: String): JObj {
        val doc = vectors(name)
        blobs = doc.obj("blobs")
        saves = doc.obj("saves")
        return doc
    }

    private fun currentOf(save: JObj): StateStore.Current {
        val docs = LinkedHashMap<String, JValue?>()
        for ((k, v) in save.obj("documents")) docs[k] = v.deepCopy()
        return StateStore.Current((save["revision"] as JInt).value.toLong(), "", "", save.obj("state").deepCopy(), ByteArray(0), 0, null,
            save.arr("inventory_items").deepCopy(), emptyList(), save.arr("acquired_items").deepCopy(), emptyList(), null, null, null,
            (save["character_profile"] as? JObj)?.deepCopy(), null, docs)
    }

    private fun sectionHashes(state: JObj): JObj {
        val out = jobj("state" to sha(state))
        for ((key, value) in state) {
            if (key == "subsystems") for ((name, section) in value.asObj) out["subsystems.$name"] = JStr(sha(section))
            else out[key] = JStr(sha(value))
        }
        return out
    }

    private fun hashDiff(expected: JValue?, actual: JObj): String {
        val exp = expected as? JObj ?: return compact(actual)
        val keys = (exp.keys + actual.keys).toSortedSet().filter { exp[it] != actual[it] }
        return if (keys.isEmpty()) "same" else "differ: $keys"
    }

    private fun ownedRecord(owned: Owned): JObj = jobj(
        "item_changes" to JArr(owned.itemChanges.entries.mapTo(ArrayList()) { (u, c) -> jarr(u, c) }),
        "new_items" to JArr(owned.newItems.entries.mapTo(ArrayList()) { (u, e) -> jarr(u, e.first, e.second) }),
        "role_changes" to JArr(owned.roleChanges.entries.mapTo(ArrayList()) { (f, c) -> jarr(f, c.first, c.second) }),
        "granted" to JArr(owned.granted.entries.mapTo(ArrayList()) { (t, nn) -> jarr(t, nn) }),
        "heroes_added" to owned.heroesAdded, "equipment_added" to owned.equipmentAdded)

    private fun compareOwned(label: String, recorded: JObj, owned: Owned) {
        if (recorded.containsKey("owned")) check("$label owned", compact(recorded["owned"]), compact(ownedRecord(owned)))
        if (recorded.containsKey("hashes")) check("$label hashes", "same", hashDiff(recorded["hashes"], sectionHashes(owned.state)))
    }

    private fun compareError(label: String, recorded: JValue?, e: Exception) {
        if (!isError(recorded)) { failures.add("$label: unexpected ${e::class.simpleName}: ${e.message}"); return }
        val rec = recorded as JObj
        check("$label error kind", rec["value_error"] == io.github.okexodus.openknights.exact.JBool(true), e is IllegalArgumentException)
        if (e is IllegalArgumentException) check("$label error", rec.str("message"), e.message)
        val code = rec["code"]
        if (code != null && code != JNull) check("$label code", (code as JInt).value.toInt(), (e as? Acquisition.Rejected)?.code)
    }

    private fun freshDoc(): JObj = jobj("profile" to Campaign.PROFILE, "day" to null, "boxes" to JArr(), "regen_anchor" to null, "seed" to null)

    private fun ubits(d: Double): Long = java.lang.Double.doubleToRawLongBits(d)

    // --- SplitMix64 / battle_seed ---------------------------------------------------------------------------------------

    @Test
    fun `splitmix and battle seed`() {
        assumeTrue(available("splitmix"), "OPENKNIGHTS_DEV_DIR / OPENKNIGHTS_ORIGINALS not set: local-only test skipped")
        val doc = load("splitmix")
        for ((i, v) in doc.arr("splitmix").withIndex()) {
            val vec = v.asObj
            val seed = (vec["seed"] as JInt).value.toLong()
            val n = Campaign.SplitMix64(seed)
            check("splitmix $i next", vec.arr("next").map { (it as JInt).value.toString() }, (0 until 8).map { n.next().toString() })
            val r = Campaign.SplitMix64(seed)
            check("splitmix $i random", vec.arr("random_bits").map { (it as JInt).value.toLong() }, (0 until 6).map { ubits(r.random()) })
            val ri = Campaign.SplitMix64(seed)
            val expInts = vec.arr("randint_1_5").map { (it as JInt).value.toLong() }
            val actInts = (0 until 6).map { ri.randint(1, 5) } + Campaign.SplitMix64(seed).randint(0, 0)
            check("splitmix $i randint", expInts, actInts)
        }
        for ((i, v) in doc.arr("battle_seed").withIndex()) {
            val vec = v.asObj
            check("battle_seed $i", (vec["seed"] as JInt).value,
                Campaign.battleSeed(vec.str("cid"), vec.long("rev"), vec.long("stage"), NOW, vec.long("helper"), vec.long("slot")))
        }
        report("splitmix")
    }

    // --- roll_drops -----------------------------------------------------------------------------------------------------

    @Test
    fun `roll drops`() {
        assumeTrue(available("drops"), "local-only test skipped")
        val doc = load("drops")
        val inputs = inputs()
        for ((i, v) in doc.arr("vectors").withIndex()) {
            val vec = v.asObj
            val row = Campaign.stageRow(inputs, vec.long("stage")) ?: run { failures.add("drops $i unknown stage"); continue }
            val seed = (vec["seed"] as JInt).value.toLong()
            val drops = Campaign.rollDrops(row, Campaign.SplitMix64(seed xor 0x5CA1AB1EL), vec.bool("first_clear"))
            check("drops $i stage ${vec.long("stage")} seed ${(vec["seed"] as JInt).value} fc ${vec.bool("first_clear")}",
                compact(vec["drops"]), compact(JArr(drops.mapTo(ArrayList()) { jarr(it.first, it.second, it.third) })))
        }
        report("drops")
    }

    // --- checks ---------------------------------------------------------------------------------------------------------

    @Test
    fun `check stage and attempt`() {
        assumeTrue(available("checks"), "local-only test skipped")
        val doc = load("checks")
        val inputs = inputs()
        for ((i, v) in doc.arr("vectors").withIndex()) {
            val vec = v.asObj
            val stage = vec.long("stage")
            val level = vec["level"]?.takeIf { it != JNull }?.let { (it as JInt).value.toLong() }
            val label = "check_stage $i stage $stage level ${vec["level"]}"
            try {
                val owned = Owned(currentOf(save(vec.str("save"))), inputs)
                val (row, mode, recs) = Campaign.checkStage(owned, inputs, stage, level)
                val describe = jobj("mode" to mode, "row_116" to row["116"], "row_126" to row["126"], "recs" to recs.size)
                if (isError(vec["result"])) failures.add("$label: expected error") else check(label, compact(vec["result"]), compact(describe))
            } catch (e: Exception) { compareError(label, vec["result"], e) }
        }
        for ((i, v) in doc.arr("check_attempt").withIndex()) {
            val vec = v.asObj
            val mode = vec.long("mode")
            val record = (vec["record"] as? JArr)?.toList()
            val label = "check_attempt $i mode $mode record ${vec["record"]}"
            try {
                Campaign.checkAttempt(mode, record, inputs)
                if (isError(vec["result"])) failures.add("$label: expected error") else check(label, "ok", "ok")
            } catch (e: Exception) { compareError(label, vec["result"], e) }
        }
        report("checks")
    }

    // --- settle_win -----------------------------------------------------------------------------------------------------

    @Test
    fun `settle win`() {
        assumeTrue(available("settle"), "local-only test skipped")
        val doc = load("settle")
        val inputs = inputs()
        for ((i, v) in doc.arr("vectors").withIndex()) {
            val vec = v.asObj
            val stage = vec.long("stage")
            val label = "settle $i stage $stage ${vec.str("variant")}"
            val recorded = vec.obj("result")
            try {
                val cur = currentOf(save(vec.str("save")))
                val owned = Owned(cur, inputs)
                val row = Campaign.stageRow(inputs, stage)!!
                val document = freshDoc()
                val helper = if (vec.bool("helper")) WorldParticipants.Participant(424242, "", ByteArray(0), 0) else null
                val drops = vec.arr("drops").map { val a = it.asArr; Triple((a[0] as JStr).value, (a[1] as JInt).value.toLong(), (a[2] as JInt).value.toLong()) }
                val (frames, reward, detail) = Campaign.settleWin(owned, cur, inputs, stage, row, vec.long("mode"),
                    vec.long("stars"), NOW, document, helper, drops, 1, vec.bool("auto"))
                if (isError(recorded)) { failures.add("$label: expected error"); continue }
                check("$label frames", framesOf(recorded["frames"]!!), framesOf(frames))
                check("$label reward", compact(recorded["reward"]), compact(reward))
                check("$label detail", compact(recorded["detail"]), compact(detail))
                check("$label document", compact(recorded["document"]), compact(document))
                compareOwned(label, recorded, owned)
            } catch (e: Exception) { compareError(label, recorded, e) }
        }
        report("settle")
    }

    // --- plan_auto / plan_battle / plan_star_box / plan_reentry ---------------------------------------------------------

    private fun replayPlan(label: String, recorded: JValue?, owned: Owned, block: () -> Plan) {
        val plan = try { block() } catch (e: Exception) { compareError(label, recorded, e); return }
        val rec = recorded as? JObj
        if (rec == null || isError(recorded)) { failures.add("$label: expected error, got a plan"); return }
        check("$label plan", compact(rec["plan"]), compact(plan.data))
        check("$label frames", framesOf(rec["frames"]!!), framesOf(plan.packets))
        compareOwned(label, rec, owned)
    }

    @Test
    fun `plan auto`() {
        assumeTrue(available("auto"), "local-only test skipped")
        val doc = load("auto")
        val inputs = inputs()
        for ((i, v) in doc.arr("vectors").withIndex()) {
            val vec = v.asObj
            val stage = vec.long("stage")
            val cur = currentOf(save(vec.str("save")))
            val owned = Owned(cur, inputs)
            val request = jobj("stage" to stage, "helper" to 0, "slot" to 6, "auto_fuse" to 0, "count" to vec.long("count"))
            replayPlan("auto $i stage $stage", vec["result"], owned) {
                Campaign.planAuto(request, owned, cur, inputs, freshDoc(), NOW, Campaign.SplitMix64(0x1234L xor 0x5CA1AB1EL))
            }
        }
        report("auto")
    }

    @Test
    fun `plan battle`() {
        assumeTrue(available("battle"), "local-only test skipped")
        val doc = load("battle")
        val inputs = inputs()
        for ((i, v) in doc.arr("vectors").withIndex()) {
            val vec = v.asObj
            val stage = vec.long("stage")
            val request = jobj("stage" to stage, "helper" to 0, "slot" to 6)
            val seed = (vec["seed"] as JInt).value.toLong()
            run {
                val cur = currentOf(save(vec.str("save")))
                val owned = Owned(cur, inputs)
                replayPlan("battle $i win stage $stage", vec["win"], owned) {
                    Campaign.planBattle(request, owned, cur, inputs, freshDoc(), NOW, null, null, null, seed)
                }
            }
            run {
                val cur = currentOf(save(vec.str("save")))
                val owned = Owned(cur, inputs)
                replayPlan("battle $i loss stage $stage", vec["loss"], owned) {
                    Campaign.planBattle(request, owned, cur, inputs, freshDoc(), NOW, null, null, null, seed, vec.obj("forced"))
                }
            }
        }
        report("battle")
    }

    @Test
    fun `plan star box reentry regen`() {
        assumeTrue(available("boxes"), "local-only test skipped")
        val doc = load("boxes")
        val inputs = inputs()
        for ((i, v) in doc.arr("star_box").withIndex()) {
            val vec = v.asObj
            val cur = currentOf(save(vec.str("save")))
            val owned = Owned(cur, inputs)
            val document = freshDoc().also { it["boxes"] = JArr(vec.arr("doc_boxes").toMutableList()) }
            replayPlan("star_box $i box ${vec.long("box")}", vec["result"], owned) {
                Campaign.planStarBox(jobj("stage" to vec.long("box")), owned, inputs, document)
            }
        }
        for ((i, v) in doc.arr("reentry").withIndex()) {
            val vec = v.asObj
            val cur = currentOf(save(vec.str("save")))
            val owned = Owned(cur, inputs)
            replayPlan("reentry $i stage ${vec.long("stage")}", vec["result"], owned) {
                Campaign.planReentry(jobj("stage" to vec.long("stage")), owned, inputs, freshDoc(), NOW, NOW)
            }
        }
        for ((i, v) in doc.arr("regen").withIndex()) {
            val vec = v.asObj
            val cur = currentOf(save(vec.str("save")))
            val owned = Owned(cur, inputs)
            val anchor = vec["anchor"]?.takeIf { it != JNull }?.let { (it as JInt).value.toLong() }
            val document = freshDoc().also { it["regen_anchor"] = anchor?.let { a -> JInt(a) } ?: JNull; it["energy_regen_anchor"] = anchor?.let { a -> JInt(a) } ?: JNull }
            val frame = Campaign.regen(owned, document, inputs, NOW)
            val expFrame = vec["frame"]
            val actFrame = frame?.let { jarr(it.first, it.second.toHexString()) }
            val expStr = if (expFrame == null || expFrame == JNull) "null" else "${(expFrame as JArr)[0]}:${blobs.str((expFrame[1] as JStr).value)}"
            val actStr = if (actFrame == null) "null" else "${actFrame[0]}:${(actFrame[1] as JStr).value}"
            check("regen $i frame", expStr, actStr)
            check("regen $i document", compact(vec["document"]), compact(document))
            check("regen $i owned", compact(vec["owned"]), compact(ownedRecord(owned)))
        }
        report("boxes")
    }

    // --- battle_actors / lineup_view ------------------------------------------------------------------------------------

    @Test
    fun `battle actors and lineup view`() {
        assumeTrue(available("lineup"), "local-only test skipped")
        val doc = load("lineup")
        val inputs = inputs()
        for ((i, v) in doc.arr("battle_actors").withIndex()) {
            val vec = v.asObj
            val label = "battle_actors $i ${vec.str("world")}"
            try {
                val world = if (vec.str("world") == "unserved") BattleStats.unservedWorld() else null
                val actors = BattleStats.battleActors(save(vec.str("save")).obj("state").deepCopy(), inputs, world)
                if (isError(vec["result"])) failures.add("$label: expected error") else check(label, compact(vec["result"]), compact(actors))
            } catch (e: Exception) { compareError(label, vec["result"], e) }
        }
        for ((i, v) in doc.arr("lineup_view").withIndex()) {
            val vec = v.asObj
            val label = "lineup_view $i"
            try {
                val payload = BattleStats.lineupViewPayload(save(vec.str("save")).obj("state").deepCopy(), inputs)
                if (isError(vec["payload"])) failures.add("$label: expected error") else check(label, (vec["payload"] as JStr).value, payload.toHexString())
            } catch (e: Exception) { compareError(label, vec["payload"], e) }
        }
        report("lineup")
    }
}
