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
import io.github.okexodus.openknights.exact.sha256Hex
import io.github.okexodus.openknights.exact.toHexString
import io.github.okexodus.openknights.gamedata.ApkTables
import io.github.okexodus.openknights.gamedata.GameTables
import io.github.okexodus.openknights.server.DeviceClock
import io.github.okexodus.openknights.server.store.StateStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Local only (OPENKNIGHTS_DEV_DIR with `vectors-g8/sweep_*.json` from the maintainer's private exporter,
 * OPENKNIGHTS_ORIGINALS with the player's APK): the sweep-feature planners + Event Hall Shop replayed on the
 * reference's own outputs. Every frame, plan, document, refusal and state must be identical. Open Great Offer spins
 * are skipped (they need agent A's `EventHall.planGreatOffer`); the count is reported.
 */
class G8SweepVectorsTest {
    private val dev: Path? = System.getenv("OPENKNIGHTS_DEV_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
    private val originals: Path? = System.getenv("OPENKNIGHTS_ORIGINALS")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
    private val failures = ArrayList<String>()
    private var checks = 0
    private var skipped = 0

    @AfterEach
    fun reset() {
        DeviceClock.active = null
        Events.setActive(null)
    }

    private fun file(name: String): Path? = dev?.resolve("vectors-g8")?.resolve("sweep_$name.json")
    private fun vectors(name: String): JObj = Json.loads(Files.readString(file(name)!!)).asObj
    private fun available(vararg names: String): Boolean =
        dev != null && originals != null && names.all { n -> file(n)?.let { Files.isRegularFile(it) } == true }
    private fun inputs() = DailyInputs(GameTables(ApkTables(originals!!.resolve("com.enjoygame.hero2d.apk"))))

    private fun check(label: String, expected: Any?, actual: Any?) {
        checks++
        if (expected != actual) failures.add("$label\n  expected: ${expected.toString().take(1200)}\n  actual:   ${actual.toString().take(1200)}")
    }

    private fun compact(v: JValue?): String = Json.dumps(v ?: JNull, itemSeparator = ",", keySeparator = ":")
    private fun sha(v: JValue?): String = sha256Hex(compact(v).toByteArray(Charsets.UTF_8)).take(24)

    private lateinit var blobs: JObj
    private fun framesOf(recorded: JValue): List<String> = recorded.asArr.map { f -> "${f.asArr[0]}:${blobs.str((f.asArr[1] as JStr).value)}" }
    private fun framesOf(frames: List<Frame>): List<String> = frames.map { "${it.first}:${it.second.toHexString()}" }

    private fun isError(recorded: JValue?): Boolean =
        (recorded as? JObj)?.let { it.containsKey("error") && it.containsKey("message") && it.containsKey("value_error") } == true
    private fun isUnchanged(recorded: JValue?): Boolean = (recorded as? JObj)?.get("unchanged") == JBool(true)

    private fun report(name: String) {
        println("$name: $checks checks, $skipped skipped, ${failures.size} failures")
        assertTrue(failures.isEmpty()) { "$name: ${failures.size} of $checks checks differ:\n" + failures.take(25).joinToString("\n") }
    }

    private fun installClock(spec: JObj) {
        val offsets = spec.arr("offsets").map { it.asArr }
        val hwm = spec["hwm"]?.takeIf { it != JNull }?.let { (it as JInt).value.toLong() }
        val clock = DeviceClock(null, timeSource = { hwm ?: 0L }, offsetSource = { epoch ->
            var value = 0
            for (o in offsets) if (o[0] == JNull || epoch >= (o[0] as JInt).value.toLong()) value = (o[1] as JInt).value.toInt()
            value
        })
        if (hwm != null) clock.now()
        DeviceClock.active = clock
    }

    private lateinit var freshSystems: Map<Int, List<ByteArray>>

    private fun loadCommon() {
        val doc = vectors("saves")
        freshSystems = doc.obj("fresh_systems").entries.associate { (op, list) -> op.toInt() to list.asArr.map { (it as JStr).value.hexBytes() } }
        Events.setActive(doc.obj("events"))
    }

    private fun seeds(hasProfile: Boolean): SystemSeeds.SeedFrames? =
        if (hasProfile) SystemSeeds.SeedFrames(freshSystems, "fresh_systems_template") else null

    private fun seedsOf(save: JObj): SystemSeeds.SeedFrames? = seeds(save["character_profile"].let { it != null && it != JNull })

    private fun currentOf(save: JObj): StateStore.Current {
        val docs = LinkedHashMap<String, JValue?>()
        for ((k, v) in save.obj("documents")) docs[k] = v.deepCopy()
        return StateStore.Current((save["revision"] as JInt).value.toLong(), "", "", save.obj("state").deepCopy(), ByteArray(0), 0, null,
            save.arr("inventory_items").deepCopy(), emptyList(), save.arr("acquired_items").deepCopy(), emptyList(), null, null, null,
            (save["character_profile"] as? JObj)?.deepCopy(), null, docs).also { cur ->
            cur.jewelEntriesView = (save["jewel_entries"] as? JArr)?.deepCopy()
        }
    }

    private fun hashDiff(expected: JValue?, actual: JObj): String {
        val exp = expected as? JObj ?: return compact(actual)
        val keys = (exp.keys + actual.keys).toSortedSet().filter { exp[it] != actual[it] }
        return if (keys.isEmpty()) "same" else "differ: $keys"
    }

    private fun sectionHashes(state: JObj): JObj {
        val out = jobj("state" to sha(state))
        for ((key, value) in state) {
            if (key == "subsystems") for ((name, section) in value.asObj) out["subsystems.$name"] = JStr(sha(section))
            else out[key] = JStr(sha(value))
        }
        return out
    }

    private fun ownedRecord(owned: Owned): JObj = jobj(
        "item_changes" to JArr(owned.itemChanges.entries.mapTo(ArrayList()) { (u, c) -> jarr(u, c) }),
        "new_items" to JArr(owned.newItems.entries.mapTo(ArrayList()) { (u, e) -> jarr(u, e.first, e.second) }),
        "role_changes" to JArr(owned.roleChanges.entries.mapTo(ArrayList()) { (f, c) -> jarr(f, c.first, c.second) }),
        "log" to owned.log,
        "granted" to JArr(owned.granted.entries.mapTo(ArrayList()) { (t, nn) -> jarr(t, nn) }),
        "heroes_added" to owned.heroesAdded, "equipment_added" to owned.equipmentAdded,
        "god" to (owned.godDocument?.let { sha(it) }))

    private fun compareError(label: String, recorded: JValue?, e: Exception) {
        if (!isError(recorded)) {
            failures.add("$label: unexpected ${e::class.simpleName}: ${e.message}")
            return
        }
        val rec = recorded as JObj
        check("$label error kind", rec["value_error"] == JBool(true), e is IllegalArgumentException)
        if (e is IllegalArgumentException) check("$label error", rec.str("message"), e.message)
        val code = rec["code"]
        if (code != null && code != JNull) check("$label code", (code as JInt).value.toInt(), (e as? Acquisition.Rejected)?.code)
    }

    private fun compareOwned(label: String, recorded: JObj, owned: Owned?) {
        if (owned == null || !recorded.containsKey("owned")) return
        check("$label owned", compact(recorded["owned"]), compact(ownedRecord(owned)))
        check("$label hashes", "same", hashDiff(recorded["hashes"], sectionHashes(owned.state)))
    }

    /** Replay a planner (may raise Unchanged / a refusal / NotPorted). [ownedRef] carries the Owned built inside [block]. */
    private fun replayPlan(label: String, recorded: JObj, ownedRef: Array<Owned?>, block: () -> Plan) {
        val result = try {
            block()
        } catch (e: NotPorted) {
            skipped++; return
        } catch (e: SweepFeatures.Unchanged) {
            if (!isUnchanged(recorded)) { failures.add("$label: expected non-unchanged, got Unchanged"); return }
            check("$label frames", framesOf(recorded["frames"]!!), framesOf(e.packets))
            check("$label fields", compact(recorded["fields"]), compact(e.fields))
            compareOwned(label, recorded, ownedRef[0])
            return
        } catch (e: Exception) {
            compareError(label, recorded, e); return
        }
        if (isError(recorded)) { failures.add("$label: expected error ${(recorded).str("error")}"); return }
        if (isUnchanged(recorded)) { failures.add("$label: expected Unchanged"); return }
        check("$label plan", compact(recorded["plan"]), compact(result.data))
        check("$label frames", framesOf(recorded["frames"]!!), framesOf(result.packets))
        compareOwned(label, recorded, ownedRef[0])
    }

    // --- stateless ------------------------------------------------------------------------------------------------------

    @Test
    fun `stateless replies`() {
        assumeTrue(available("saves", "stateless"), "OPENKNIGHTS_DEV_DIR / OPENKNIGHTS_ORIGINALS not set: local-only test skipped")
        loadCommon()
        val doc = vectors("stateless")
        blobs = doc.obj("blobs")
        for ((i, v) in doc.arr("replies").withIndex()) {
            val vector = v.asObj
            val seeds = seeds(vector.str("context") == "fresh_seeds")
            val opcode = vector.long("opcode").toInt()
            val payload = vector.str("payload").hexBytes()
            val label = "reply $i ${vector.str("context")} C$opcode ${vector.str("payload")}"
            val recorded = vector["result"]
            try {
                val (frames, fields) = SweepFeatures.statelessReply(opcode, payload, null, seeds)
                if (isError(recorded)) { failures.add("$label: expected error"); continue }
                check("$label frames", framesOf((recorded as JObj)["frames"]!!), framesOf(frames))
                check("$label fields", compact(recorded["fields"]), compact(fields))
            } catch (e: Exception) {
                compareError(label, recorded, e)
            }
        }
        for ((i, v) in doc.arr("level_gift").withIndex()) {
            val vector = v.asObj
            val seeds = seeds(vector.str("context") == "fresh_seeds")
            check("level_gift $i ${vector.str("context")}", vector.str("frame"), SweepFeatures.levelGiftFrame(seeds).toHexString())
        }
        report("stateless")
    }

    // --- owned planners (album, signature, great offer) ---------------------------------------------------------------

    private fun ownedPlanners(name: String, plan: (JObj, Owned, StateStore.Current, DailyInputs) -> Plan) {
        assumeTrue(available("saves", name), "OPENKNIGHTS_DEV_DIR / OPENKNIGHTS_ORIGINALS not set: local-only test skipped")
        loadCommon()
        val doc = vectors(name)
        blobs = doc.obj("blobs")
        val inputs = inputs()
        for ((i, v) in doc.arr("vectors").withIndex()) {
            val vector = v.asObj
            val save = vector.obj("save")
            vector["clock"]?.let { installClock(it.asObj) }
            val label = "$name $i ${vector["case"] ?: ""} ${vector.str("payload")}"
            val ownedRef = arrayOfNulls<Owned>(1)
            replayPlan(label, vector.obj("result"), ownedRef) {
                val cur = currentOf(save)
                val owned = Owned(cur, inputs).also { ownedRef[0] = it }
                plan(vector, owned, cur, inputs)
            }
        }
        report(name)
    }

    @Test
    fun `album activate`() = ownedPlanners("album") { v, owned, cur, inputs ->
        SweepFeatures.planAlbumActivate(v.str("payload").hexBytes(), owned, cur, inputs, seedsOf(v.obj("save")))
    }

    @Test
    fun `signature`() = ownedPlanners("signature") { v, owned, _, inputs ->
        SweepFeatures.planSignature(v.str("payload").hexBytes(), owned, inputs)
    }

    @Test
    fun `great offer`() = ownedPlanners("great_offer") { v, owned, cur, inputs ->
        SweepFeatures.planGreatOfferSpin(v.str("payload").hexBytes(), owned, cur, inputs, v.long("now"))
    }

    // --- current-only planners (totem, tmp vip) -----------------------------------------------------------------------

    @Test
    fun `totem lineup`() {
        assumeTrue(available("saves", "totem"), "local-only test skipped")
        loadCommon()
        val doc = vectors("totem")
        blobs = doc.obj("blobs")
        for ((i, v) in doc.arr("vectors").withIndex()) {
            val vector = v.asObj
            val save = vector.obj("save")
            val label = "totem $i ${vector.str("case")} ${vector.str("payload")}"
            replayPlan(label, vector.obj("result"), arrayOfNulls(1)) {
                SweepFeatures.planTotemLineup(vector.str("payload").hexBytes(), currentOf(save), seedsOf(save))
            }
        }
        report("totem")
    }

    @Test
    fun `tmp vip claim`() {
        assumeTrue(available("saves", "tmp_vip"), "local-only test skipped")
        loadCommon()
        val doc = vectors("tmp_vip")
        blobs = doc.obj("blobs")
        for ((i, v) in doc.arr("vectors").withIndex()) {
            val vector = v.asObj
            val save = vector.obj("save")
            vector["clock"]?.let { installClock(it.asObj) }
            val label = "tmp_vip $i ${vector.str("case")} vip${vector["vip"]}"
            replayPlan(label, vector.obj("result"), arrayOfNulls(1)) {
                SweepFeatures.planTmpVipClaim(if (vector.str("case") == "payload") byteArrayOf(1) else ByteArray(0),
                    currentOf(save), seedsOf(save), vector.long("now"))
            }
        }
        report("tmp_vip")
    }

    // --- rebirth shop -------------------------------------------------------------------------------------------------

    @Test
    fun `rebirth shop`() {
        assumeTrue(available("saves", "rebirth"), "local-only test skipped")
        loadCommon()
        val doc = vectors("rebirth")
        blobs = doc.obj("blobs")
        val inputs = inputs()
        for ((i, v) in doc.arr("decode").withIndex()) {
            val vector = v.asObj
            val label = "decode $i C${vector.long("opcode")} ${vector.str("payload")}"
            val recorded = vector["result"]
            try {
                val r = RebirthShop.decodeRequest(vector.long("opcode").toInt(), vector.str("payload").hexBytes())
                if (isError(recorded)) failures.add("$label: expected error") else check(label, compact(recorded), compact(r))
            } catch (e: Exception) { compareError(label, recorded, e) }
        }
        for ((i, v) in doc.arr("refresh_limit").withIndex()) {
            val vector = v.asObj
            val label = "refresh_limit $i shop${vector.long("shop")} vip${vector.long("vip")}"
            check(label, (vector["result"] as JInt).value.toLong(), RebirthShop.refreshLimit(inputs, vector.long("shop"), JInt(vector.long("vip"))))
        }
        for (section in listOf("timer", "refresh", "buy")) {
            val opcode = mapOf("timer" to 3941, "refresh" to 3939, "buy" to 3937).getValue(section)
            for ((i, v) in doc.arr(section).withIndex()) {
                val vector = v.asObj
                val save = vector.obj("save")
                installClock(vector.obj("clock"))
                val label = "$section $i ${vector["case"] ?: ""} ${vector.str("payload")}"
                val ownedRef = arrayOfNulls<Owned>(1)
                replayPlan(label, vector.obj("result"), ownedRef) {
                    val cur = currentOf(save)
                    val owned = Owned(cur, inputs).also { ownedRef[0] = it }
                    SweepFeatures.planRebirthShop(opcode, vector.str("payload").hexBytes(), owned, cur, inputs,
                        seedsOf(save), vector.long("now"), save.str("owner_key"))
                }
            }
        }
        report("rebirth")
    }
}
