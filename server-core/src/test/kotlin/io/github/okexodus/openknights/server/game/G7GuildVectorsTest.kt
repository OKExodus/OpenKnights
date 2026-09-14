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
 * Local only (OPENKNIGHTS_DEV_DIR with `vectors-g7/guild_*.json` from the maintainer's private exporter,
 * OPENKNIGHTS_ORIGINALS with the player's APK): the guild slice replayed on the reference's own outputs — the name,
 * codec, task board, reward, level and schedule rules, and every guild request of the slice through
 * [SocialRoutes.dispatch] on synthetic and recorded world guilds (reply frames or refusals, pushes to other players,
 * the character transactions' plans / frames / owned changes, the world writes with their history details).
 */
class G7GuildVectorsTest {
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

    private fun file(name: String): Path? = dev?.resolve("vectors-g7")?.resolve("guild_$name.json")

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

    private var blobs = JObj()

    private fun framesOf(recorded: JValue): List<String> = recorded.asArr.map { f -> "${f.asArr[0]}:${blobs.str((f.asArr[1] as JStr).value)}" }

    private fun framesOf(frames: List<Frame>): List<String> = frames.map { "${it.first}:${it.second.toHexString()}" }

    private fun isError(recorded: JValue?): Boolean =
        (recorded as? JObj)?.let { it.containsKey("error") && it.containsKey("message") && it.containsKey("value_error") } == true

    private fun report(name: String) {
        println("$name: $checks checks, ${failures.size} failures")
        assertTrue(failures.isEmpty()) { "$name: ${failures.size} of $checks checks differ:\n" + failures.take(25).joinToString("\n") }
    }

    /** Compare an exception with the recorded error (ValueError family, message, code). */
    private fun checkError(label: String, recorded: JValue?, e: Exception) {
        if (e is NotPorted) throw e
        if (!isError(recorded)) {
            failures.add("$label: unexpected ${e::class.simpleName}: ${e.message}\n${e.stackTrace.take(8).joinToString("\n")}")
            return
        }
        val rec = recorded as JObj
        if (rec.str("error") != "error") check("$label error kind", rec["value_error"] == JBool(true), e is IllegalArgumentException)
        if (rec["value_error"] == JBool(true)) check("$label error", rec.str("message"), e.message)
        val code = rec["code"]
        if (code != null && code != JNull) check("$label code", (code as JInt).value.toInt(), (e as? Acquisition.Rejected)?.code)
    }

    private fun <T> replay(label: String, recorded: JValue?, block: () -> T, compare: (JValue, T) -> Unit) {
        val result = try {
            block()
        } catch (e: Exception) {
            checkError(label, recorded, e)
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

    // --- rules -----------------------------------------------------------------------------------------------------------

    @Test
    fun `names, codecs, task boards, rewards, levels and the schedule`() {
        assumeTrue(available("rules"), "OPENKNIGHTS_DEV_DIR / OPENKNIGHTS_ORIGINALS not set: local-only test skipped")
        val doc = vectors("rules")
        val inputs = inputs()
        val nameDocs = doc.arr("name_docs").map { it.asObj }
        for ((i, v) in doc.arr("validate_name").withIndex()) {
            val vector = v.asObj
            replay("validate_name $i", vector["result"], {
                Guild.validateName(vector.str("raw").hexBytes(), nameDocs[vector.long("doc").toInt()], inputs, vector.strOrNull("exclude")).toHexString()
            }) { rec, hex -> check("validate_name $i", (rec as JStr).value, hex) }
        }
        for ((i, v) in doc.arr("decode").withIndex()) {
            val vector = v.asObj
            val payload = vector.str("payload").hexBytes()
            when (vector.str("fn")) {
                "cstrings" -> replay("cstrings $i", vector["result"], {
                    Guild.decodeCstrings(payload, vector.long("count").toInt(), 2181).map { it.toHexString() }
                }) { rec, parts -> check("cstrings $i", rec.asArr.map { (it as JStr).value }, parts) }
                "u32" -> replay("u32 $i", vector["result"], { Guild.decodeU32(payload, 2203) }) { rec, n -> check("u32 $i", (rec as JInt).value.toLong(), n) }
                else -> replay("task_donate $i", vector["result"], { Guild.decodeTaskDonate(payload) }) { rec, out -> check("task_donate $i", compact(rec), compact(out)) }
            }
        }
        for ((i, v) in doc.arr("task_document").withIndex()) {
            val vector = v.asObj
            installClock(vector.obj("clock"))
            val stored = vector["document"]?.takeIf { it != JNull }
            val now = vector.long("now")
            replay("task_document $i", vector["result"], { Guild.taskDocument(stored?.deepCopy(), inputs, now, vector.str("owner")) }) { rec, out ->
                check("task_document $i", compact(rec), compact(out))
            }
            replay("tasks_payload $i", vector["payload"], {
                Guild.tasksPayload(Guild.taskDocument(stored?.deepCopy(), inputs, now, vector.str("owner")), now).toHexString()
            }) { rec, hex -> check("tasks_payload $i", (rec as JStr).value, hex) }
        }
        for ((i, v) in doc.arr("task_reward").withIndex()) {
            val vector = v.asObj
            val r = Guild.taskReward(inputs.guildTask(vector.long("task"))!!, vector.long("star"), vector.int("level"), inputs)
            check("task_reward $i", compact(vector["result"]), compact(jarr(r.exp, r.gold, r.points, r.item?.let { jarr(it.first, it.second) })))
        }
        for ((i, v) in doc.arr("level").withIndex()) {
            val vector = v.asObj
            val guild = jobj("popularity" to vector["popularity"], "badge" to vector["badge"])
            check("level $i", vector.long("level"), Guild.levelOf(guild, inputs))
            replay("max $i", vector["max"], { Guild.maxMembers(guild, inputs) }) { rec, n -> check("max $i", (rec as JInt).value.toLong(), n) }
        }
        for ((i, v) in doc.arr("tech_cost").withIndex()) {
            val vector = v.asObj
            check("tech_cost $i", vector.long("cost"), Guild.techCost(inputs.guildTech(vector.long("tech"))!!, vector.long("level")))
        }
        for ((i, v) in doc.arr("war").withIndex()) {
            val vector = v.asObj
            installClock(vector.obj("clock"))
            val now = vector.long("now")
            check("war state $i", vector.long("state").toInt(), Guild.warState(Guild.hour(now)))
            check("boss $i", vector.bool("boss"), Guild.bossOpen(now, inputs))
            check("boss default $i", vector.bool("boss_default"), Guild.bossOpen(now))
            check("match $i", vector.long("match"), Guild.matchTime(now))
            check("day $i", vector.str("day"), Guild.day(now))
        }
        report("rules")
    }

    // --- requests --------------------------------------------------------------------------------------------------------

    private fun participantOf(json: JObj): Participant = Participant(json.long("participant_id"), json.str("kind"), json.str("name_raw").hexBytes(),
        json.long("level"), json.long("vip"), json.long("reputation"), (json["power"] as? JStr)?.value?.let { BigInteger(it) },
        json.long("leader_template"), json.arr("lineup").map { e ->
            val x = e.asArr.map { (it as JInt).value.toLong() }
            WorldParticipants.LineupEntry(x[0], x[1], x[2], x[3], x[4], x[5])
        }, json.strOrNull("character_id"), json.str("created"), json.long("gender"), json.obj("extra").deepCopy())

    /** A save with the vector's overrides: role bits, documents (null = absent), appended item stacks. */
    private fun applyOverrides(save: JObj, ov: JObj): JObj {
        val out = save.deepCopy()
        val state = out.obj("state")
        (ov["roles"] as? JObj)?.forEach { (field, bits) ->
            for (f in state.arr("role_properties")) if ((f as JObj).long("id") == field.toLong()) f.obj("value")["bits"] = bits
        }
        (ov["docs"] as? JObj)?.forEach { (table, doc) -> if (doc == JNull) out.obj("documents").remove(table) else out.obj("documents")[table] = doc.deepCopy() }
        (ov["items"] as? JArr)?.forEach { item ->
            val x = item.asArr
            state.arr("items").add(jobj("wire_values" to jarr(x[0], x[1], x[2]), "timed_flag" to x[3]))
        }
        return out
    }

    private fun currentOf(save: JObj): StateStore.Current {
        val docs = LinkedHashMap<String, JValue?>()
        for ((k, v) in save.obj("documents")) docs[k] = v.deepCopy()
        return StateStore.Current((save["revision"] as JInt).value.toLong(), "", "", save.obj("state").deepCopy(), ByteArray(0), 0, null,
            save.arr("inventory_items").deepCopy(), emptyList(), save.arr("acquired_items").deepCopy(), emptyList(), null, null, null,
            (save["character_profile"] as? JObj)?.deepCopy(), null, docs)
    }

    private fun ownedRecord(owned: Owned): JObj = jobj(
        "item_changes" to JArr(owned.itemChanges.entries.mapTo(ArrayList()) { (u, c) -> jarr(u, c) }),
        "new_items" to JArr(owned.newItems.entries.mapTo(ArrayList()) { (u, e) -> jarr(u, e.first, e.second) }),
        "role_changes" to JArr(owned.roleChanges.entries.mapTo(ArrayList()) { (f, c) -> jarr(f, c.first, c.second) }),
        "log" to owned.log)

    /** The session's participant list (the reference's `ctx._people`), by role. */
    private fun peopleMap(people: List<Participant>): Map<Long, Participant> =
        LinkedHashMap<Long, Participant>().also { m -> for (p in people) m[p.participantId] = p }

    private val pushOffsets = listOf(0L, 3600L, -5000L)
    private val worldNames = listOf("guilds", "mail", "presence")

    @Test
    fun `every guild request on synthetic and recorded world guilds`() {
        assumeTrue(available("requests", "saves"), "OPENKNIGHTS_DEV_DIR / OPENKNIGHTS_ORIGINALS not set: local-only test skipped")
        val saves = vectors("saves").arr("saves").associate { it.asObj.str("id") to it.asObj }
        val doc = vectors("requests")
        blobs = doc.obj("blobs")
        val inputs = inputs()
        val worlds = doc.obj("worlds")
        val peoples = doc.obj("people").entries.associate { (k, v) -> k to v.asArr.map { participantOf(it.asObj) } }
        val world = WorldDirectory.initialize(temp.resolve("world.sqlite3"), JdbcSqlDriver(), "vectors")
        var ran = 0
        for ((i, v) in doc.arr("vectors").withIndex()) {
            val vector = v.asObj
            val opcode = vector.long("opcode").toInt()
            val label = "request $i ${vector.str("world")} C$opcode ${vector.str("payload")}"
            installClock(vector.obj("clock"))
            val save = applyOverrides(saves.getValue(vector.str("save")), vector.obj("ov"))
            val docs = worlds.obj(vector.str("world"))
            for (name in worldNames) world.putDocument(name, docs.obj(name), world.document(name)!!.first, "vectors", "vectors")
            val sequence = world.connect(readOnly = true).use { it.queryOne("SELECT MAX(sequence) AS m FROM world_history")!!.long("m") }
            val pushes = ArrayList<Pair<Long, List<List<Frame>>>>()
            val ctx = SocialRoutes.SocialContext(world, null, inputs, pushFn = { role, builder -> pushes.add(role to pushOffsets.map { builder(it) }) },
                clockOffset = vector.long("offset"), people = peopleMap(peoples.getValue(vector.str("people"))))
            val commits = ArrayList<JObj>()
            val commit = SocialRoutes.Commit { action, planner ->
                val cur = currentOf(save)
                val owned = Owned(cur, inputs)
                val plan = planner(owned, cur)
                commits.add(jobj("action" to action, "plan" to plan.data.deepCopy(), "frames" to framesOf(plan.packets), "owned" to ownedRecord(owned),
                    "state" to sha(owned.state)))
                plan
            }
            replay(label, vector["result"], {
                SocialRoutes.dispatch(opcode, vector.str("payload").hexBytes(), currentOf(save), ctx, commit, vector.long("now"), vector.long("served"),
                    vector.str("owner_key"))
            }) { rec, frames -> check("$label frames", framesOf((rec as JObj)["frames"]!!), framesOf(frames)) }
            val recCommits = vector.arr("commits")
            check("$label commits", recCommits.size, commits.size)
            for ((k, c) in recCommits.withIndex()) {
                if (k >= commits.size) break
                val r = c.asObj
                val mine = commits[k]
                check("$label commit $k action", r.str("action"), mine.str("action"))
                check("$label commit $k plan", compact(r["plan"]), compact(mine["plan"]))
                check("$label commit $k frames", framesOf(r["frames"]!!), mine.arr("frames").map { (it as JStr).value })
                check("$label commit $k owned", compact(r["owned"]), compact(mine["owned"]))
                check("$label commit $k state", r.str("state"), mine.str("state"))
            }
            val recPushes = vector.arr("pushes").map { p -> p.asArr[0].let { (it as JInt).value.toLong() } to p.asArr[1].asArr.map { framesOf(it) } }
            check("$label pushes", recPushes, pushes.map { (role, built) -> role to built.map { framesOf(it) } })
            val log = world.connect(readOnly = true).use { db ->
                db.query("SELECT action, detail_json FROM world_history WHERE sequence > ? ORDER BY sequence", sequence).map { row ->
                    val detail = Json.loads(row.string("detail_json")).asObj
                    val name = detail.str("name")
                    detail.remove("name")
                    detail.remove("revision")
                    listOf(name, row.string("action"), Json.canonical(detail))
                }
            }
            check("$label log", vector.arr("log").map { e -> listOf(e.asArr[0].let { (it as JStr).value }, (e.asArr[1] as JStr).value, Json.canonical(e.asArr[2])) }, log)
            for ((name, checksum) in vector.obj("after")) {
                check("$label after $name", (checksum as JStr).value, Json.checksum(world.document(name)!!.second))
            }
            ran++
        }
        println("requests: $ran vectors")
        report("requests")
    }
}
