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
import io.github.okexodus.openknights.server.store.AccountRegistry
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
import java.nio.file.StandardCopyOption

/**
 * Local only (OPENKNIGHTS_DEV_DIR with `vectors-g7/friends_*.json` from the maintainer's private exporter,
 * OPENKNIGHTS_ORIGINALS with the player's APK): the friends slice replayed on the reference's own outputs — the request
 * decoders, the friends / mail / chat rules on synthetic participants and world documents, the praise / mail claim /
 * Arena reward planners on every distinct recorded save, and the friends / mail / chat requests through copies of the
 * recorded multi-character worlds (replies, pushes, commits, world documents and world history).
 */
class G7FriendsVectorsTest {
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

    private fun file(name: String): Path? = dev?.resolve("vectors-g7")?.resolve("friends_$name.json")

    private fun vectors(name: String): JObj = Json.loads(Files.readString(file(name)!!)).asObj

    private fun available(vararg names: String): Boolean =
        dev != null && originals != null && names.all { n -> file(n)?.let { Files.isRegularFile(it) } == true }

    private fun inputs() = DailyInputs(GameTables(ApkTables(originals!!.resolve("com.enjoygame.hero2d.apk"))))

    private fun check(label: String, expected: Any?, actual: Any?) {
        checks++
        if (expected != actual) failures.add("$label\n  expected: ${expected.toString().take(900)}\n  actual:   ${actual.toString().take(900)}")
    }

    private fun compact(v: JValue?): String = Json.dumps(v ?: JNull, itemSeparator = ",", keySeparator = ":")

    private fun sorted(v: JValue?): String = Json.dumps(v ?: JNull, sortKeys = true, itemSeparator = ",", keySeparator = ":")

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
        compare(recorded ?: JNull, result)
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

    private fun participantOf(json: JObj): Participant = Participant(json.long("participant_id"), json.str("kind"), json.str("name_raw").hexBytes(),
        json.long("level"), json.long("vip"), json.long("reputation"), (json["power"] as? JStr)?.value?.let { BigInteger(it) },
        json.long("leader_template"), json.arr("lineup").map { e ->
            val x = e.asArr.map { (it as JInt).value.toLong() }
            WorldParticipants.LineupEntry(x[0], x[1], x[2], x[3], x[4], x[5])
        }, json.strOrNull("character_id"), json.str("created"), json.long("gender"), json.obj("extra").deepCopy())

    private fun peopleOf(list: JArr): LinkedHashMap<Long, Participant> {
        val out = LinkedHashMap<Long, Participant>()
        for (p in list) participantOf(p.asObj).let { out[it.participantId] = it }
        return out
    }

    // --- saves -----------------------------------------------------------------------------------------------------------

    private lateinit var saves: Map<String, JObj>

    private fun loadSaves() {
        saves = vectors("saves").arr("saves").associate { it.asObj.str("id") to it.asObj }
    }

    private fun currentOf(save: JObj): StateStore.Current {
        val docs = LinkedHashMap<String, JValue?>()
        for ((k, v) in save.obj("documents")) docs[k] = v.deepCopy()
        return StateStore.Current((save["revision"] as JInt).value.toLong(), "", "", save.obj("state").deepCopy(), ByteArray(0), 0, null,
            save.arr("inventory_items").deepCopy(), emptyList(), save.arr("acquired_items").deepCopy(), emptyList(), null, null, null,
            (save["character_profile"] as? JObj)?.deepCopy(), null, docs)
    }

    private fun setRoles(save: JObj, roles: JObj?) {
        for ((field, bits) in roles ?: JObj()) {
            if (bits == JNull) continue
            for (f in save.obj("state").arr("role_properties")) if (f.asObj.long("id") == field.toLong()) f.asObj.obj("value")["bits"] = bits
        }
    }

    private fun ownedRecord(owned: Owned): JObj = jobj(
        "item_changes" to JArr(owned.itemChanges.entries.mapTo(ArrayList()) { (u, c) -> jarr(u, c) }),
        "new_items" to JArr(owned.newItems.entries.mapTo(ArrayList()) { (u, e) -> jarr(u, e.first, e.second) }),
        "role_changes" to JArr(owned.roleChanges.entries.mapTo(ArrayList()) { (f, c) -> jarr(f, c.first, c.second) }),
        "log" to owned.log)

