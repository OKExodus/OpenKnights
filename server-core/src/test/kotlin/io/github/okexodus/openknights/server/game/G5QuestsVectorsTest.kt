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
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path

/**
 * Local only (OPENKNIGHTS_DEV_DIR with `vectors-g5/quests_*.json` from the maintainer's private exporter,
 * OPENKNIGHTS_ORIGINALS with the player's APK): the quests slice replayed on the reference's own outputs — the quest
 * rewards, player EXP, every quest / bounty board planner on the recorded saves (as they are and primed), the goal
 * claims and the Hidden Training queries. Every frame, plan, document and state must be identical.
 */
class G5QuestsVectorsTest {
    private val dev: Path? = System.getenv("OPENKNIGHTS_DEV_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
    private val originals: Path? = System.getenv("OPENKNIGHTS_ORIGINALS")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
    private val failures = ArrayList<String>()
    private var checks = 0

    @AfterEach
    fun reset() {
        DeviceClock.active = null
        Events.setActive(null)
    }

    private fun file(name: String): Path? = dev?.resolve("vectors-g5")?.resolve("quests_$name.json")

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
            (save["character_profile"] as? JObj)?.deepCopy(), null, docs)
    }

    /** The exporter's `apply` / `apply_overrides`: documents (null = absent), role bits, dropped achievement kinds, ladders. */
    private fun applyOverrides(save: JObj, overrides: JObj?): JObj {
        val out = save.deepCopy()
        if (overrides == null) return out
        (overrides["documents"] as? JObj)?.forEach { (table, doc) -> if (doc == JNull) out.obj("documents").remove(table) else out.obj("documents")[table] = doc.deepCopy() }
        (overrides["roles"] as? JObj)?.forEach { (field, bits) ->
            for (f in out.obj("state").arr("role_properties")) if (f.asObj["id"] == JInt(field.toLong())) f.asObj.obj("value")["bits"] = bits
        }
        (overrides["drop_achievements"] as? JArr)?.forEach { kind ->
            val section = out.obj("state").obj("subsystems").obj("achievements")
            section["entries"] = JArr(section.arr("entries").filter { it.asObj.arr("wire_values")[0] != kind }.toMutableList())
        }
        (overrides["ladders"] as? JArr)?.let { ladders ->
            val section = (out.obj("state").obj("subsystems")["game_activities"] as? JObj)?.get("first_list") as? JObj
            if (section != null) {
                section["entries"] = JArr((ladders.deepCopy() + section.arr("entries")).toMutableList())
                section["count"] = JInt(section.arr("entries").size)
            }
        }
        if (overrides["no_profile"] == JBool(true)) out["character_profile"] = JNull
        return out
    }

    /** The keys whose hashes differ (the failure text names them). */
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
    fun `quest rewards, decoders, player EXP and the training formulas`() {
        assumeTrue(available("saves", "rules"), "OPENKNIGHTS_DEV_DIR / OPENKNIGHTS_ORIGINALS not set: local-only test skipped")
        loadSaves()
        val doc = vectors("rules")
        blobs = doc.obj("blobs")
        val inputs = inputs()
        for ((i, v) in doc.arr("quest_reward").withIndex()) {
            val vector = v.asObj
            val quest = inputs.quest(vector.long("quest"))!!
            replay("quest_reward $i ${vector["quest"]}", vector["result"], {
                Quests.questReward(quest, (vector["level"] as JInt).value, inputs, vector["stars"]?.takeIf { it != JNull })
            }) { _, r -> check("quest_reward $i ${compact(vector)}", compact(vector["result"]), compact(jarr(r.exp, r.gold, r.honor, r.points))) }
        }
        for ((i, v) in doc.arr("decode_task").withIndex()) {
            val vector = v.asObj
            replay("decode_task $i", vector["result"], { Quests.decodeTask(vector.str("payload").hexBytes(), vector.long("opcode").toInt()) }) { rec, r ->
                check("decode_task $i", compact(rec), compact(r))
            }
        }
        for ((i, v) in doc.arr("decode_claim").withIndex()) {
            val vector = v.asObj
            val recorded = vector["result"]
            try {
                val ident = Goals.decodeClaim(vector.str("payload").hexBytes())
                check("decode_claim $i", compact(recorded), compact(JInt(ident)))
            } catch (e: Acquisition.Rejected) {
                check("decode_claim $i error", (recorded as JObj).str("message") to recorded["code"], e.message to JInt(e.code))
            }
        }
        val goals = Goals.tables(inputs).goals
        for ((i, v) in doc.arr("reward_of").withIndex()) {
            val vector = v.asObj
            val goal = if (vector["goal"] is JInt) goals.getValue(vector.long("goal")) else vector.obj("synthetic")
            replay("reward_of $i", vector["result"], { Goals.rewardOf(goal) }) { rec, r -> check("reward_of $i", compact(rec), compact(r)) }
        }
        for ((i, v) in doc.arr("grant_exp").withIndex()) {
            val vector = v.asObj
            val save = applyOverrides(saves.getValue(vector.str("save")), jobj("roles" to vector["roles"]))
            val cur = currentOf(save)
            var owned: Owned? = null
            replay("grant_exp $i", vector["result"], {
                owned = Owned(cur, inputs)
                PlayerLevel.grantExp(owned!!, (vector["amount"] as JInt).value, inputs)
            }) { r, (frames, levels) ->
                val rec = r as JObj
                check("grant_exp $i frames", framesOf(rec["frames"]!!), framesOf(frames))
                check("grant_exp $i levels", rec["levels"], JInt(levels))
                check("grant_exp $i owned", compact(rec["owned"]), compact(ownedRecord(owned!!)))
                check("grant_exp $i hashes", "same", hashDiff(rec["hashes"], sectionHashes(owned!!.state)))
            }
        }
        for ((i, v) in doc.arr("room_password").withIndex()) {
            val vector = v.asObj
            replay("room_password $i", vector["result"], { HiddenTraining.decodeRoomPassword(vector.str("payload").hexBytes(), vector.long("opcode").toInt()) }) { rec, r ->
                check("room_password $i", compact(rec), compact(jobj("room" to r.room, "password" to r.password.toHexString())))
            }
        }
        for ((i, v) in doc.arr("attack_score").withIndex()) {
            val vector = v.asObj
            val roles = vector["title"]?.takeIf { it != JNull }?.let { jobj("22" to it) }
            val save = applyOverrides(saves.getValue(vector.str("save")), jobj("roles" to roles))
            replay("attack_score $i", vector["result"], { HiddenTraining.attackScore(save.obj("state"), inputs) }) { rec, r ->
                check("attack_score $i", compact(rec), compact(JInt(r)))
            }
        }
        for ((i, v) in doc.arr("training_reward").withIndex()) {
            val vector = v.asObj
            val players = MutableList(vector.long("players").toInt()) { HiddenTraining.Player(JInt(it), JInt(0), ByteArray(0), 0) }
            val room = HiddenTraining.Room(JInt(1), vector["row"]!!, ByteArray(0), null, ByteArray(0), (vector["mine"] as JBool).value, players)
            val boost = HiddenTraining.trainingBoost(room, inputs)
            check("training_boost $i", vector.long("boost"), boost)
            val (exp, honor) = HiddenTraining.trainingReward(BigInteger.valueOf(vector.long("seconds")), BigInteger(vector.str("power")),
                inputs.trainingRoom(vector.long("row"))!!, boost)
            check("training_reward $i", compact(vector["reward"]), compact(jarr(exp, honor)))
        }
        for ((i, v) in doc.arr("held_outside").withIndex()) {
            val vector = v.asObj
            val state = saves.getValue(vector.str("save")).obj("state").deepCopy()
            state["items"] = vector.arr("items").deepCopy()
            val document = vector.obj("document").deepCopy()
            replay("held_outside $i", vector["result"], { Quests.refreshOwned(document, inputs, state) }) { rec, changed ->
                check("held_outside $i ${vector.str("variant")}", compact(rec), compact(jobj("changed" to changed, "document" to document)))
            }
        }
        report("rules")
    }

    // --- planners -------------------------------------------------------------------------------------------------------

    @Test
    fun `every quest and bounty board planner on the recorded saves`() {
        assumeTrue(available("saves", "plans"), "OPENKNIGHTS_DEV_DIR / OPENKNIGHTS_ORIGINALS not set: local-only test skipped")
        loadSaves()
        val doc = vectors("plans")
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
            val label = "plan $i ${source.str("id")} C$opcode ${vector.str("payload")}"
            val cur = currentOf(save)
            var owned: Owned? = null
            replay(label, vector["result"], {
                val routed = DailyRoutes.plannerFor(opcode, payload, inputs, seedsOf(save), now, { served }, DailyRoutes.WorldContext(), source.str("owner_key"))
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
        println("planners: $ran vectors")
        report("planners")
    }

    @Test
    fun `goal claims`() {
        assumeTrue(available("saves", "goals"), "OPENKNIGHTS_DEV_DIR / OPENKNIGHTS_ORIGINALS not set: local-only test skipped")
        loadSaves()
        val doc = vectors("goals")
        blobs = doc.obj("blobs")
        val inputs = inputs()
        for ((i, v) in doc.arr("vectors").withIndex()) {
            val vector = v.asObj
            val save = applyOverrides(saves.getValue(vector.str("save")), vector["overrides"] as? JObj)
            installClock(vector.obj("clock"))
            val power = (vector["power"] as? JStr)?.value?.let { BigInteger(it) }
            val label = "goal $i ${vector.str("save")} ${vector.str("payload")}"
            val cur = currentOf(save)
            var owned: Owned? = null
            replay(label, vector["result"], {
                owned = Owned(cur, inputs)
                Goals.planClaim(vector.str("payload").hexBytes(), owned!!, cur, seedsOf(save), inputs, vector.long("now"),
                    power?.let { p -> { _: StateStore.Current -> p } })
            }) { r, plan ->
                val rec = r as JObj
                check("$label plan", compact(rec["plan"]), compact(plan.data))
                check("$label frames", framesOf(rec["frames"]!!), framesOf(plan.packets))
                check("$label owned", compact(rec["owned"]), compact(ownedRecord(owned!!)))
                check("$label hashes", "same", hashDiff(rec["hashes"], sectionHashes(owned!!.state)))
            }
        }
        report("goal claims")
    }

    @Test
    fun `Hidden Training queries`() {
        assumeTrue(available("saves", "training"), "OPENKNIGHTS_DEV_DIR / OPENKNIGHTS_ORIGINALS not set: local-only test skipped")
        loadSaves()
        val doc = vectors("training")
        blobs = doc.obj("blobs")
        val inputs = inputs()
        for ((i, v) in doc.arr("vectors").withIndex()) {
            val vector = v.asObj
            val save = applyOverrides(saves.getValue(vector.str("save")),
                jobj("documents" to jobj("training_state" to vector["document"]), "roles" to vector["roles"]))
            val opcode = vector.long("opcode").toInt()
            val label = "training $i ${vector.str("save")} C$opcode ${vector.str("payload")}"
            replay(label, vector["result"], {
                DailyRoutes.trainingQuery(opcode, vector.str("payload").hexBytes(), currentOf(save), inputs, vector.long("now"))
            }) { rec, frames -> check("$label frames", framesOf((rec as JObj)["frames"]!!), framesOf(frames)) }
        }
        report("training queries")
    }
}
