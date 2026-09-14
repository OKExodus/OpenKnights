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
 * Local only (OPENKNIGHTS_DEV_DIR with `vectors-g6/training_*.json` from the maintainer's private exporter,
 * OPENKNIGHTS_ORIGINALS with the player's APK): the Hidden Training actions replayed on the reference's own outputs —
 * the decoders, the Set Out hero EXP and outcomes, and every training room, forge and Hero Set Out planner on the
 * recorded saves (as they are and primed). Every frame, plan, document and state must be identical.
 */
class G6TrainingVectorsTest {
    private val dev: Path? = System.getenv("OPENKNIGHTS_DEV_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
    private val originals: Path? = System.getenv("OPENKNIGHTS_ORIGINALS")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
    private val failures = ArrayList<String>()
    private var checks = 0

    @AfterEach
    fun reset() {
        DeviceClock.active = null
        Events.setActive(null)
    }

    private fun file(name: String): Path? = dev?.resolve("vectors-g6")?.resolve("training_$name.json")

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
            if (!isError(recorded)) failures.add("$label: unexpected ${e::class.simpleName}: ${e.message}\n${e.stackTrace.take(8).joinToString("\n")}")
            else {
                val rec = recorded as JObj
                check("$label error kind ${rec.str("error")} / ${e::class.simpleName}", rec["value_error"] == JBool(true), e is IllegalArgumentException)
                if (e is IllegalArgumentException) check("$label error", rec.str("message"), e.message)
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

    // --- the saves ------------------------------------------------------------------------------------------------------

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

    private fun currentOf(save: JObj): StateStore.Current {
        val docs = LinkedHashMap<String, JValue?>()
        for ((k, v) in save.obj("documents")) docs[k] = v.deepCopy()
        return StateStore.Current((save["revision"] as JInt).value.toLong(), "", "", save.obj("state").deepCopy(), ByteArray(0), 0, null,
            save.arr("inventory_items").deepCopy(), emptyList(), save.arr("acquired_items").deepCopy(), emptyList(), null, null, null,
            (save["character_profile"] as? JObj)?.deepCopy(), null, docs).also { cur ->
            cur.jewelEntriesView = (save["jewel_entries"] as? JArr)?.deepCopy()
        }
    }

    private fun heroUid(fields: JValue): JValue? = Acquisition.heroValues(fields.asArr)[0L]

    /**
     * The exporter's `apply`: documents (null = absent), role bits, heroes {uid: fields}, equipment {uid: wire values},
     * appended items, appended formation jewel blocks, prepended ladders, the jewel_entries_view.
     */
    private fun applyOverrides(save: JObj, overrides: JObj?): JObj {
        val out = save.deepCopy()
        if (overrides == null) return out
        val state = out.obj("state")
        (overrides["documents"] as? JObj)?.forEach { (table, doc) -> if (doc == JNull) out.obj("documents").remove(table) else out.obj("documents")[table] = doc.deepCopy() }
        (overrides["roles"] as? JObj)?.forEach { (field, bits) ->
            for (f in state.arr("role_properties")) if (f.asObj["id"] == JInt(field.toLong())) f.asObj.obj("value")["bits"] = bits
        }
        (overrides["heroes"] as? JObj)?.forEach { (uid, fields) ->
            val heroes = state.arr("heroes")
            for (i in heroes.indices) if (heroUid(heroes[i]) == JInt(uid.toLong())) heroes[i] = fields.deepCopy()
        }
        (overrides["equipment"] as? JObj)?.forEach { (uid, wire) ->
            for (r in state.arr("equipment")) if (r.asObj.arr("wire_values")[0] == JInt(uid.toLong())) r.asObj["wire_values"] = wire.deepCopy()
        }
        (overrides["items"] as? JArr)?.forEach { state.arr("items").add(it.deepCopy()) }
        (overrides["blocks"] as? JArr)?.forEach { b ->
            val block = b.asObj
            state.arr("formation")[block.long("slot").toInt()].asObj.arr("blocks_40").add(jobj("id" to block["id"], "raw_hex" to block["raw_hex"]))
        }
        (overrides["ladders"] as? JArr)?.let { ladders ->
            val section = (state.obj("subsystems")["game_activities"] as? JObj)?.get("first_list") as? JObj
            if (section != null) {
                section["entries"] = JArr((ladders.deepCopy() + section.arr("entries")).toMutableList())
                section["count"] = JInt(section.arr("entries").size)
            }
        }
        if (overrides.containsKey("jewel_entries")) out["jewel_entries"] = overrides["jewel_entries"]!!.deepCopy()
        return out
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
        "granted" to JArr(owned.granted.entries.mapTo(ArrayList()) { (t, n) -> jarr(t, n) }),
        "heroes_added" to owned.heroesAdded, "equipment_added" to owned.equipmentAdded,
        "god" to (owned.godDocument?.let { sha(it) }))

    // --- rules ----------------------------------------------------------------------------------------------------------

    @Test
    fun `decoders, Set Out hero EXP and outcomes`() {
        assumeTrue(available("saves", "rules"), "OPENKNIGHTS_DEV_DIR / OPENKNIGHTS_ORIGINALS not set: local-only test skipped")
        loadSaves()
        val doc = vectors("rules")
        blobs = doc.obj("blobs")
        val inputs = inputs()
        for ((i, v) in doc.arr("decoders").withIndex()) {
            val vector = v.asObj
            val payload = vector.str("payload").hexBytes()
            val opcode = vector.long("opcode").toInt()
            val label = "decoder $i ${vector.str("name")} C$opcode ${vector.str("payload")}"
            replay(label, vector["result"], {
                when (vector.str("name")) {
                    "decode_train" -> HiddenTraining.decodeTrain(payload)
                    "decode_pick" -> HiddenTraining.decodePick(payload, opcode)
                    "decode_u32_slot" -> HiddenTraining.decodeU32Slot(payload, opcode)
                    "decode_explore_pick" -> HiddenTraining.decodeExplorePick(payload, opcode)
                    "decode_refresh" -> HiddenTraining.decodeRefresh(payload)
                    else -> HiddenTraining.decodeU8Slot(payload, opcode)
                }
            }) { rec, r -> check(label, compact(rec), compact(r)) }
        }
        for ((i, v) in doc.arr("grant_hero_exp").withIndex()) {
            val vector = v.asObj
            val source = saves.getValue(vector.str("save"))
            val uid = vector["uid"]!!
            val awarded = vector.long("awarded")
            val label = "grant_hero_exp $i ${vector.str("save")} ${vector.str("variant")} $uid $awarded"
            if (vector["hero"] == JNull) {
                replay(label, vector["result"], {
                    HiddenTraining.grantHeroExp(Owned(currentOf(source), inputs), inputs, uid, awarded).third
                }) { rec, detail -> check(label, compact(rec), compact(detail)) }
                continue
            }
            val save = applyOverrides(source, jobj("heroes" to jobj(PyDocs.str(uid) to vector["hero"])))
            var owned: Owned? = null
            replay(label, vector["result"], {
                owned = Owned(currentOf(save), inputs)
                HiddenTraining.grantHeroExp(owned!!, inputs, uid, awarded)
            }) { r, (frames, grow, detail) ->
                val rec = r as JObj
                check("$label frames", framesOf(rec["frames"]!!), framesOf(frames))
                check("$label grow", compact(rec["grow"]), compact(grow))
                check("$label detail", compact(rec["detail"]), compact(detail))
                check("$label hashes", "same", hashDiff(rec["hashes"], sectionHashes(owned!!.state)))
            }
        }
        for ((i, v) in doc.arr("outcome").withIndex()) {
            val vector = v.asObj
            val save = saves.getValue(vector.str("save"))
            val slot = vector.obj("slot")
            val label = "outcome $i ${vector.str("save")} ${compact(slot)}"
            replay(label, vector["result"], {
                val outcome = HiddenTraining.outcome(inputs, slot.deepCopy(), save.str("owner_key"), vector.long("now"))
                jobj("outcome" to outcome, "text" to HiddenTraining.resultText(inputs, save.obj("state"), slot, outcome))
            }) { rec, r -> check(label, compact(rec), compact(r)) }
        }
        report("rules")
    }

    // --- planners -------------------------------------------------------------------------------------------------------

    /** The route of one request; C1779 decodes with Castle.decodeU32 (the other slice): a local stand-in until it lands. */
    private fun route(opcode: Int, payload: ByteArray, inputs: DailyInputs, save: JObj, now: Long, served: Long): DailyRoutes.Routed = try {
        DailyRoutes.plannerFor(opcode, payload, inputs, seedsOf(save), now, { served }, DailyRoutes.WorldContext(), save.str("owner_key"))
    } catch (e: NotPorted) {
        if (opcode != HiddenTraining.C_ROOM_ADD_TIME) throw e
        if (payload.size != 4) throw Acquisition.Rejected("C$opcode is u32")
        val request = jobj("room" to io.github.okexodus.openknights.protocol.WireReader(payload).number('I'))
        DailyRoutes.Routed("training_add_time", request) { owned, current ->
            HiddenTraining.planAddTime(request, owned, inputs, PyDocs.get(current, "training_state"), now, served).also {
                it["now_epoch"] = now
                it["served_time"] = served
            }
        }
    }

    private fun planners(name: String) {
        assumeTrue(available("saves", name), "OPENKNIGHTS_DEV_DIR / OPENKNIGHTS_ORIGINALS not set: local-only test skipped")
        loadSaves()
        val doc = vectors(name)
        blobs = doc.obj("blobs")
        val inputs = inputs()
        var ran = 0
        for ((i, v) in doc.arr("vectors").withIndex()) {
            val vector = v.asObj
            val source = saves.getValue(vector.str("save"))
            val save = applyOverrides(source, vector["overrides"] as? JObj)
            val opcode = vector.long("opcode").toInt()
            val payload = vector.str("payload").hexBytes()
            val now = vector.long("now")
            val served = vector.long("served")
            installClock(vector.obj("clock"))
            val label = "$name $i ${source.str("id")} C$opcode ${vector.str("payload")}"
            val cur = currentOf(save)
            var owned: Owned? = null
            replay(label, vector["result"], {
                val routed = route(opcode, payload, inputs, save, now, served)
                owned = Owned(cur, inputs)
                routed to routed.planner(owned!!, cur)
            }) { r, (routed, plan) ->
                val rec = r as JObj
                check("$label action", rec.str("action"), routed.action)
                check("$label request", compact(rec["request"]), compact(routed.request))
                check("$label plan", compact(rec["plan"]), compact(plan.data))
                check("$label frames", framesOf(rec["frames"]!!), framesOf(plan.packets))
                check("$label owned", compact(rec["owned"]), compact(ownedRecord(owned!!)))
                check("$label hashes", "same", hashDiff(rec["hashes"], sectionHashes(owned!!.state)))
            }
            ran++
        }
        println("$name: $ran vectors")
        report(name)
    }

    @Test
    fun `training room planners`() = planners("rooms")

    @Test
    fun `forge planners`() = planners("forge")

    @Test
    fun `Hero Set Out planners`() = planners("setout")
}