    private fun hashes(state: JObj): JObj = jobj("state" to sha(state), "role_properties" to sha(state["role_properties"]), "items" to sha(state["items"]))

    // --- rules -----------------------------------------------------------------------------------------------------------

    @Test
    fun `decoders and the friends, mail and chat rules`() {
        assumeTrue(available("rules"), "OPENKNIGHTS_DEV_DIR / OPENKNIGHTS_ORIGINALS not set: local-only test skipped")
        val doc = vectors("rules")
        blobs = doc.obj("blobs")
        val inputs = inputs()
        for ((i, v) in doc.arr("decoders").withIndex()) {
            val vector = v.asObj
            val p = vector.str("payload").hexBytes()
            val l = "decode $i ${vector.str("payload")}"
            replay("$l recommend", vector["recommend"], { Friends.decodeRecommend(p) }) { r, out -> check("$l recommend", compact(r), compact(out)) }
            replay("$l reply", vector["reply"], { Friends.decodeReply(p) }) { r, out -> check("$l reply", compact(r), compact(out)) }
            replay("$l praise", vector["praise"], { Friends.decodePraise(p) }) { r, out -> check("$l praise", compact(r), compact(out)) }
            replay("$l name", vector["name"], { Friends.decodeName(p, 361).toHexString() }) { r, out -> check("$l name", (r as JStr).value, out) }
            replay("$l id", vector["id"], { Friends.decodeId(p, 385) }) { r, out -> check("$l id", (r as JInt).value.toLong(), out) }
            replay("$l mail id", vector["mail_id"], { Mail.decodeId(p, 195) }) { r, out -> check("$l mail id", (r as JInt).value.toLong(), out) }
            replay("$l strings3", vector["strings3"], { Mail.decodeStrings(p, 3, 201).map { it.toHexString() } }) { r, out ->
                check("$l strings3", r.asArr.map { (it as JStr).value }, out)
            }
            replay("$l strings2", vector["strings2"], { Mail.decodeStrings(p, 2, 2169).map { it.toHexString() } }) { r, out ->
                check("$l strings2", r.asArr.map { (it as JStr).value }, out)
            }
            replay("$l chat", vector["chat"], { Chat.decodeChat(p) }) { r, out ->
                check("$l chat", compact(r), compact(jobj("channel" to out.channel, "target" to out.target.toHexString(), "text" to out.text.toHexString())))
            }
        }
        for ((i, v) in doc.arr("info").withIndex()) {
            val vector = v.asObj
            val person = participantOf(vector.obj("person"))
            val state = vector["state"]?.takeIf { it != JNull } as JObj?
            replay("info $i", vector["result"], {
                Friends.playerInfoPayload(person, state, vector.obj("presence"), vector.long("now"), vector.long("offset")).toHexString()
            }) { r, hex -> check("info $i", (r as JStr).value, hex) }
        }
        for ((i, v) in doc.arr("recommend").withIndex()) {
            val vector = v.asObj
            val people = peopleOf(vector.arr("participants"))
            replay("recommend $i", vector["result"], {
                Friends.recommendPayload(vector.obj("social"), vector.long("role"), people, vector.obj("presence"), vector.long("now"),
                    vector.long("count"), vector.long("kind"), vector.long("own_level"), vector.long("offset"),
                    if (vector["inputs"] == JBool(true)) inputs else null).toHexString()
            }) { r, hex -> check("recommend $i", (r as JStr).value, hex) }
        }
        for ((i, v) in doc.arr("social").withIndex()) {
            val vector = v.asObj
            val args = vector.obj("args")
            val work = vector.obj("social").deepCopy()
            val role = vector.long("role")
            val l = "social $i ${vector.str("op")}"
            replay(l, vector["result"], {
                when (vector.str("op")) {
                    "add" -> { Friends.add(work, role, args.long("target"), args.long("own_max"), vector.long("now")); jobj("doc" to work) }
                    "reply" -> {
                        val result = Friends.reply(work, role, args.long("requester"), args.bool("accept"), args.long("own_max"), args.long("their_max"))
                        jobj("result" to result, "doc" to work)
                    }
                    else -> { Friends.remove(work, role, args.long("target")); jobj("doc" to work) }
                }
            }) { r, out -> check(l, sorted(r), sorted(out)) }
        }
        mailRules(doc, inputs)
        chatRules(doc, inputs)
        for (v in doc.arr("command")) {
            val vector = v.asObj
            check("command ${vector.str("text")}", vector.str("reply"), Chat.commandReply(vector.str("text").hexBytes()).toHexString())
        }
        report("rules")
    }

