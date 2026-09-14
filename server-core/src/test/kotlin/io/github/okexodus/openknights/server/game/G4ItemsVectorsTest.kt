package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JBool
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JNull
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.PyRandom
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
 * Local only (OPENKNIGHTS_DEV_DIR with `vectors-g4/items_*.json` from the maintainer's private exporter,
 * OPENKNIGHTS_ORIGINALS with the player's APK): the items slice replayed on the reference's own outputs — every planner
 * of C73 / C4099 / C803 / C801 / C321 / C1251 / C1249 / C2051 / C2633 / C3137 / C2055 / C2631 through
 * [AcquisitionRoutes.plannerFor] on the recorded saves (as they are and primed), the fuse luck query C1253, and the pure
 * rules (item use classes, box profiles and draws, fresh hero maps, luck lists, Combine attributes, Summon Report frames).
 */
class G4ItemsVectorsTest {
    private val dev: Path? = System.getenv("OPENKNIGHTS_DEV_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
    private val originals: Path? = System.getenv("OPENKNIGHTS_ORIGINALS")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
    private val failures = ArrayList<String>()
    private var checks = 0

    @AfterEach
    fun reset() {
        DeviceClock.active = null
    }

    private fun vectors(name: String): JObj = Json.loads(Files.readString(dev!!.resolve("vectors-g4").resolve("items_$name.json"))).asObj

    private fun available(name: String): Boolean =
        dev != null && originals != null && Files.isRegularFile(dev.resolve("vectors-g4").resolve("items_$name.json"))

    private fun check(label: String, expected: Any?, actual: Any?) {
        checks++
        if (expected != actual) failures.add("$label\n  expected: ${expected.toString().take(1500)}\n  actual:   ${actual.toString().take(1500)}")
    }

    private fun compact(v: JValue?): String = Json.dumps(v ?: JNull, itemSeparator = ",", keySeparator = ":")

    private fun sha(v: JValue?): String = sha256Hex(compact(v).toByteArray(Charsets.UTF_8)).take(24)

    private lateinit var blobs: JObj

    private fun framesOf(recorded: JValue): List<String> = recorded.asArr.map { f -> "${f.asArr[0]}:${blobs.str((f.asArr[1] as JStr).value)}" }

    private fun framesOf(frames: List<Frame>): List<String> = frames.map { "${it.first}:${it.second.toHexString()}" }

    private fun isError(recorded: JValue?): Boolean =
        (recorded as? JObj)?.let { it.containsKey("error") && it.containsKey("message") && it.containsKey("value_error") } == true

    private fun report(name: String) {
        println("$name: $checks checks, ${failures.size} failures")
        assertTrue(failures.isEmpty()) { "$name: ${failures.size} of $checks checks differ:\n" + failures.take(25).joinToString("\n") }
    }

    /** Run [block]; compare an error with the recorded error (kind, message, code), else hand the result to [compare]. */
    private fun <T> replay(label: String, recorded: JValue?, block: () -> T, compare: (JValue, T) -> Unit) {
        val result = try {
            block()
        } catch (e: NotPorted) {
            throw e
        } catch (e: Exception) {
            if (!isError(recorded)) failures.add("$label: unexpected ${e::class.simpleName}: ${e.message}\n${e.stackTrace.take(10).joinToString("\n")}")
            else {
                val rec = recorded as JObj
                check("$label error kind (${rec.str("error")}: ${rec.str("message")} / ${e::class.simpleName}: ${e.message})",
                    rec["value_error"] == JBool(true), e is IllegalArgumentException)
                if (e is IllegalArgumentException && rec["value_error"] == JBool(true)) check("$label error", rec.str("message"), e.message)
                val code = rec["code"]
                if (code != null && code != JNull) check("$label code", (code as JInt).value.toInt(), (e as? Acquisition.Rejected)?.code)
            }
            return
        }
        if (isError(recorded)) {
            failures.add("$label: expected ${(recorded as JObj).str("error")} (${recorded.str("message")})")
            return
        }
        compare(recorded!!, result)
    }

    private var tablesCache: DailyInputs? = null

    private fun inputs(): DailyInputs = tablesCache ?: DailyInputs(GameTables(ApkTables(originals!!.resolve("com.enjoygame.hero2d.apk")))).also { tablesCache = it }

    // --- saves ---

    private lateinit var saves: Map<String, JObj>
    private lateinit var policy: JObj

    private fun loadSaves() {
        if (::saves.isInitialized) return
        val doc = vectors("saves")
        saves = doc.arr("saves").associate { it.asObj.str("id") to it.asObj }
        policy = doc.obj("policy")
    }

    /** The exporter's `apply(save, overrides)`. */
    private fun apply(save: JObj, ov: JObj): JObj {
        val out = save.deepCopy()
        val state = out.obj("state")
        ov["capacity"]?.let { state["item_capacity_values"] = it.deepCopy() }
        (ov["roles"] as? JObj)?.forEach { (field, bits) ->
            for (f in state.arr("role_properties")) if (f.asObj["id"] == JInt(field.toLong())) f.asObj.obj("value")["bits"] = bits
        }
        (ov["items"] as? JArr)?.forEach { i ->
            val a = i.asArr
            state.arr("items").add(jobj("wire_values" to jarr(a[0], a[1], a[2]), "timed_flag" to a[3]))
        }
        (ov["heroes"] as? JArr)?.forEach { fields ->
            state.arr("heroes").add(fields.deepCopy())
            state.arr("offline_hero_uids").add(fields.asArr[0].asObj.obj("value").getValue("bits"))
        }
        (ov["equipment"] as? JArr)?.forEach { r -> state.arr("equipment").add(jobj("offset" to null, "wire_values" to r.deepCopy())) }
        state["bag_equipment_uids"] = JArr((state.arr("bag_equipment_uids") + ((ov["bag"] as? JArr) ?: JArr())).toMutableList())
        (ov["documents"] as? JObj)?.forEach { (table, doc) -> if (doc == JNull) out.obj("documents").remove(table) else out.obj("documents")[table] = doc.deepCopy() }
        (ov["achievements"] as? JArr)?.let { state.obj("subsystems").obj("achievements")["entries"] = it.deepCopy() }
        if (ov["no_profile"] == JBool(true)) out["character_profile"] = JNull
        if (ov["no_god"] == JBool(true)) out["god_skills"] = JNull
        return out
    }

    private fun jewelView(save: JObj, ov: JObj): JArr? {
        if ("jewels" in ov) return ov["jewels"] as? JArr
        val doc = (save["jewelry_list"] as? JObj)?.get("document") as? JObj ?: return null
        return doc.arr("entries")
    }

    private fun currentOf(save: JObj, jewels: JArr?): StateStore.Current {
        val docs = LinkedHashMap<String, JValue?>()
        for ((k, v) in save.obj("documents")) docs[k] = v.deepCopy()
        return StateStore.Current((save["revision"] as JInt).value.toLong(), "", "", save.obj("state").deepCopy(), ByteArray(0), 0, null,
            save.arr("inventory_items").deepCopy(), emptyList(), save.arr("acquired_items").deepCopy(), emptyList(), null, null,
            (save["god_skills"] as? JObj)?.deepCopy(), (save["character_profile"] as? JObj)?.deepCopy(), (save["jewelry_list"] as? JObj)?.deepCopy(),
            docs).also { it.jewelEntriesView = jewels?.deepCopy() }
    }

    private fun deployment(save: JObj): FreshProfile.DeploymentPolicy =
        FreshProfile.DeploymentPolicy((save["character_profile"] as? JObj) ?: jobj("document" to jobj("character_id" to "x"), "document_sha256" to "x"))

    private fun sectionHashes(state: JObj): JObj {
        val out = jobj("state" to sha(state))
        for ((key, value) in state) {
            if (key == "subsystems") for ((name, section) in value.asObj) out["subsystems.$name"] = JStr(sha(section))
            else out[key] = JStr(sha(value))
        }
        return out
    }

    private fun ownedState(owned: Owned): JObj = jobj(
        "item_changes" to owned.itemChanges.map { (u, c) -> jarr(u, c) },
        "new_items" to owned.newItems.map { (u, e) -> jarr(u, e.first, e.second) },
        "role_changes" to owned.roleChanges.map { (f, v) -> jarr(f, v.first, v.second) },
        "log" to owned.log, "heroes_added" to owned.heroesAdded, "heroes_removed" to owned.heroesRemoved,
        "equipment_added" to owned.equipmentAdded, "granted" to owned.granted.map { (t, c) -> jarr(t, c) },
        "god" to owned.godDocument?.let { sha(it) })

    private fun routes(part: String) {
        assumeTrue(available(part) && available("saves"), "OPENKNIGHTS_DEV_DIR / OPENKNIGHTS_ORIGINALS not set: local-only test skipped")
        loadSaves()
        val doc = vectors(part)
        blobs = doc.obj("blobs")
        val inputs = inputs()
        for ((i, v) in doc.arr("vectors").withIndex()) {
            val vector = v.asObj
            val save0 = saves.getValue(vector.str("save"))
            val ov = vector.obj("overrides")
            val save = apply(save0, ov)
            val current = currentOf(save, jewelView(save, ov))
            val opcode = vector.long("opcode").toInt()
            val payload = vector.str("payload").hexBytes()
            val now = vector.long("now")
            val label = "$part $i ${save0.str("label")} C$opcode ${vector.str("payload")}"
            replay(label, vector["result"], {
                val routed = AcquisitionRoutes.plannerFor(opcode, payload, inputs, null, if (vector["policy"] == JBool(true)) policy else null,
                    deployment(save), now) { now }
                val owned = Owned(current, inputs, save.arr("retired").map { it.long })
                Triple(routed, routed.planner(owned, current), owned)
            }) { recorded, (routed, plan, owned) ->
                val rec = recorded.asObj
                check("$label action", rec.str("action"), routed.action)
                check("$label request", compact(rec["request"]), compact(routed.request))
                check("$label plan", compact(rec["plan"]), compact(plan.data))
                check("$label packets", framesOf(rec["packets"]!!), framesOf(plan.packets))
                check("$label owned", compact(rec["owned"]), compact(ownedState(owned)))
                check("$label sections", compact(rec["sections"]), compact(sectionHashes(current.state)))
            }
        }
        report("items $part")
    }

    @Test
    fun `item use C73`() = routes("use")

    @Test
    fun `choose box C4099`() = routes("choose")

    @Test
    fun `merge C803 and C801`() = routes("merge")

    @Test
    fun `summon C321`() = routes("summon")

    @Test
    fun `refine fuse and Combine`() = routes("compose")

    @Test
    fun `fuse luck query C1253`() {
        assumeTrue(available("luck") && available("saves"), "OPENKNIGHTS_DEV_DIR / OPENKNIGHTS_ORIGINALS not set: local-only test skipped")
        loadSaves()
        val doc = vectors("luck")
        blobs = doc.obj("blobs")
        for ((i, v) in doc.arr("vectors").withIndex()) {
            val vector = v.asObj
            val save = apply(saves.getValue(vector.str("save")), vector.obj("overrides"))
            val current = currentOf(save, null)
            val label = "luck $i"
            replay(label, vector["result"], { AcquisitionRoutes.fuseLuckReply(vector.str("payload").hexBytes(), current) }) { recorded, (packets, fields) ->
                val rec = recorded.asObj
                check("$label packets", framesOf(rec["packets"]!!), framesOf(packets))
                check("$label fields", compact(rec["fields"]), compact(fields))
            }
        }
        report("fuse luck query")
    }

    @Test
    fun `item rules`() {
        assumeTrue(available("rules"), "OPENKNIGHTS_DEV_DIR / OPENKNIGHTS_ORIGINALS not set: local-only test skipped")
        val doc = vectors("rules")
        blobs = doc.obj("blobs")
        val inputs = inputs()
        for (v in doc.arr("classify")) {
            val vector = v.asObj
            val t = vector.long("template")
            replay("classify $t", vector["result"], { Acquisition.classifyUse(inputs.item(t), inputs) }) { rec, out -> check("classify $t", compact(rec), compact(out)) }
        }
        for (v in doc.arr("boxes")) {
            val vector = v.asObj
            val group = vector.long("group")
            val rows = inputs.boxGroup(group)
            replay("box $group profile", vector["profile"], { Acquisition.boxProfile(rows) }) { rec, out -> check("box $group profile", compact(rec), compact(out)) }
            for (d in vector.arr("draws")) {
                val draw = d.asObj
                val seed = (draw["seed"] as JInt).value
                replay("box $group draw $seed", draw["result"], {
                    JArr(Acquisition.drawBox(rows, PyRandom.seeded(seed)).mapTo(ArrayList()) { (s, r, o) -> jarr(s, r, jarr(o.kind, o.id, o.count)) })
                }) { _, out -> check("box $group draw $seed", compact(draw["result"]), compact(out)) }
            }
        }
        for (v in doc.arr("beads")) {
            val vector = v.asObj
            val t = vector.long("template")
            val rows = inputs.boxGroup(inputs.item(t)!!.long("value_107"))
            check("bead $t", compact(vector["rows"]), compact(JArr(Acquisition.beadRows(t, rows).toMutableList())))
        }
        for (v in doc.arr("fresh")) {
            val vector = v.asObj
            val t = vector.long("template")
            replay("fresh $t", vector["result"], { inputs.freshHeroFields(vector.long("uid"), t) }) { _, out -> check("fresh $t", compact(vector["result"]), compact(out)) }
        }
        for (v in doc.arr("luck")) {
            val vector = v.asObj
            replay("luck ${compact(vector["luck"])}", vector["result"], { Compose.luckPayload(vector.obj("luck")).toHexString() }) { _, out ->
                check("luck ${compact(vector["luck"])}", (vector["result"] as JStr).value, out)
            }
        }
        for (v in doc.arr("attributes")) {
            val vector = v.asObj
            val label = "attribute ${vector.str("kind")} ${compact(vector["record"])}"
            replay(label, vector["result"], { Compose.composeAttribute(vector.str("kind"), vector.arr("record"), inputs) }) { _, (a, c) ->
                check(label, compact(vector["result"]), compact(jarr(a, c)))
            }
        }
        for (v in doc.arr("reports")) {
            val vector = v.asObj
            val name = vector.str("name").hexBytes()
            val heroes = vector.arr("heroes").map { it.long }
            val label = "reports ${vector.str("name")}"
            val all = vector.arr("all_heroes").map { it.long }
            check("$label qualifying", compact(vector["qualifying"]), compact(JArr(SummonReports.qualifying(all, inputs).mapTo(ArrayList()) { JInt(it) })))
            val entries = SummonReports.record(null, 90000123, name, heroes, 1_800_000_000)
            check("$label entries", compact(vector["entries"]), compact(JArr(entries.toMutableList<JValue>())))
            val d = jobj("profile" to SummonReports.PROFILE, "entries" to JArr(MutableList(5) { SummonReports.entry(1, "x".toByteArray(), heroes[0], 5) }))
            check("$label appended", compact(vector["appended"]), compact(SummonReports.append(d, entries)))
            for ((offset, frames) in vector.obj("frames")) {
                check("$label frames $offset", framesOf(frames), framesOf(SummonReports.summonFrames(entries, inputs, offset.toLong())))
            }
        }
        report("item rules")
    }
}
