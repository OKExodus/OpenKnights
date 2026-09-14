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
import io.github.okexodus.openknights.protocol.ProtocolException
import io.github.okexodus.openknights.server.DeviceClock
import io.github.okexodus.openknights.server.store.StateStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path

/**
 * Local only (OPENKNIGHTS_DEV_DIR with `vectors-g8/eventhall_*.json` from the maintainer's private exporter,
 * OPENKNIGHTS_ORIGINALS with the player's APK): the Event Hall action slice replayed on the reference's own outputs —
 * the decoders, and every action planner (C1635 / C1637 / C1641 / C3077 / C1665 through `DailyRoutes.plannerFor`,
 * `plan_great_offer` directly) on the recorded saves. Every frame, plan, document and state must be identical.
 */
class G8EventHallVectorsTest {
    private val dev: Path? = System.getenv("OPENKNIGHTS_DEV_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
    private val originals: Path? = System.getenv("OPENKNIGHTS_ORIGINALS")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
    private val failures = ArrayList<String>()
    private var checks = 0

    @AfterEach
    fun reset() {
        DeviceClock.active = null
        Events.setActive(null)
    }

    private fun file(name: String): Path? = dev?.resolve("vectors-g8")?.resolve("eventhall_$name.json")
    private fun vectors(name: String): JObj = Json.loads(Files.readString(file(name)!!)).asObj
    private fun available(vararg names: String): Boolean =
        dev != null && originals != null && names.all { n -> file(n)?.let { Files.isRegularFile(it) } == true }

    private fun inputs() = DailyInputs(GameTables(ApkTables(originals!!.resolve("com.enjoygame.hero2d.apk"))))

    private fun check(label: String, expected: Any?, actual: Any?) {
        checks++
        if (expected != actual) failures.add("$label\n  expected: ${expected.toString().take(900)}\n  actual:   ${actual.toString().take(900)}")
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

    private fun <T> replay(label: String, recorded: JValue?, block: () -> T, compare: (JValue, T) -> Unit) {
        val result = try {
            block()
        } catch (e: NotPorted) {
            throw e
        } catch (e: Exception) {
            if (!isError(recorded)) failures.add("$label: unexpected ${e::class.simpleName}: ${e.message}\n${e.stackTrace.take(8).joinToString("\n")}")
            else {
                val rec = recorded as JObj
                val valueError = e is IllegalArgumentException && !(e is ProtocolException && rec.str("error") == "error")
                check("$label error kind ${rec.str("error")} / ${e::class.simpleName}", rec["value_error"] == JBool(true), valueError)
                if (valueError) check("$label error", rec.str("message"), e.message)
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

    // --- rules ------------------------------------------------------------------------------------------------------------

    @Test
    fun `decoders and exchange-body round-trip`() {
        assumeTrue(available("rules"), "OPENKNIGHTS_DEV_DIR / OPENKNIGHTS_ORIGINALS not set: local-only test skipped")
        val doc = vectors("rules")
        for ((i, v) in doc.arr("pie").withIndex()) {
            val vector = v.asObj
            replay("pie $i ${vector.str("payload")}", vector["result"], { EventHall.decodePieRequest(vector.str("payload").hexBytes()) }) { r, res -> check("pie $i", compact(r), compact(res)) }
        }
        for ((i, v) in doc.arr("combine").withIndex()) {
            val vector = v.asObj
            replay("combine $i ${vector.str("payload")}", vector["result"], { EventHall.decodeCombine(vector.str("payload").hexBytes()) }) { r, res -> check("combine $i", compact(r), compact(res)) }
        }
        for ((i, v) in doc.arr("exchange").withIndex()) {
            val vector = v.asObj
            replay("exchange $i ${vector.str("payload")}", vector["result"], { EventHall.decodeExchange(vector.str("payload").hexBytes()) }) { r, res -> check("exchange $i", compact(r), compact(res)) }
        }
        for ((i, v) in doc.arr("exchange_body").withIndex()) {
            val vector = v.asObj
            replay("exchange_body $i", vector["result"], { EventHall.decodeExchangeBody(vector.str("body").hexBytes()) }) { r, res -> check("exchange_body $i", compact(r), compact(res)) }
        }
        report("rules")
    }

    // --- saves + planners -------------------------------------------------------------------------------------------------

    private lateinit var saves: Map<String, JObj>
    private lateinit var freshSystems: Map<Int, List<ByteArray>>

    private fun loadSaves() {
        val doc = vectors("saves")
        freshSystems = doc.obj("fresh_systems").entries.associate { (op, list) -> op.toInt() to list.asArr.map { (it as JStr).value.hexBytes() } }
        saves = doc.arr("saves").associate { it.asObj.str("id") to it.asObj }
        Events.setActive(doc.obj("events"))
    }

    private fun seedsOf(save: JObj): SystemSeeds.SeedFrames? =
        if (save["character_profile"] != null && save["character_profile"] != JNull) SystemSeeds.SeedFrames(freshSystems, "fresh_systems_template") else null

    /** The exporter's `apply(save, ov)`, in the same order. */
    private fun apply(save: JObj, ov: JObj): JObj {
        val out = save.deepCopy()
        val state = out.obj("state")
        (ov["state"] as? JObj)?.forEach { (key, value) -> state[key] = value.deepCopy() }
        (ov["subsystems"] as? JObj)?.forEach { (name, section) -> state.obj("subsystems")[name] = section.deepCopy() }
        (ov["roles"] as? JObj)?.forEach { (field, bits) ->
            for (f in state.arr("role_properties")) if (f.asObj["id"] == JInt(field.toLong())) f.asObj.obj("value")["bits"] = bits
        }
        (ov["items"] as? JArr)?.forEach { r ->
            val (uid, template, count, timed) = r.asArr
            state.arr("items").add(jobj("wire_values" to jarr(uid, template, count), "timed_flag" to timed))
        }
        (ov["heroes"] as? JArr)?.forEach { fields ->
            state.arr("heroes").add(fields.deepCopy())
            state.arr("offline_hero_uids").add(fields.asArr[0].asObj.obj("value").getValue("bits"))
        }
        (ov["equipment"] as? JArr)?.forEach { r -> state.arr("equipment").add(jobj("offset" to null, "wire_values" to r.deepCopy())) }
        state["bag_equipment_uids"] = JArr((state.arr("bag_equipment_uids") + ((ov["bag"] as? JArr) ?: JArr())).toMutableList())
        (ov["lineup"] as? JObj)?.forEach { (slot, uid) ->
            for (s in state.arr("formation")) if (s.asObj["slot_id"] == JInt(slot.toLong())) s.asObj["hero_uid"] = uid
        }
        (ov["documents"] as? JObj)?.forEach { (table, d) -> if (d == JNull) out.obj("documents").remove(table) else out.obj("documents")[table] = d.deepCopy() }
        if (ov.containsKey("secondary_team")) out["secondary_team"] = ov["secondary_team"]!!.deepCopy()
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
            save.arr("inventory_items").deepCopy(), emptyList(), save.arr("acquired_items").deepCopy(), emptyList(), null,
            (save["secondary_team"] as? JObj)?.deepCopy(), (save["god_skills"] as? JObj)?.deepCopy(), (save["character_profile"] as? JObj)?.deepCopy(),
            (save["jewelry_list"] as? JObj)?.deepCopy(), docs).also { it.jewelEntriesView = jewels?.deepCopy() }
    }

    private fun sectionHashes(state: JObj): JObj {
        val out = jobj("state" to sha(state))
        for ((key, value) in state) {
            if (key == "subsystems") for ((name, section) in value.asObj) out["subsystems.$name"] = JStr(sha(section))
            else out[key] = JStr(sha(value))
        }
        return out
    }

    private fun changedHashes(before: JObj, after: JObj): JObj {
        val changed = JObj()
        for ((k, v) in after) if (k != "state" && before[k] != v) changed[k] = v
        return jobj("state" to after["state"], "changed" to changed, "removed" to before.keys.filter { it !in after }.sorted())
    }

    private fun ownedState(owned: Owned): JObj = jobj(
        "item_changes" to owned.itemChanges.map { (u, c) -> jarr(u, c) },
        "new_items" to owned.newItems.map { (u, e) -> jarr(u, e.first, e.second) },
        "role_changes" to owned.roleChanges.map { (f, v) -> jarr(f, v.first, v.second) },
        "log" to owned.log, "heroes_added" to owned.heroesAdded, "heroes_removed" to owned.heroesRemoved,
        "equipment_added" to owned.equipmentAdded, "granted" to owned.granted.map { (t, c) -> jarr(t, c) },
        "god" to owned.godDocument?.let { sha(it) })

    @Test
    fun `every Event Hall action planner on the recorded saves`() {
        assumeTrue(available("saves", "plans"), "OPENKNIGHTS_DEV_DIR / OPENKNIGHTS_ORIGINALS not set: local-only test skipped")
        loadSaves()
        val doc = vectors("plans")
        blobs = doc.obj("blobs")
        val overrides = doc.obj("overrides")
        val inputs = inputs()
        var ran = 0
        for ((i, v) in doc.arr("vectors").withIndex()) {
            val vector = v.asObj
            val source = saves.getValue(vector.str("save"))
            val ov = overrides.obj(vector.str("ov"))
            val save = apply(source, ov)
            val jewels = jewelView(save, ov)
            val opcode = vector.long("opcode").toInt()
            val payload = vector.str("payload").hexBytes()
            val now = vector.long("now")
            val served = vector.long("served")
            installClock(vector.obj("clock"))
            val label = "plans $i ${source.str("id")} ${vector.str("ov").take(6)} C$opcode ${vector.str("payload")}"
            val cur = currentOf(save, jewels)
            val before = sectionHashes(cur.state)
            var owned: Owned? = null
            replay(label, vector["result"], {
                val routed = DailyRoutes.plannerFor(opcode, payload, inputs, seedsOf(save), now, { served }, DailyRoutes.WorldContext(), source.str("owner_key"))
                owned = Owned(cur, inputs, (save["retired"] as JArr).map { it.long })
                routed to routed.planner(owned!!, cur)
            }) { r, (routed, plan) ->
                val rec = r as JObj
                check("$label action", rec.str("action"), routed.action)
                check("$label request", compact(rec["request"]), compact(routed.request))
                check("$label plan", compact(rec["plan"]), compact(plan.data))
                check("$label frames", framesOf(rec["frames"]!!), framesOf(plan.packets))
                check("$label owned", compact(rec["owned"]), compact(ownedState(owned!!)))
                check("$label hashes", compact(rec["hashes"]), compact(changedHashes(before, sectionHashes(owned!!.state))))
            }
            ran++
        }
        println("plans: $ran vectors")
        report("plans")
    }

    @Test
    fun `Great Offer engine on synthetic windows and seeds`() {
        assumeTrue(available("saves", "offer"), "OPENKNIGHTS_DEV_DIR / OPENKNIGHTS_ORIGINALS not set: local-only test skipped")
        loadSaves()
        val doc = vectors("offer")
        blobs = doc.obj("blobs")
        val overrides = doc.obj("overrides")
        val inputs = inputs()
        var ran = 0
        for ((i, v) in doc.arr("vectors").withIndex()) {
            val vector = v.asObj
            val source = saves.getValue(vector.str("save"))
            val ov = overrides.obj(vector.str("ov"))
            val save = apply(source, ov)
            val jewels = jewelView(save, ov)
            val now = vector.long("now")
            val seed = BigInteger(vector.str("seed"))
            val document = vector["document"]?.takeIf { it != JNull }
            installClock(vector.obj("clock"))
            val label = "offer $i ${source.str("id")} seed=${vector.str("seed").take(8)}"
            val cur = currentOf(save, jewels)
            val before = sectionHashes(cur.state)
            var owned: Owned? = null
            replay(label, vector["result"], {
                owned = Owned(cur, inputs, (save["retired"] as JArr).map { it.long })
                EventHall.planGreatOffer(owned!!, document, inputs, now, seed)
            }) { r, plan ->
                val rec = r as JObj
                check("$label plan", compact(rec["plan"]), compact(plan.data))
                check("$label frames", framesOf(rec["frames"]!!), framesOf(plan.packets))
                check("$label owned", compact(rec["owned"]), compact(ownedState(owned!!)))
                check("$label hashes", compact(rec["hashes"]), compact(changedHashes(before, sectionHashes(owned!!.state))))
            }
            ran++
        }
        println("offer: $ran vectors")
        report("offer")
    }
}