    private fun mailRules(doc: JObj, inputs: DailyInputs) {
        for ((i, v) in doc.arr("mail").withIndex()) {
            val vector = v.asObj
            val op = vector.str("op")
            val work = vector.obj("doc").deepCopy()
            val l = "mail $i $op"
            if (op == "write") {
                val sender = participantOf(vector.obj("sender"))
                val recipient = (vector["recipient"] as? JObj)?.let { participantOf(it) }
                replay(l, vector["result"], {
                    val (code, mail) = Mail.write(work, sender, recipient, vector.str("title").hexBytes(), vector.str("body").hexBytes(), inputs, vector.long("now"))
                    jobj("code" to code, "mail" to mail, "doc" to work)
                }) { r, out -> check(l, sorted(r), sorted(out)) }
                continue
            }
            val role = vector.long("role")
            val mailId = vector.long("mail_id")
            val claimed = vector.arr("claimed").map { (it as JInt).value.toLong() }
            val hide = vector["hide"] == JBool(true)
            replay(l, vector["result"], {
                when (op) {
                    "read" -> jobj("frames" to JArr(framesOf(Mail.read(work, role, mailId, hide)).mapTo(ArrayList()) { JStr(it) }), "doc" to work)
                    "delete" -> jobj("frames" to JArr(framesOf(Mail.delete(work, role, mailId, claimed)).mapTo(ArrayList()) { JStr(it) }), "doc" to work)
                    "remove" -> { Mail.remove(work, role, mailId); jobj("doc" to work) }
                    "content" -> Mail.find(work, role, mailId)?.let { JStr(Mail.contentPayload(it, hide).toHexString()) } ?: JNull
                    "unpaid" -> JBool(Mail.praiseUnpaid(Mail.find(work, role, mailId), vector["ledger"]?.takeIf { it != JNull }))
                    "blacklist" -> {
                        val hex = (vector["result"] as JObj).strOrNull("name") ?: "61"      // the over-long list's vector records only its error
                        jobj("payload" to Mail.blacklistPayload(work, role).toHexString(), "blocked" to Mail.blocked(work, role, hex.hexBytes()), "name" to hex)
                    }
                    else -> Mail.find(work, role, mailId) ?: JNull
                }
            }) { r, out ->
                if (op == "read" || op == "delete") {
                    r as JObj
                    check("$l frames", framesOf(r["frames"]!!), (out as JObj).arr("frames").map { (it as JStr).value })
                    check("$l doc", sorted(r["doc"]), sorted(out["doc"]))
                } else check(l, sorted(r), sorted(out))
            }
        }
    }

    private fun chatRules(doc: JObj, inputs: DailyInputs) {
        for ((i, v) in doc.arr("chat").withIndex()) {
            val vector = v.asObj
            val people = vector.arr("participants").map { participantOf(it.asObj) }
            val byName = LinkedHashMap<String, Participant>()
            for (p in people) byName[Chat.nameKey(p.nameRaw)] = p
            val sender = (vector["sender"] as? JInt)?.let { people[it.toInt()] }
            val req = vector.obj("request")
            val request = Chat.Request(req.long("channel"), req.str("target").hexBytes(), req.str("text").hexBytes())
            val work = vector.obj("doc").deepCopy()
            replay("chat $i", vector["result"], {
                val (deliveries, line) = Chat.send(work, request, sender, vector.long("guild_id"), vector.arr("members").map { (it as JInt).toLong() },
                    byName, inputs, vector.long("now"))
                jobj("deliveries" to JArr(deliveries.mapTo(ArrayList()) { d ->
                    jarr(if (d.world) "world" else d.recipient, d.frame.first, d.frame.second.toHexString())
                }), "line" to line, "doc" to work)
            }) { r, out -> check("chat $i", sorted(r), sorted(out)) }
        }
    }

    // --- plans -----------------------------------------------------------------------------------------------------------

