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
 * Local only (OPENKNIGHTS_DEV_DIR with `vectors-g1/` from the maintainer's private exporter, OPENKNIGHTS_ORIGINALS with
 * the player's APK): the daily systems' login functions replayed on the reference's own outputs — every distinct
 * character save of the recorded roots at several device clocks — and on synthetic vectors of the seeded draws. Every
 * frame, plan, document and state must be identical.
 */
class G1DailyVectorsTest {
    @TempDir
    lateinit var temp: Path

    private val dev: Path? = System.getenv("OPENKNIGHTS_DEV_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
    private val originals: Path? = System.getenv("OPENKNIGHTS_ORIGINALS")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
    private val failures = ArrayList<String>()
    private var checks = 0

    @AfterEach
    fun reset() {
        DeviceClock.active = null
        Events.setActive(null)
    }

    private fun vectors(name: String): JObj? {
        val file = dev?.resolve("vectors-g1")?.resolve("$name.json") ?: return null
        return if (Files.isRegularFile(file)) Json.loads(Files.readString(file)).asObj else null
    }

    private fun inputs(): DailyInputs {
        val apk = originals!!.resolve("com.enjoygame.hero2d.apk")
        return DailyInputs(GameTables(ApkTables(apk)))
    }

    private fun available(): Boolean = dev != null && originals != null && Files.isRegularFile(dev.resolve("vectors-g1").resolve("saves.json"))

    // --- helpers ------------------------------------------------------------------------------------------------------

    private fun check(label: String, expected: Any?, actual: Any?) {
        checks++
        if (expected != actual) failures.add("$label\n  expected: ${expected.toString().take(600)}\n  actual:   ${actual.toString().take(600)}")
    }

    private fun compact(v: JValue?): String = Json.dumps(v ?: JNull, itemSeparator = ",", keySeparator = ":")

    private fun sha(v: JValue?): String = sha256Hex(compact(v).toByteArray(Charsets.UTF_8)).take(24)

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

    private lateinit var blobs: JObj

    private fun framesOf(recorded: JValue): List<String> = recorded.asArr.map { f -> "${f.asArr[0]}:${blobs.str((f.asArr[1] as JStr).value)}" }

    private fun framesOf(frames: List<Frame>): List<String> = frames.map { "${it.first}:${it.second.toHexString()}" }

    /** Compare one recorded outcome ({error} or a value) with the port's. */
    private fun outcome(label: String, recorded: JValue?, run: () -> Any?, compare: (JValue, Any?) -> Unit) {
        val result = try { Result.success(run()) } catch (e: NotPorted) { throw e } catch (e: Exception) { Result.failure(e) }
        val error = (recorded as? JObj)?.takeIf { it.containsKey("error") && it.containsKey("message") }
        if (error != null) {
            checks++
            val e = result.exceptionOrNull()
            if (e == null) { failures.add("$label: expected ${error.str("error")} (${error.str("message")}), got ${result.getOrNull().toString().take(300)}"); return }
            if (error.str("error") == "AcquisitionRejected") {
                check("$label rejection", error.str("message") to error["code"], (e.message ?: "") to (if (e is Acquisition.Rejected) JInt(e.code) else JNull))
            } else if (error.bool("value_error")) {
                check("$label ValueError", error.str("message"), e.message)
            }
            return
        }
        val e = result.exceptionOrNull()
        if (e != null) { failures.add("$label: unexpected ${e::class.simpleName}: ${e.message}\n${e.stackTrace.take(6).joinToString("\n")}"); return }
        compare(recorded!!, result.getOrNull())
    }

    private fun currentOf(save: JObj): StateStore.Current {
        val docs = LinkedHashMap<String, JValue?>()
        for ((k, v) in save.obj("documents")) docs[k] = v.deepCopy()
        return StateStore.Current((save["revision"] as JInt).value.toLong(), "", "", save.obj("state").deepCopy(), ByteArray(0), 0, null,
            save.arr("inventory_items").deepCopy(), emptyList(), save.arr("acquired_items").deepCopy(), emptyList(), null, null, null,
            (save["character_profile"] as? JObj)?.deepCopy(), null, docs)
    }

    private fun applyOverrides(save: JObj, overrides: JObj?): JObj {
        val out = save.deepCopy()
        if (overrides == null) return out
        (overrides["documents"] as? JObj)?.forEach { (table, doc) -> if (doc == JNull) out.obj("documents").remove(table) else out.obj("documents")[table] = doc.deepCopy() }
        (overrides["roles"] as? JObj)?.forEach { (field, bits) ->
            for (f in out.obj("state").arr("role_properties")) if (f.asObj["id"] == JInt(field.toLong())) f.asObj.obj("value")["bits"] = bits
        }
        return out
    }

    private lateinit var freshSystems: Map<Int, List<ByteArray>>

    private fun seedsOf(save: JObj): SystemSeeds.SeedFrames? =
        if (save["character_profile"] != null && save["character_profile"] != JNull) SystemSeeds.SeedFrames(freshSystems, "fresh_systems_template") else null

    private val worlds = HashMap<String, WorldDirectory>()
    private val driver = JdbcSqlDriver()

    private fun worldOf(docs: JObj): WorldDirectory {
        val key = compact(docs)
        return worlds.getOrPut(key) {
            val world = WorldDirectory.initialize(temp.resolve("world-${worlds.size}.sqlite3"), driver, "vectors")
            for (name in listOf("guilds", "royal_door")) {
                val doc = docs[name] as? JObj
                if (doc != null) world.putDocument(name, doc, 1, "vectors", "vectors")
                else world.connect().use { it.execute("DELETE FROM world_documents WHERE name=?", name) }
            }
            world
        }
    }

    private fun report(name: String) {
        println("$name: $checks checks, ${failures.size} failures")
        assertTrue(failures.isEmpty()) { "$name: ${failures.size} of $checks checks differ:\n" + failures.take(25).joinToString("\n") }
    }

    // --- the saves ------------------------------------------------------------------------------------------------------

    private lateinit var saves: Map<String, JObj>
    private lateinit var catalogLucky: JObj

    private fun loadSaves(): Boolean {
        val doc = vectors("saves") ?: return false
        freshSystems = doc.obj("fresh_systems").entries.associate { (op, list) -> op.toInt() to list.asArr.map { (it as JStr).value.hexBytes() } }
        saves = doc.arr("saves").associate { it.asObj.str("id") to it.asObj }
        catalogLucky = doc.obj("catalog_lucky")
        Events.setActive(doc.obj("events"))
        return true
    }

    private fun roleChanges(owned: Owned): JArr = JArr(owned.roleChanges.entries.mapTo(ArrayList()) { (f, v) -> jarr(f, v.first, v.second) })

    private fun sectionHashes(state: JObj): JObj {
        val out = jobj("state" to sha(state))
        for ((key, value) in state) {
            if (key == "subsystems") for ((name, section) in value.asObj) out["subsystems.$name"] = JStr(sha(section))
            else out[key] = JStr(sha(value))
        }
        return out
    }

    @Test
    fun `daily routes on every recorded save`() {
        assumeTrue(available(), "OPENKNIGHTS_DEV_DIR / OPENKNIGHTS_ORIGINALS not set: local-only test skipped")
        loadSaves()
        val doc = vectors("daily_routes")!!
        blobs = doc.obj("blobs")
        val inputs = inputs()
        var count = 0
        for (v in doc.arr("vectors")) {
            val vector = v.asObj
            val save = saves.getValue(vector.str("save"))
            val label = "${save.str("label")} ${vector.str("name")}"
            val now = vector.long("now")
            installClock(vector.obj("clock"))
            val seeds = seedsOf(save)
            val owner = save.str("owner_key")
            val social = vector["social"] as? JObj
            val power = (vector["power"] as? JInt)?.value
            val questClaims = (save["quest_claims"] as? JArr)?.map { (it as JInt).value.toLong() }
            val worldCtx = DailyRoutes.WorldContext(worldOf(save.obj("world")))

            outcome("$label refresh_needed", vector["refresh_needed"], {
                DailyRoutes.refreshNeeded(currentOf(save), seeds, inputs, now, owner, now, social, questClaims)
            }) { r, a -> check("$label refresh_needed", (r as JBool).value, a) }

            outcome("$label refresh_plan", vector["refresh_plan"], {
                val cur = currentOf(save)
                val owned = Owned(cur, inputs)
                val plan = DailyRoutes.refreshPlan(owned, cur, seeds, inputs, now, owner, now, social, questClaims)
                Triple(plan, owned, cur)
            }) { r, a ->
                val (plan, owned, _) = a as Triple<*, *, *>
                val p = plan as Plan
                val o = owned as Owned
                val rec = r.asObj
                check("$label refresh_plan data", compact(rec["plan"]), compact(p.data))
                check("$label refresh_plan packets", 0, p.packets.size)
                val hashes = sectionHashes(o.state)
                for ((k, h) in rec.obj("sections")) check("$label refresh_plan state $k", h, hashes[k])
                check("$label refresh_plan item_changes", compact(rec["item_changes"]),
                    compact(JArr(o.itemChanges.entries.mapTo(ArrayList()) { (u, c) -> jarr(u, c) })))
                check("$label refresh_plan new_items", compact(rec["new_items"]),
                    compact(JArr(o.newItems.entries.mapTo(ArrayList()) { (u, e) -> jarr(u, e.first, e.second) })))
                check("$label refresh_plan role_changes", compact(rec["role_changes"]), compact(roleChanges(o)))
                check("$label refresh_plan log", compact(rec["log"]), compact(o.log))
            }

            outcome("$label login_burst_frames", vector["login_burst_frames"], {
                DailyRoutes.loginBurstFrames(currentOf(save), seeds, inputs, now, now)
            }) { r, a -> check("$label login_burst_frames", framesOf(r), framesOf(a as List<Frame>)) }

            for ((key, page) in listOf("login_query_frames" to null, "login_query_frames_page" to 7L)) {
                outcome("$label $key", vector[key], {
                    DailyRoutes.loginQueryFrames(currentOf(save), seeds, inputs, now, worldCtx, owner, page)
                }) { r, a -> check("$label $key", framesOf(r), framesOf(a as List<Frame>)) }
            }

            for ((key, recorded) in vector.obj("query_reply")) {
                val (op, hex) = key.split(":")
                outcome("$label query $key", recorded, {
                    DailyRoutes.queryReply(op.toInt(), hex.hexBytes(), currentOf(save), seeds, inputs, now, worldCtx, owner)
                }) { r, a -> check("$label query $key", framesOf(r), framesOf(a as List<Frame>)) }
            }

            outcome("$label goals", vector["goals_plan_login"], {
                val cur = currentOf(save)
                val owned = Owned(cur, inputs)
                try {
                    val plan = Goals.planLogin(owned, cur, seeds, inputs, now, power?.let { p -> { _: StateStore.Current -> p } })
                    jobj("plan" to plan.data, "state" to sha(owned.state))
                } catch (e: Goals.Unchanged) {
                    jobj("unchanged" to true, "payload" to e.payload?.toHexString())
                }
            }) { r, a ->
                val rec = r.asObj
                val act = a as JObj
                if (rec["unchanged"] == JBool(true)) check("$label goals unchanged", compact(jobj("unchanged" to true, "payload" to rec["payload"])), compact(act))
                else {
                    check("$label goals plan", compact(rec["plan"]), compact(act["plan"]))
                    check("$label goals state", rec["state"], act["state"])
                }
            }

            outcome("$label lucky", vector["lucky"], {
                val (entries, remaining, document) = Shops.luckyView(jobj("lucky" to catalogLucky), PyDocs.get(currentOf(save), "lucky_state"), now, true)
                jobj("entries" to entries, "remaining" to remaining, "document" to document,
                    "payload" to Shops.encodeLuckyInfo(entries, remaining, remaining, catalogLucky.long("flag")).toHexString())
            }) { r, a -> check("$label lucky", compact(r), compact(a as JObj)) }

            outcome("$label vip_reset", vector["vip_reset"], {
                val cur = currentOf(save)
                val needed = Claims.vipResetNeeded(cur, inputs, now)
                val owned = Owned(cur, inputs)
                val plan = Claims.planVipReset(owned, inputs, cur, now)
                jobj("needed" to needed, "plan" to plan.data, "state" to sha(owned.state))
            }) { r, a -> check("$label vip_reset", compact(r), compact(a as JObj)) }

            outcome("$label acquisition_frames", vector["acquisition_frames"], {
                val cur = currentOf(save)
                val stored = PyDocs.get(cur, "summon_state")
                val summon = if (PyDocs.truthy(stored)) stored as JObj else Summon.initialDocument(cur, now)
                val (a, b) = Summon.freeCdRemaining(summon, now)
                val frames = mutableListOf<Frame>(Summon.S_FREE_CD to Summon.freeCdPayload(a, b))
                if (Recharge.hasCharged(cur.state, PyDocs.get(cur, "recharge_ledger"))) frames.add(Recharge.S_CHARGED to byteArrayOf(0))
                val block = PyDocs.get(cur.state.obj("subsystems"), "vip")
                if (block != null) frames += Claims.buyCountFrames((block as JObj).arr("wire_values"), inputs)
                frames.add(1760 to Claims.cardStatePayload(PyDocs.get(cur, "month_cards"), Shops.dayOf(now)))
                frames to (if (stored == null) summon else null)
            }) { r, a ->
                val (frames, document) = a as Pair<*, *>
                @Suppress("UNCHECKED_CAST")
                check("$label acquisition frames", framesOf(r.asObj["frames"]!!), framesOf(frames as List<Frame>))
                check("$label summon document", compact(r.asObj["summon_document"]), compact(document as JValue?))
            }

            outcome("$label alt_team", vector["alt_team"], {
                AltTeam.infoPayload(currentOf(save), AltTeam.openRows(inputs)).toHexString()
            }) { r, a -> check("$label alt_team", (r as JStr).value, a) }
            count++
        }
        println("daily routes: $count vectors")
        report("daily_routes")
    }

    @Test
    fun `seeded draws and the synthetic rule vectors`() {
        assumeTrue(available(), "OPENKNIGHTS_DEV_DIR / OPENKNIGHTS_ORIGINALS not set: local-only test skipped")
        loadSaves()
        val inputs = inputs()

        // bounty board: 15 weighted draws seeded with the owner, the local day and the epoch second
        for (v in vectors("quests_board")!!.arr("vectors")) {
            val vec = v.asObj
            installClock(vec.obj("clock"))
            val owner = vec.str("owner_key")
            val board = Quests.newBoard(inputs, vec.long("now"), owner)
            check("board $owner ${vec.long("now")}", compact(vec["board"]), compact(board))
            check("board payload", vec.str("payload"), Quests.boardPayload(board, vec.long("later")).toHexString())
            val (rolled, changed) = Quests.boardRoll(board, inputs, vec.long("later"), owner)
            check("board roll", compact(vec["roll_same"]), compact(jarr(rolled, changed)))
        }

        // rebirth shop: per-shop weighted draws + diamond flags, the seed list, the S3904 payload
        val rebirth = vectors("rebirth_shop")!!
        for (v in rebirth.arr("roll")) {
            val vec = v.asObj
            outcome("rebirth roll ${vec.str("owner_key")} ${vec.str("day")} ${vec.long("shop")} ${vec["vip"]}", vec["entries"], {
                RebirthShop.rollShop(inputs, vec.long("shop"), vec["vip"]!!, RebirthShop.rng(vec.str("owner_key"), vec.str("day"), vec.long("shop"), "day"))
            }) { r, a -> check("rebirth roll ${vec.str("owner_key")}", compact(r), compact(a as JArr)) }
        }
        for (v in rebirth.arr("view")) {
            val vec = v.asObj
            installClock(vec.obj("clock"))
            outcome("rebirth view ${vec.str("owner_key")}", vec["result"], {
                val seed = (vec["seed"] as? JStr)?.value?.hexBytes()
                val (doc, changed) = RebirthShop.view((vec["document"] as? JObj)?.deepCopy(), inputs, vec.obj("state"), vec.long("now"), vec.str("owner_key"),
                    seed, if (seed != null) jobj("source" to "fresh_systems_template", "opcode" to 3904, "index" to 0) else null)
                jarr(doc, changed)
            }) { r, a -> check("rebirth view ${vec.str("owner_key")}", compact(r), compact(a as JArr)) }
        }
        for (v in rebirth.arr("list_payload")) {
            val vec = v.asObj
            installClock(vec.obj("clock"))
            check("rebirth list payload", vec.str("payload"), RebirthShop.listPayload(vec.obj("document"), vec.long("now")).toHexString())
        }

        // Lucky Shop: pools drawn per UTC cycle, the view and S3170
        val lucky = vectors("shops_lucky")!!
        val luckyCatalog = lucky.obj("lucky")
        for (v in lucky.arr("pools")) {
            val vec = v.asObj
            val seed = BigInteger(vec.str("seed"))
            if (vec["cycle"] is JInt) check("lucky cycle seed ${vec["cycle"]}", seed, Shops.cycleSeed(vec.long("cycle")))
            check("lucky pools ${vec.str("seed")}", compact(vec["pools"]), compact(JArr(Shops.drawLuckyPools(luckyCatalog, seed).mapTo(ArrayList()) { JInt(it) })))
        }
        for (v in lucky.arr("view")) {
            val vec = v.asObj
            val (entries, remaining, document) = Shops.luckyView(jobj("lucky" to luckyCatalog), vec["document"]?.takeIf { it != JNull }?.deepCopy(), vec.long("now"), vec.bool("pool_policy"))
            check("lucky view ${vec.long("now")}", compact(jobj("entries" to vec["entries"], "remaining" to vec["remaining"], "document" to vec["document_after"], "payload" to vec["payload"])),
                compact(jobj("entries" to entries, "remaining" to remaining, "document" to document,
                    "payload" to Shops.encodeLuckyInfo(entries, remaining, remaining, luckyCatalog.long("flag")).toHexString())))
        }

        // Royal Door: four tasks sampled per world birth and day, the day view, S2720 / S2722
        val door = vectors("daily_door")!!
        for (v in door.arr("tasks")) {
            val vec = v.asObj
            check("door tasks ${vec.str("birth")} ${vec.str("day")}", compact(vec["tasks"]),
                compact(JArr(Daily.doorTasks(vec.str("birth"), vec.str("day")).mapTo(ArrayList()) { JInt(it) })))
        }
        for (v in door.arr("view")) {
            val vec = v.asObj
            installClock(vec.obj("clock"))
            val view = Daily.doorView(vec["document"]?.takeIf { it != JNull }?.deepCopy(), vec.long("now"), vec.obj("door")["born_at_utc"])
            check("door view", compact(vec["view"]), compact(view))
            check("door payload", vec.str("payload"), Daily.doorPayload(view, vec.obj("door")).toHexString())
            check("door donated", vec.str("donated"), Daily.donatedPayload(view).toHexString())
        }

        // Event Hall login set on varied documents and clocks
        val hall = vectors("event_hall")!!
        blobs = hall.obj("blobs")
        for (v in hall.arr("vectors")) {
            val vec = v.asObj
            installClock(vec.obj("clock"))
            val save = applyOverrides(saves.getValue(vec.str("save")), vec["overrides"] as? JObj)
            outcome("event hall ${vec.str("save")} ${vec.long("now")}", vec["frames"], {
                DailyRoutes.loginBurstFrames(currentOf(save), seedsOf(save), inputs, vec.long("now"), vec.long("served_time"))
            }) { r, a -> check("event hall ${vec.str("save")} ${vec.long("now")}", framesOf(r), framesOf(a as List<Frame>)) }
        }

        // Castle refresh: recruits whose terms end, alchemy attempts regenerating
        for (v in vectors("castle")!!.arr("vectors")) {
            val vec = v.asObj
            installClock(vec.obj("clock"))
            val save = vec.obj("save")
            outcome("castle ${save.str("label")}", vec["result"], {
                val cur = currentOf(save)
                val document = DailyRoutes.castleRefresh(cur.state, cur, inputs, vec.long("now"))
                jobj("document" to document, "sections" to sectionHashes(cur.state), "alchemy" to cur.state.obj("subsystems").obj("alchemy")["wire_values"],
                    "messages" to cur.state["servant_messages"])
            }) { r, a -> check("castle ${save.str("label")}", compact(r), compact(a as JObj)) }
        }

        // titles
        val prestige = vectors("prestige")!!
        blobs = prestige.obj("blobs")
        for (v in prestige.arr("promote")) {
            val vec = v.asObj
            val save = applyOverrides(saves.getValue(vec.str("save")), vec["overrides"] as? JObj)
            outcome("promote ${vec.str("save")}", vec["result"], {
                val owned = Owned(currentOf(save), inputs)
                val frames = Prestige.promote(owned, inputs)
                frames to roleChanges(owned)
            }) { r, a ->
                val (frames, roles) = a as Pair<*, *>
                @Suppress("UNCHECKED_CAST")
                check("promote frames ${vec.str("save")}", framesOf(r.asObj["frames"]!!), framesOf(frames as List<Frame>))
                check("promote roles ${vec.str("save")}", compact(r.asObj["roles"]), compact(roles as JArr))
            }
        }
        for (g in prestige.arr("title_for")) {
            val row = g.asArr
            check("title_for $row", (row[2] as JInt).value.toLong(), Prestige.titleFor(inputs, (row[0] as JInt).value, (row[1] as JInt).value))
        }

        // event definitions and the rolling roulette window
        val events = vectors("events")!!
        for (v in events.arr("validate")) {
            val vec = v.asObj
            outcome("events validate", vec["result"], { Events.validate(vec.obj("definitions").deepCopy()); true }) { r, a -> check("events validate", (r as JBool).value, a) }
        }
        for (v in events.arr("roulette")) {
            val vec = v.asObj
            val defs = JObj(LinkedHashMap(Events.ACTIVE.map)).also { it["roulette_window"] = vec["rolling"]!! }
            val section = vec.obj("section").deepCopy()
            val rolled = Events.rollRouletteWindow(section, defs, vec.long("served_time"))
            check("roulette rolled", (vec["rolled"] as JBool).value, rolled)
            check("roulette after", compact(vec["after"]), compact(section))
            val state = jobj("subsystems" to jobj("game_activities" to vec.obj("section").deepCopy()))
            val changed = Events.reconcile(state, defs, vec.long("served_time"))
            check("reconcile changed", (vec["reconcile"] as JBool).value, changed)
            check("reconciled", compact(vec["reconciled"]), compact(state.obj("subsystems")["game_activities"]))
        }

        // the ladder branch of the events refresh (defined activities: reconcile, login days, the wording rows)
        val ladder = events.obj("ladder")
        Events.setActive(ladder.obj("definitions").deepCopy())
        for (v in ladder.arr("vectors")) {
            val vec = v.asObj
            installClock(vec.obj("clock"))
            val save = saves.getValue(vec.str("save"))
            val now = vec.long("now")
            outcome("ladder ${vec.str("save")} $now", vec["result"], {
                val cur = currentOf(save)
                val first = DailyRoutes.eventsRefresh(cur.state, cur, now, now)
                val docs = LinkedHashMap(cur.documents).also { it["event_state"] = first }
                val next = StateStore.Current(cur.revision, "", "", cur.state, ByteArray(0), 0, null, cur.inventoryItems, emptyList(),
                    cur.acquiredItems, emptyList(), null, null, null, cur.characterProfile, null, docs)
                val later = now + 86400L * (1 + (ladder.arr("vectors").indexOf(v) % 2))
                val second = DailyRoutes.eventsRefresh(next.state, next, later, later)
                val third = DailyRoutes.eventsRefresh(next.state, next, later + 5, later + 5)
                jobj("first" to first, "second" to second, "third" to third, "activities" to cur.state.obj("subsystems")["game_activities"])
            }) { r, a -> check("ladder ${vec.str("save")} $now", compact(r), compact(a as JObj)) }
        }
        Events.setActive(vectors("saves")!!.obj("events"))

        // goals on varied documents, levels and Power
        for (v in vectors("goals")!!.arr("vectors")) {
            val vec = v.asObj
            installClock(vec.obj("clock"))
            val save = applyOverrides(saves.getValue(vec.str("save")), vec["overrides"] as? JObj)
            val power = (vec["power"] as? JStr)?.value?.let { BigInteger(it) }
            outcome("goals ${vec.str("save")} ${vec.long("now")}", vec["result"], {
                val cur = currentOf(save)
                val owned = Owned(cur, inputs)
                try {
                    jobj("plan" to Goals.planLogin(owned, cur, seedsOf(save), inputs, vec.long("now"), power?.let { p -> { _: StateStore.Current -> p } }).data)
                } catch (e: Goals.Unchanged) {
                    jobj("unchanged" to true, "payload" to e.payload?.toHexString())
                }
            }) { r, a -> check("goals ${vec.str("save")} ${vec.long("now")}", compact(r), compact(a as JObj)) }
        }
        report("synthetic")
    }
}
