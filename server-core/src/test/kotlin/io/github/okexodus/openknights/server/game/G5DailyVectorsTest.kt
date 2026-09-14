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
import io.github.okexodus.openknights.server.game.WorldParticipants.Participant
import io.github.okexodus.openknights.server.store.JdbcSqlDriver
import io.github.okexodus.openknights.server.store.StateStore
import io.github.okexodus.openknights.server.store.WorldDirectory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path

/**
 * Local only (OPENKNIGHTS_DEV_DIR with `vectors-g5/daily_*.json` from the maintainer's private exporter,
 * OPENKNIGHTS_ORIGINALS with the player's APK): the daily slice replayed on the reference's own outputs — every daily
 * planner (check-in, timed gifts, salary, Daily Mission gifts, Royal Door claims and donations) on every distinct
 * recorded save (as it is and primed, with documents at several stages, device clocks and world Doors), the grant /
 * decode rules, the arena query side and the Castle catch list on synthetic participants, and the C417 / C421 / C753
 * replies with their arena ladder writes.
 */
class G5DailyVectorsTest {
    @TempDir
    lateinit var temp: Path

    private val dev: Path? = System.getenv("OPENKNIGHTS_DEV_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
    private val originals: Path? = System.getenv("OPENKNIGHTS_ORIGINALS")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
    private val failures = ArrayList<String>()
    private var checks = 0

    @AfterEach
    fun reset() {
        DeviceClock.active = null
    }

    private fun file(name: String): Path? = dev?.resolve("vectors-g5")?.resolve("daily_$name.json")

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

    /** Run [block]; compare an error with the recorded error (ValueError family, message, code), else [compare]. */
    private fun <T> replay(label: String, recorded: JValue?, block: () -> T, compare: (JValue, T) -> Unit) {
        val result = try {
            block()
        } catch (e: NotPorted) {
            throw e
        } catch (e: Exception) {
            if (!isError(recorded)) failures.add("$label: unexpected ${e::class.simpleName}: ${e.message}\n${e.stackTrace.take(8).joinToString("\n")}")
            else {
                val rec = recorded as JObj
                // struct.error (pack ranges) is the wire layer's ProtocolException, an IllegalArgumentException here
                if (rec.str("error") != "error") check("$label error kind", rec["value_error"] == JBool(true), e is IllegalArgumentException)
                if (rec["value_error"] == JBool(true)) check("$label error", rec.str("message"), e.message)
                val code = rec["code"]
                if (code != null && code != JNull) check("$label code", (code as JInt).value.toInt(), (e as? Acquisition.Rejected)?.code)
            }
            return
        }
        if (isError(recorded)) {
            failures.add("$label: expected ${(recorded as JObj).str("error")} (${recorded.str("message")}), got ${result.toString().take(300)}")
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

    // --- saves and worlds ----------------------------------------------------------------------------------------------

    private lateinit var saves: Map<String, JObj>
    private lateinit var freshSystems: Map<Int, List<ByteArray>>

    private fun loadSaves() {
        val doc = vectors("saves")
        freshSystems = doc.obj("fresh_systems").entries.associate { (op, list) -> op.toInt() to list.asArr.map { (it as JStr).value.hexBytes() } }
        saves = doc.arr("saves").associate { it.asObj.str("id") to it.asObj }
    }

    private fun seedsOf(save: JObj): SystemSeeds.SeedFrames? =
        if (save["character_profile"] != null && save["character_profile"] != JNull) SystemSeeds.SeedFrames(freshSystems, "fresh_systems_template") else null

    private fun withDocs(save: JObj, docs: JObj?): JObj {
        val out = save.deepCopy()
        docs?.forEach { (table, doc) -> if (doc == JNull) out.obj("documents").remove(table) else out.obj("documents")[table] = doc.deepCopy() }
        return out
    }

    private fun currentOf(save: JObj): StateStore.Current {
        val docs = LinkedHashMap<String, JValue?>()
        for ((k, v) in save.obj("documents")) docs[k] = v.deepCopy()
        return StateStore.Current((save["revision"] as JInt).value.toLong(), "", "", save.obj("state").deepCopy(), ByteArray(0), 0, null,
            save.arr("inventory_items").deepCopy(), emptyList(), save.arr("acquired_items").deepCopy(), emptyList(), null, null, null,
            (save["character_profile"] as? JObj)?.deepCopy(), null, docs)
    }

    private val driver = JdbcSqlDriver()
    private var worldCount = 0

    /** A fresh world directory holding [documents] (each written once over its birth document). */
    private fun worldWith(documents: Map<String, JObj>): WorldDirectory {
        val world = WorldDirectory.initialize(temp.resolve("world-${worldCount++}.sqlite3"), driver, "vectors")
        for ((name, doc) in documents) world.putDocument(name, doc, 1, "vectors", "vectors")
        return world
    }

    private val doorWorlds = HashMap<String, WorldDirectory>()

    /** The session's participant builder (world-less or registry-less: the requester only). */
    private fun context(world: WorldDirectory?, power: BigInteger?): DailyRoutes.WorldContext =
        DailyRoutes.WorldContext(world, null, power?.let { p -> { _: StateStore.Current -> p } }, emptyList()) { ctx, current ->
            if (current == null) emptyList()
            else listOf(WorldParticipants.characterParticipant(jobj("character_id" to null, "created_at_utc" to ""), current, ctx.powerOf))
        }

    private fun ownedRecord(owned: Owned, granted: Boolean = true): JObj = jobj(
        "item_changes" to JArr(owned.itemChanges.entries.mapTo(ArrayList()) { (u, c) -> jarr(u, c) }),
        "new_items" to JArr(owned.newItems.entries.mapTo(ArrayList()) { (u, e) -> jarr(u, e.first, e.second) }),
        "role_changes" to JArr(owned.roleChanges.entries.mapTo(ArrayList()) { (f, c) -> jarr(f, c.first, c.second) }),
        "log" to owned.log).also { if (granted) it["granted"] = JArr(owned.granted.entries.mapTo(ArrayList()) { (t, n) -> jarr(t, n) }) }

    private fun hashes(state: JObj): JObj = jobj("state" to sha(state), "role_properties" to sha(state["role_properties"]), "items" to sha(state["items"]))

    // --- the planners ----------------------------------------------------------------------------------------------------

    @Test
    fun `every daily planner on every recorded save`() {
        assumeTrue(available("plans", "saves"), "OPENKNIGHTS_DEV_DIR / OPENKNIGHTS_ORIGINALS not set: local-only test skipped")
        loadSaves()
        val doc = vectors("plans")
        blobs = doc.obj("blobs")
        val inputs = inputs()
        var ran = 0
        for ((i, v) in doc.arr("vectors").withIndex()) {
            val vector = v.asObj
            val save = withDocs(saves.getValue(vector.str("save")), vector["docs"] as? JObj)
            val opcode = vector.long("opcode").toInt()
            val payload = vector.str("payload").hexBytes()
            val now = vector.long("now")
            installClock(vector.obj("clock"))
            val door = vector["door"] as? JObj
            val worldCtx = if (door == null) DailyRoutes.WorldContext() else DailyRoutes.WorldContext(doorWorlds.getOrPut(compact(door)) {
                worldWith(mapOf("royal_door" to door))
            })
            val label = "plan $i ${vector.str("save")} C$opcode ${vector.str("payload")}"
            val cur = currentOf(save)
            var owned: Owned? = null
            replay(label, vector["result"], {
                val routed = DailyRoutes.plannerFor(opcode, payload, inputs, seedsOf(save), now, { now }, worldCtx, save.str("owner_key"))
                owned = Owned(cur, inputs)
                routed to routed.planner(owned!!, cur)
            }) { rec, (routed, plan) ->
                rec as JObj
                check("$label action", rec.str("action"), routed.action)
                check("$label request", compact(rec["request"]), compact(routed.request))
                check("$label plan", compact(rec["plan"]), compact(plan.data))
                check("$label frames", framesOf(rec["frames"]!!), framesOf(plan.packets))
                check("$label owned", compact(rec["owned"]), compact(ownedRecord(owned!!)))
                check("$label hashes", compact(rec["hashes"]), compact(hashes(owned!!.state)))
                check("$label flag", compact(rec["title_reward_flag"]), compact(owned!!.state["title_reward_flag"]))
            }
            ran++
        }
        println("planners: $ran vectors")
        report("planners")
    }

    // --- rules -----------------------------------------------------------------------------------------------------------

    @Test
    fun `grant triples, mission points and the request decoders`() {
        assumeTrue(available("rules", "saves"), "OPENKNIGHTS_DEV_DIR / OPENKNIGHTS_ORIGINALS not set: local-only test skipped")
        loadSaves()
        val doc = vectors("rules")
        blobs = doc.obj("blobs")
        val inputs = inputs()
        for ((i, v) in doc.arr("grant_triples").withIndex()) {
            val vector = v.asObj
            val cur = currentOf(saves.getValue(vector.str("save")))
            var owned: Owned? = null
            val reward = Acquisition.emptyReward()
            replay("grant_triples $i", vector["result"], {
                owned = Owned(cur, inputs)
                Daily.grantTriples(owned!!, vector.arr("triples").map { it.asArr }, reward)
            }) { rec, frames ->
                rec as JObj
                check("grant_triples $i frames", framesOf(rec["frames"]!!), framesOf(frames))
                check("grant_triples $i reward", compact(rec["reward"]), compact(reward))
                check("grant_triples $i owned", compact(rec["owned"]), compact(ownedRecord(owned!!, granted = false)))
            }
        }
        for ((i, v) in doc.arr("mission_points").withIndex()) {
            val vector = v.asObj
            replay("mission_points $i", vector["result"], { Daily.missionPoints(vector.obj("document"), inputs) }) { rec, total ->
                check("mission_points $i", (rec as JInt).value, total)
            }
        }
        for ((i, v) in doc.arr("decode_mission_gift").withIndex()) {
            val vector = v.asObj
            replay("decode_mission_gift $i", vector["result"], { Daily.decodeMissionGift(vector.str("payload").hexBytes()) }) { rec, out ->
                check("decode_mission_gift $i", compact(rec), compact(out))
            }
        }
        for ((i, v) in doc.arr("decode_donate").withIndex()) {
            val vector = v.asObj
            replay("decode_donate $i", vector["result"], { Daily.decodeDonate(vector.str("payload").hexBytes()) }) { rec, out ->
                check("decode_donate $i", compact(rec), compact(out))
            }
        }
        report("rules")
    }

    // --- arena and castle ------------------------------------------------------------------------------------------------

    private fun participantOf(json: JObj): Participant = Participant(json.long("participant_id"), json.str("kind"), json.str("name_raw").hexBytes(),
        json.long("level"), json.long("vip"), json.long("reputation"), (json["power"] as? JStr)?.value?.let { BigInteger(it) },
        json.long("leader_template"), json.arr("lineup").map { e ->
            val x = e.asArr.map { (it as JInt).value.toLong() }
            WorldParticipants.LineupEntry(x[0], x[1], x[2], x[3], x[4], x[5])
        }, json.strOrNull("character_id"), json.str("created"), json.long("gender"), json.obj("extra").deepCopy())

    @Test
    fun `arena query side and the Castle catch list`() {
        assumeTrue(available("arena"), "OPENKNIGHTS_DEV_DIR / OPENKNIGHTS_ORIGINALS not set: local-only test skipped")
        val doc = vectors("arena")
        for ((i, v) in doc.arr("settlement").withIndex()) {
            val vector = v.asObj
            installClock(vector.obj("clock"))
            check("settlement $i", vector.long("settle"), Arena.lastSettlement(vector.long("now")))
        }
        for ((i, v) in doc.arr("ladder").withIndex()) {
            val vector = v.asObj
            val people = vector.arr("participants").map { participantOf(it.asObj) }
            val own = vector.long("own")
            val stored = vector["stored"]?.takeIf { it != JNull } as JObj?
            val label = "ladder $i"
            replay(label, vector["result"], { Arena.ladderRanks(stored, people) }) { rec, (ranks, changed) ->
                rec as JObj
                check("$label ranks", compact(rec["ranks"]), compact(JArr(ranks.mapTo(ArrayList()) { JInt(it) })))
                check("$label changed", rec["changed"], JBool(changed))
                val byId = LinkedHashMap<Long, Participant>()
                for (p in people) byId[p.participantId] = p
                replay("$label rows", rec["rows"], { Arena.opponents(ranks, byId, own) }) { r, rows ->
                    check("$label rows", compact(r), compact(JArr(rows.mapTo(ArrayList()) { jarr(it.first, it.second.participantId) })))
                }
                val rank = rec.long("rank")
                check("$label rank", rank, if (own in ranks) ranks.indexOf(own) + 1L else ranks.size + 1L)
                replay("$label info", rec["info"], { Arena.infoPayload(rec.obj("doc"), rank, Arena.opponents(ranks, byId, own)).toHexString() }) { r, hex ->
                    check("$label info", (r as JStr).value, hex)
                }
                replay("$label top", rec["top"], { Arena.topPayload(ranks, byId).toHexString() }) { r, hex ->
                    check("$label top", (r as JStr).value, hex)
                }
            }
        }
        for ((i, v) in doc.arr("catch").withIndex()) {
            val vector = v.asObj
            val people = vector.arr("participants").map { participantOf(it.asObj) }
            replay("catch $i", vector["result"], {
                Castle.catchListPayload(people, vector["own"], vector.long("level"), vector.long("cost")).toHexString()
            }) { rec, hex -> check("catch $i", (rec as JStr).value, hex) }
        }
        for ((i, v) in doc.arr("joined").withIndex()) {
            val vector = v.asObj
            val profile = vector["profile"]?.takeIf { it != JNull } as JObj?
            val cur = StateStore.Current(1, "", "", jobj("role_properties" to JArr()), ByteArray(0), 0, null, JArr(), emptyList(), JArr(), emptyList(),
                null, null, null, profile, null, LinkedHashMap())
            replay("joined $i", vector["result"], { Arena.joinedAtOf(cur) }) { rec, at -> check("joined $i", (rec as JInt).value.toLong(), at) }
        }
        for ((i, v) in doc.arr("view").withIndex()) {
            val vector = v.asObj
            installClock(vector.obj("clock"))
            val joined = (vector["joined_at"] as? JInt)?.value?.toLong()
            replay("view $i", vector["result"], {
                Arena.arenaView(vector["document"]?.takeIf { it != JNull }?.deepCopy(), vector.long("rank"), vector.long("now"), joined)
            }) { rec, out -> check("view $i", compact(rec), compact(out)) }
        }
        report("arena")
    }

    // --- the queries -----------------------------------------------------------------------------------------------------

    @Test
    fun `arena and catch list replies with their ladder writes`() {
        assumeTrue(available("queries", "saves"), "OPENKNIGHTS_DEV_DIR / OPENKNIGHTS_ORIGINALS not set: local-only test skipped")
        loadSaves()
        val doc = vectors("queries")
        blobs = doc.obj("blobs")
        val inputs = inputs()
        for ((i, v) in doc.arr("vectors").withIndex()) {
            val vector = v.asObj
            val save = withDocs(saves.getValue(vector.str("save")), vector["docs"] as? JObj)
            val opcode = vector.long("opcode").toInt()
            val now = vector.long("now")
            installClock(vector.obj("clock"))
            val ladder = vector["ladder"] as? JObj
            val world = ladder?.let {
                worldWith(mapOf("royal_door" to jobj("profile" to "royal_door_world_v1", "level" to 1, "exp" to 0,
                    "born_at_utc" to "2026-09-10T10:00:00.000+00:00"), "arena_ladder" to it))
            }
            val power = (vector["power"] as? JStr)?.value?.let { BigInteger(it) }
            val label = "query $i ${vector.str("save")} C$opcode"
            replay(label, vector["result"], {
                DailyRoutes.queryReply(opcode, vector.str("payload").hexBytes(), currentOf(save), seedsOf(save), inputs, now, context(world, power),
                    save.str("owner_key"))
            }) { rec, frames ->
                rec as JObj
                check("$label frames", framesOf(rec["frames"]!!), framesOf(frames))
                val after = rec["world"] as? JObj
                if (world == null) check("$label world", null, after)
                else if (after != null) {
                    val (revision, document) = world.document("arena_ladder")!!
                    check("$label ladder", compact(after["document"]), compact(document))
                    check("$label ladder written", (after.long("revision") - 1), revision - 2)
                    val log = after.arr("log")
                    check("$label ladder log", log.map { e -> e.asObj.str("action") + ":" + compact(e.asObj["detail"]) },
                        if (revision > 2) listOf("arena_ladder_join:" + compact(jobj("ranks" to document.arr("ranks").size))) else emptyList())
                }
            }
        }
        report("queries")
    }
}