    @Test
    fun `praise, mail claim and Arena reward planners on every recorded save`() {
        assumeTrue(available("plans", "saves"), "OPENKNIGHTS_DEV_DIR / OPENKNIGHTS_ORIGINALS not set: local-only test skipped")
        loadSaves()
        val doc = vectors("plans")
        blobs = doc.obj("blobs")
        val inputs = inputs()
        for ((i, v) in doc.arr("praise").withIndex()) {
            val vector = v.asObj
            val save = saves.getValue(vector.str("save")).deepCopy()
            setRoles(save, vector["roles"] as? JObj)
            if (vector["extra_ach"] == JBool(true)) {
                (save.obj("state").obj("subsystems")["achievements"] as? JObj)?.arr("entries")?.add(jobj("wire_values" to jarr(26, 3, 7)))
            }
            val social = vector.obj("social").deepCopy()
            val cur = currentOf(save)
            var owned: Owned? = null
            val l = "praise $i ${vector.str("save")}"
            replay(l, vector["result"], {
                owned = Owned(cur, inputs)
                Friends.planPraise(social, vector.long("role"), vector.long("target"), vector.long("kind"), owned!!, inputs, vector.long("now"))
            }) { r, praise ->
                r as JObj
                check("$l praised", r["praised"], JBool(praise.praised))
                check("$l frames", framesOf(r["frames"]!!), framesOf(praise.packets))
                check("$l social", sorted(r["social"]), sorted(social))
                check("$l owned", compact(r["owned"]), compact(ownedRecord(owned!!)))
                check("$l hashes", compact(r["hashes"]), compact(hashes(owned!!.state)))
            }
        }
        for ((i, v) in doc.arr("claim").withIndex()) {
            val vector = v.asObj
            val save = saves.getValue(vector.str("save")).deepCopy()
            setRoles(save, vector["roles"] as? JObj)
            val cur = currentOf(save)
            var owned: Owned? = null
            val fn = vector.str("fn")
            val l = "claim $i $fn ${vector.str("save")}"
            replay(l, vector["result"], {
                owned = Owned(cur, inputs)
                val mail = vector.obj("mail").deepCopy()
                val ledger = vector["ledger"]?.takeIf { it != JNull }?.deepCopy()
                if (fn == "claim") Mail.planClaim(mail, owned!!, inputs, ledger) else Mail.planPraiseRead(mail, owned!!, inputs, ledger)
            }) { r, plan -> comparePlan(l, r as JObj, plan, owned!!) }
        }
        for ((i, v) in doc.arr("arena").withIndex()) {
            val vector = v.asObj
            val save = saves.getValue(vector.str("save")).deepCopy()
            setRoles(save, vector["roles"] as? JObj)
            installClock(vector.obj("clock"))
            val cur = currentOf(save)
            val rows = vector.arr("rows").map { r -> (r.asArr[0] as JInt).toLong() to participantOf(r.asArr[1].asObj) }
            var owned: Owned? = null
            val l = "arena $i ${vector.str("save")}"
            replay(l, vector["result"], {
                owned = Owned(cur, inputs)
                Arena.planReward(owned!!, inputs, vector["document"]?.takeIf { it != JNull }?.deepCopy(), vector.long("rank"), vector.long("now"), rows)
            }) { r, plan -> comparePlan(l, r as JObj, plan, owned!!) }
        }
        val driver = JdbcSqlDriver()
        var worldCount = 0
        for ((i, v) in doc.arr("arena_route").withIndex()) {
            val vector = v.asObj
            val save = saves.getValue(vector.str("save")).deepCopy()
            val state = vector["arena_state"]
            if (state == null || state == JNull) save.obj("documents").remove("arena_state") else save.obj("documents")["arena_state"] = state.deepCopy()
            installClock(vector.obj("clock"))
            val now = vector.long("now")
            val world = WorldDirectory.initialize(temp.resolve("arena-${worldCount++}.sqlite3"), driver, "vectors")
            world.putDocument("royal_door", jobj("profile" to "royal_door_world_v1", "level" to 1, "exp" to 0, "born_at_utc" to "2026-09-10T10:00:00.000+00:00"),
                1, "vectors", "vectors")
            world.putDocument("arena_ladder", vector.obj("ladder"), 1, "vectors", "vectors")
            val power = (vector["power"] as? JStr)?.value?.let { BigInteger(it) }
            val worldCtx = DailyRoutes.WorldContext(world, null, power?.let { p -> { _: StateStore.Current -> p } }, emptyList()) { ctx, current ->
                if (current == null) emptyList()
                else listOf(WorldParticipants.characterParticipant(jobj("character_id" to null, "created_at_utc" to ""), current, ctx.powerOf))
            }
            val cur = currentOf(save)
            var owned: Owned? = null
            val l = "arena route $i ${vector.str("save")}"
            replay(l, vector["result"], {
                val routed = DailyRoutes.plannerFor(423, vector.str("payload").hexBytes(), inputs, null, now, { now }, worldCtx, save.str("owner_key"))
                owned = Owned(cur, inputs)
                routed to routed.planner(owned!!, cur)
            }) { r, (routed, plan) ->
                r as JObj
                check("$l action", r.str("action"), routed.action)
                check("$l request", compact(r["request"]), compact(routed.request))
                comparePlan(l, r, plan, owned!!)
                val after = r.obj("world")
                val (revision, ladder) = world.document("arena_ladder")!!
                check("$l ladder", sorted(after["document"]), sorted(ladder))
                check("$l ladder revision", after.long("revision"), revision - 1)
            }
        }
        report("plans")
    }

    private fun comparePlan(l: String, r: JObj, plan: Plan, owned: Owned) {
        check("$l plan", compact(r["plan"]), compact(plan.data))
        check("$l frames", framesOf(r["frames"]!!), framesOf(plan.packets))
        check("$l owned", compact(r["owned"]), compact(ownedRecord(owned)))
        check("$l hashes", compact(r["hashes"]), compact(hashes(owned.state)))
    }

    // --- routes through the recorded worlds --------------------------------------------------------------------------------

    private class RouteWorld(val dir: Path, val pristine: ByteArray)

    private fun copyTree(source: Path, target: Path) {
        Files.walk(source).use { stream ->
            for (p in stream) {
                val to = target.resolve(source.relativize(p).toString())
                if (Files.isDirectory(p)) Files.createDirectories(to) else Files.copy(p, to, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    @Test
    fun `friends, mail and chat requests through the recorded worlds`() {
        assumeTrue(available("routes"), "OPENKNIGHTS_DEV_DIR / OPENKNIGHTS_ORIGINALS not set: local-only test skipped")
        val doc = vectors("routes")
        blobs = doc.obj("blobs")
        val inputs = inputs()
        val driver = JdbcSqlDriver()
        val worlds = HashMap<String, RouteWorld>()
        for ((n, rel) in doc.obj("worlds").keys.withIndex()) {
            val source = dev!!.resolve(rel)
            val dir = temp.resolve("world-$n")
            copyTree(source, dir)
            worlds[rel] = RouteWorld(dir, Files.readAllBytes(source.resolve("world.sqlite3")))
        }
        for ((i, v) in doc.arr("vectors").withIndex()) {
            val vector = v.asObj
            val rw = worlds.getValue(vector.str("world"))
            for (suffix in listOf("-wal", "-shm", "-journal")) Files.deleteIfExists(rw.dir.resolve("world.sqlite3$suffix"))
            Files.write(rw.dir.resolve("world.sqlite3"), rw.pristine)
            val world = WorldDirectory(rw.dir.resolve("world.sqlite3"), driver)
            val registry = AccountRegistry(rw.dir.resolve("registry.sqlite3"), driver)
            for ((name, d) in vector.obj("world_docs")) world.putDocument(name, d.asObj, world.document(name)!!.first, "vectors", "vectors")
            val before = world.connect(readOnly = true).use { db -> db.queryOne("SELECT COALESCE(MAX(rowid), 0) AS n FROM world_history")!!.long("n") }
            val requester = vector.str("requester")
            val current = withOverrides(registry.resolveStateStore(requester).read(), vector.obj("requester_overrides"))
            val powers = vector.obj("powers").entries.associate { (k, p) -> k.toLong() to (p as? JStr)?.value?.let { BigInteger(it) } }
            val offsets = vector.obj("push_offsets").entries.associate { (k, o) -> k.toLong() to (o as JInt).toLong() }
            val pushes = JArr()
            val commits = JArr()
            val ctx = SocialRoutes.SocialContext(world, registry, inputs, emptyList(), { role, frames ->
                pushes.add(jarr(role, JArr(framesOf(frames(offsets[role] ?: 0L)).mapTo(ArrayList()) { JStr(it) })))
            }, "local-service", vector.long("clock_offset")) { c -> powers[SocialRoutes.roleOf(c)] }
            val commit = SocialRoutes.Commit { action, planner ->
                val cur = PyDocs.deepCopy(current)
                val owned = Owned(cur, inputs)
                val plan = planner(owned, cur)
                commits.add(jobj("action" to action, "plan" to plan.data.deepCopy(), "frames" to JArr(framesOf(plan.packets).mapTo(ArrayList()) { JStr(it) }),
                    "owned" to ownedRecord(owned), "hashes" to hashes(owned.state)))
                plan
            }
            val opcode = vector.long("opcode").toInt()
            val now = vector.long("now")
            val l = "route $i C$opcode ${vector.str("payload")} ${vector.str("world").substringAfter("bundles/").substringBefore("/")}"
            replay(l, vector["result"], {
                SocialRoutes.dispatch(opcode, vector.str("payload").hexBytes(), current, ctx, commit, now, now, requester,
                    vector.arr("online").map { (it as JInt).toLong() })
            }) { r, frames -> check("$l frames", framesOf((r as JObj)["frames"]!!), framesOf(frames)) }
            val recPushes = vector.arr("pushes").map { p -> "${p.asArr[0]}=" + framesOf(p.asArr[1]).joinToString(",") }
            check("$l pushes", recPushes, pushes.map { p -> "${p.asArr[0]}=" + p.asArr[1].asArr.joinToString(",") { (it as JStr).value } })
            val recCommits = vector.arr("commits")
            check("$l commit count", recCommits.size, commits.size)
            for (k in 0 until minOf(recCommits.size, commits.size)) {
                val rc = recCommits[k].asObj
                val kc = commits[k].asObj
                check("$l commit $k action", rc.str("action"), kc.str("action"))
                check("$l commit $k plan", compact(rc["plan"]), compact(kc["plan"]))
                check("$l commit $k frames", framesOf(rc["frames"]!!), kc.arr("frames").map { (it as JStr).value })
                check("$l commit $k owned", compact(rc["owned"]), compact(kc["owned"]))
                check("$l commit $k hashes", compact(rc["hashes"]), compact(kc["hashes"]))
            }
            for ((name, after) in vector.obj("world_after")) {
                val (revision, document) = world.document(name)!!
                check("$l world $name revision", (after.asArr[0] as JInt).toLong(), revision)
                check("$l world $name", sorted(after.asArr[1]), sorted(document))
            }
            val history = world.connect(readOnly = true).use { db ->
                db.query("SELECT action, detail_json FROM world_history WHERE rowid > ? ORDER BY rowid", before).map { row ->
                    row.string("action") + ":" + sorted(Json.loads(row.string("detail_json")))
                }
            }
            check("$l history", vector.arr("history").map { h -> (h.asArr[0] as JStr).value + ":" + sorted(h.asArr[1]) }, history)
        }
        println("routes: ${doc.arr("vectors").size} vectors")
        report("routes")
    }

    /** The requester's read with the vector's role values, documents and extra achievement entries. */
    private fun withOverrides(read: StateStore.Current, ov: JObj): StateStore.Current {
        val state = read.state
        for ((field, bits) in (ov["roles"] as? JObj) ?: JObj()) {
            for (f in state.arr("role_properties")) if (f.asObj.long("id") == field.toLong()) f.asObj.obj("value")["bits"] = bits
        }
        val extra = ov["achievements"] as? JArr
        if (extra != null && extra.isNotEmpty()) (state.obj("subsystems")["achievements"] as? JObj)?.arr("entries")?.addAll(extra.deepCopy())
        val docs = LinkedHashMap(read.documents)
        for ((table, d) in (ov["documents"] as? JObj) ?: JObj()) docs[table] = d.deepCopy()
        return StateStore.Current(read.revision, read.sourceSha256, read.payloadSha256, state, read.payload, read.inventorySchemaVersion,
            read.inventorySha256, read.inventoryItems, read.inventoryPayloads, read.acquiredItems, read.acquiredPayloads, read.acquiredSha256,
            read.secondaryTeam, read.godSkills, read.characterProfile, read.jewelryList, docs, read.fixtureInjectedHeroUids, read.acquiredHeroUids)
    }
}
