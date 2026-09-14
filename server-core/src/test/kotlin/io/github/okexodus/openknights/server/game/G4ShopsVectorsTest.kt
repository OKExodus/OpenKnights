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
import io.github.okexodus.openknights.exact.toHexString
import io.github.okexodus.openknights.server.DeviceClock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Local only (OPENKNIGHTS_DEV_DIR with `vectors-g4/shops_*.json` from the maintainer's private exporter,
 * OPENKNIGHTS_ORIGINALS with the player's APK): the shops slice replayed on the reference's own outputs — the event
 * ladder rules, every planner of the shops / warehouse / claims / VIP quest opcodes on every distinct recorded save
 * (as it is and primed), the read-only replies, the Fate Store ranking and the Rename Card.
 */
class G4ShopsVectorsTest {
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
        val file = dev?.resolve("vectors-g4")?.resolve("shops_$name.json") ?: return null
        return if (Files.isRegularFile(file)) Json.loads(Files.readString(file)).asObj else null
    }

    private fun available(name: String): Boolean =
        dev != null && originals != null && Files.isRegularFile(dev.resolve("vectors-g4").resolve("shops_$name.json"))

    private fun check(label: String, expected: Any?, actual: Any?) {
        checks++
        if (expected != actual) failures.add("$label\n  expected: ${expected.toString().take(900)}\n  actual:   ${actual.toString().take(900)}")
    }

    private fun compact(v: JValue?): String = Json.dumps(v ?: JNull, itemSeparator = ",", keySeparator = ":")

    private lateinit var blobs: JObj

    private fun framesOf(recorded: JValue): List<String> = recorded.asArr.map { f -> "${f.asArr[0]}:${blobs.str((f.asArr[1] as JStr).value)}" }

    private fun framesOf(frames: List<Frame>): List<String> = frames.map { "${it.first}:${it.second.toHexString()}" }

    private fun isError(recorded: JValue?): Boolean =
        (recorded as? JObj)?.let { it.containsKey("error") && it.containsKey("message") && it.containsKey("value_error") } == true

    private fun report(name: String) {
        println("$name: $checks checks, ${failures.size} failures")
        assertTrue(failures.isEmpty()) { "$name: ${failures.size} of $checks checks differ:\n" + failures.take(25).joinToString("\n") }
    }

    /** Run [block]; compare an error with the recorded error, else hand the result to [compare]. */
    private fun <T> replay(label: String, recorded: JValue?, block: () -> T, compare: (JObj, T) -> Unit) {
        val result = try {
            block()
        } catch (e: NotPorted) {
            throw e
        } catch (e: Exception) {
            if (!isError(recorded)) failures.add("$label: unexpected ${e::class.simpleName}: ${e.message}\n${e.stackTrace.take(8).joinToString("\n")}")
            else {
                val rec = recorded as JObj
                check("$label error kind", rec["value_error"] == io.github.okexodus.openknights.exact.JBool(true), e is IllegalArgumentException)
                // the text of a UTF-8 decode error only reaches the log
                if (e is IllegalArgumentException && rec.str("error") != "UnicodeDecodeError") check("$label error", rec.str("message"), e.message)
                val code = rec["code"]
                if (code != null && code != JNull) check("$label code", (code as JInt).value.toInt(), (e as? Acquisition.Rejected)?.code)
            }
            return
        }
        if (isError(recorded)) {
            failures.add("$label: expected ${(recorded as JObj).str("error")} (${recorded.str("message")})")
            return
        }
        compare(recorded as JObj, result)
    }

    @Test
    fun `event ladder rules`() {
        assumeTrue(available("activity"), "OPENKNIGHTS_DEV_DIR / OPENKNIGHTS_ORIGINALS not set: local-only test skipped")
        val doc = vectors("activity")!!
        blobs = doc.obj("blobs")
        val lists = doc.arr("lists")
        for ((i, v) in doc.arr("vectors").withIndex()) {
            val vector = v.asObj
            val entries = lists[(vector["list"] as JInt).value.toInt()].deepCopy() as JArr
            val state = jobj("subsystems" to jobj("game_activities" to jobj("first_list" to jobj("count" to entries.size, "entries" to entries))))
            val served = (vector["served_time"] as? JInt)?.value?.toLong()
            val op = vector.arr("op")
            val label = "ladder $i ${compact(op)} @$served"
            replay(label, vector["result"], {
                when ((op[0] as JStr).value) {
                    "advance" -> ActivityProgress.advance(state, (op[1] as JStr).value, (op[2] as JInt).value.longValueExact(), served)
                    "count_transaction" -> ActivityProgress.countTransaction(state, (op[1] as JInt).value.longValueExact(), served)
                    else -> ActivityProgress.setVipLevel(state, (op[1] as JInt).value, served)
                }
            }) { rec, frames ->
                check("$label frames", framesOf(rec["frames"]!!), framesOf(frames))
                check("$label after", compact(rec["after"]), compact(state.obj("subsystems").obj("game_activities")["first_list"]))
            }
        }
        for ((i, v) in doc.arr("claims").withIndex()) {
            val vector = v.asObj
            val entry = (lists[(vector["list"] as JInt).value.toInt()] as JArr)[(vector["index"] as JInt).value.toInt()].deepCopy() as JObj
            val label = "claim_row $i"
            replay(label, vector["result"], { ActivityProgress.claimRow(entry) }) { rec, claimed ->
                check("$label claimed", compact(rec["claimed"]), compact(claimed?.let { jarr(it.first, it.second) }))
                check("$label after", compact(rec["after"]), compact(entry))
            }
        }
        for ((i, v) in doc.arr("events_claims").withIndex()) {
            val vector = v.asObj
            val entry = vector.obj("entry").deepCopy()
            val label = "events.claim $i"
            replay(label, vector["result"], { Events.claim(vector.obj("activity"), entry) }) { rec, claimed ->
                check("$label claimed", compact(rec["claimed"]), compact(claimed?.let { jarr(it.first, it.second) }))
                check("$label after", compact(rec["after"]), compact(entry))
            }
        }
        report("event ladder rules")
    }

    private fun sha(v: JValue?): String = io.github.okexodus.openknights.exact.sha256Hex(compact(v).toByteArray(Charsets.UTF_8)).take(24)

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

    private fun currentOf(save: JObj): io.github.okexodus.openknights.server.store.StateStore.Current {
        val docs = LinkedHashMap<String, JValue?>()
        for ((k, v) in save.obj("documents")) docs[k] = v.deepCopy()
        return io.github.okexodus.openknights.server.store.StateStore.Current((save["revision"] as JInt).value.toLong(), "", "",
            save.obj("state").deepCopy(), ByteArray(0), 0, null, save.arr("inventory_items").deepCopy(), emptyList(),
            save.arr("acquired_items").deepCopy(), emptyList(), null, null, null, (save["character_profile"] as? JObj)?.deepCopy(), null, docs)
    }

    private fun hashes(state: JObj): JObj {
        val sub = state.obj("subsystems")
        return jobj("state" to sha(state), "role_properties" to sha(state["role_properties"]), "items" to sha(state["items"]),
            "vip" to sha(sub["vip"]), "game_activities" to sha(sub["game_activities"]), "achievements" to sha(sub["achievements"]))
    }

    private fun ownedRecord(owned: Owned): JObj = jobj(
        "item_changes" to JArr(owned.itemChanges.entries.mapTo(ArrayList()) { (u, c) -> jarr(u, c) }),
        "new_items" to JArr(owned.newItems.entries.mapTo(ArrayList()) { (u, e) -> jarr(u, e.first, e.second) }),
        "role_changes" to JArr(owned.roleChanges.entries.mapTo(ArrayList()) { (f, c) -> jarr(f, c.first, c.second) }),
        "log" to owned.log,
        "granted" to JArr(owned.granted.entries.mapTo(ArrayList()) { (t, n) -> jarr(t, n) }))

    @Test
    fun `every planner of the slice on every recorded save`() {
        assumeTrue(available("plans") && available("plan_saves"), "OPENKNIGHTS_DEV_DIR / OPENKNIGHTS_ORIGINALS not set: local-only test skipped")
        val saves = vectors("plan_saves")!!.arr("saves").associate { it.asObj.str("id") to it.asObj }
        val doc = vectors("plans")!!
        blobs = doc.obj("blobs")
        val inputs = inputs()
        val catalog = doc.obj("catalog")
        val rngPolicy = doc.obj("rng_policy")
        val releaseEvents = doc.obj("events")
        val ladderEvents = jobj("profile" to Events.PROFILE, "activities" to doc.arr("event_defs"), "exchanges" to JArr())
        val fallbackProfile = saves.values.firstNotNullOf { it["character_profile"] as? JObj }
        var ran = 0
        for ((i, v) in doc.arr("vectors").withIndex()) {
            val vector = v.asObj
            val save = saves.getValue(vector.str("save"))
            val opcode = vector.long("opcode").toInt()
            val payload = vector.str("payload").hexBytes()
            val now = vector.long("now")
            installClock(vector.obj("clock"))
            Events.setActive(if (vector.str("events") == "ladder") ladderEvents.deepCopy() else releaseEvents)
            val rng = if ((vector["rng"] as io.github.okexodus.openknights.exact.JBool).value) rngPolicy else null
            val label = "plan $i ${save.str("id")} C$opcode ${vector.str("payload")}"
            val cur = currentOf(save)
            val policy = FreshProfile.DeploymentPolicy(cur.characterProfile ?: fallbackProfile)
            val recorded = vector["result"]
            if (AcquisitionRoutes.isReadOnly(opcode, payload)) {
                replay(label, recorded, { AcquisitionRoutes.readOnlyReply(opcode, payload, cur, inputs, catalog, rng, now) }) { rec, (frames, fields) ->
                    check("$label read-only", true, (rec["read_only"] as? io.github.okexodus.openknights.exact.JBool)?.value)
                    check("$label frames", framesOf(rec["frames"]!!), framesOf(frames))
                    check("$label fields", compact(rec["fields"]), compact(fields))
                }
            } else {
                var owned: Owned? = null
                replay(label, recorded, {
                    val routed = AcquisitionRoutes.plannerFor(opcode, payload, inputs, catalog, rng, policy, now) { now }
                    owned = Owned(cur, inputs)
                    routed to routed.planner(owned!!, cur)
                }) { rec, (routed, plan) ->
                    check("$label action", rec.str("action"), routed.action)
                    check("$label request", compact(rec["request"]), compact(routed.request))
                    check("$label plan", compact(rec["plan"]), compact(plan.data))
                    check("$label frames", framesOf(rec["frames"]!!), framesOf(plan.packets))
                    check("$label owned", compact(rec["owned"]), compact(ownedRecord(owned!!)))
                    check("$label hashes", compact(rec["hashes"]), compact(hashes(owned!!.state)))
                }
            }
            ran++
        }
        println("planners: $ran vectors")
        report("planners")
    }

    @Test
    fun `Fate Store ranking and Rename Card`() {
        assumeTrue(available("rank") && available("plan_saves"), "OPENKNIGHTS_DEV_DIR / OPENKNIGHTS_ORIGINALS not set: local-only test skipped")
        val saves = vectors("plan_saves")!!.arr("saves").associate { it.asObj.str("id") to it.asObj }
        val doc = vectors("rank")!!
        blobs = doc.obj("blobs")
        val inputs = inputs()
        for ((i, v) in doc.arr("record").withIndex()) {
            val vector = v.asObj
            replay("record $i", vector["result"], {
                RouletteRank.record(vector.obj("document").deepCopy(), vector.long("role"), vector["today_score"]!!, vector["total_score"]!!,
                    vector.str("today"), vector.str("yesterday"))
            }) { rec, out -> check("record $i", compact(rec), compact(out)) }
        }
        for ((i, v) in doc.arr("listing").withIndex()) {
            val vector = v.asObj
            val tab = vector.long("tab").toInt()
            val rows = RouletteRank.listing(vector.obj("document"), tab, vector.str("today"), vector.str("yesterday"), vector.long("threshold"))
            check("listing $i rows", compact(vector["rows"]), compact(JArr(rows.mapTo(ArrayList()) { jarr(it.first, it.second) })))
            val role = vector.long("role")
            val flag = RouletteRank.ownFlag(rows, role, vector["claimed"]?.takeIf { it != JNull })
            check("listing $i flag", compact(vector["flag"]), compact(jarr(flag.first, flag.second)))
            val names = LinkedHashMap<Long, ByteArray>()
            for ((k, n) in vector.obj("names")) names[k.toLong()] = (n as JStr).value.hexBytes()
            check("listing $i payload", vector.str("payload"), RouletteRank.rankPayload(tab, rows, names, mapOf(role to flag.first)).toHexString())
        }
        for ((i, v) in doc.arr("claim").withIndex()) {
            val vector = v.asObj
            val cur = currentOf(saves.getValue(vector.str("save")))
            var owned: Owned? = null
            replay("claim $i", vector["result"], {
                owned = Owned(cur, inputs)
                RouletteRank.planClaim(owned!!, vector["claims"]?.takeIf { it != JNull }, vector.long("rank").toInt(), vector.str("yesterday"))
            }) { rec, plan ->
                check("claim $i plan", compact(rec["plan"]), compact(plan.data))
                check("claim $i frames", framesOf(rec["frames"]!!), framesOf(plan.packets))
                check("claim $i owned", compact(rec["owned"]), compact(ownedRecord(owned!!).also { it.remove("granted") }))
                check("claim $i hashes", compact(rec["hashes"]), compact(hashes(owned!!.state)))
            }
        }
        for ((i, v) in doc.arr("rename").withIndex()) {
            val vector = v.asObj
            var save = saves.getValue(vector.str("save"))
            if ((vector["card"] as io.github.okexodus.openknights.exact.JBool).value) save = withItem(save, 10719, 1L + i / 4 % 2)
            val cur = currentOf(save)
            var owned: Owned? = null
            replay("rename $i", vector["result"], {
                owned = Owned(cur, inputs)
                Rename.planRename(vector.str("name"), owned!!)
            }) { rec, plan ->
                check("rename $i plan", compact(rec["plan"]), compact(plan.data))
                check("rename $i frames", framesOf(rec["frames"]!!), framesOf(plan.packets))
                check("rename $i owned", compact(rec["owned"]), compact(ownedRecord(owned!!).also { it.remove("granted") }))
                check("rename $i hashes", compact(rec["hashes"]), compact(hashes(owned!!.state)))
            }
        }
        for ((i, v) in doc.arr("owns").withIndex()) {
            val vector = v.asObj
            val save = saves.getValue(vector.str("save"))
            check("owns $i", (vector["owns"] as io.github.okexodus.openknights.exact.JBool).value, Rename.ownsCard(currentOf(save)))
            check("owns_with $i", (vector["owns_with"] as io.github.okexodus.openknights.exact.JBool).value, Rename.ownsCard(currentOf(withItem(save, 10719, 1))))
            check("owns_zero $i", (vector["owns_zero"] as io.github.okexodus.openknights.exact.JBool).value, Rename.ownsCard(currentOf(withItem(save, 10719, 0))))
        }
        for ((i, v) in doc.arr("decode").withIndex()) {
            val vector = v.asObj
            replay("decode $i", vector["result"], { Rename.decodeRequest(vector.str("payload").hexBytes()) }) { rec, raw ->
                check("decode $i raw", rec.str("raw"), raw)
                val name = rec["name"]!!
                val actual = try { JStr(FreshProfile.normalizeName(raw)) } catch (e: IllegalArgumentException) { JStr("error: ${e.message}") }
                check("decode $i name", if (isError(name)) JStr("error: ${(name as JObj).str("message")}") else name, actual)
            }
        }
        report("rank and rename")
    }

    /** `add_items(save, [(template, count)])` of the exporter: one new stack with the next free uid. */
    private fun withItem(save: JObj, template: Long, count: Long): JObj {
        val out = save.deepCopy()
        val items = out.obj("state").arr("items")
        val taken = (items + out.arr("inventory_items") + out.arr("acquired_items")).map { (it.asObj.arr("wire_values")[0] as JInt).value.toLong() }
        items.add(jobj("wire_values" to jarr((taken.maxOrNull() ?: 0L) + 1, template, count), "timed_flag" to 0))
        return out
    }

    private fun inputs() = DailyInputs(io.github.okexodus.openknights.gamedata.GameTables(
        io.github.okexodus.openknights.gamedata.ApkTables(originals!!.resolve("com.enjoygame.hero2d.apk"))))

    @Test
    fun `claims rules`() {
        assumeTrue(available("claims_rules"), "OPENKNIGHTS_DEV_DIR / OPENKNIGHTS_ORIGINALS not set: local-only test skipped")
        val doc = vectors("claims_rules")!!
        blobs = doc.obj("blobs")
        val inputs = inputs()
        for ((i, v) in doc.arr("raise_maxima").withIndex()) {
            val vector = v.asObj
            val block = vector.arr("block").deepCopy()
            replay("raise_maxima $i", vector["result"], { Claims.raiseMaxima(block, vector.long("level"), inputs) }) { rec, _ ->
                check("raise_maxima $i", compact(rec["block"]), compact(block))
            }
        }
        for ((i, v) in doc.arr("buy_counts").withIndex()) {
            val vector = v.asObj
            replay("buy_counts $i", vector["result"], { Claims.buyCountFrames(vector.arr("block"), inputs) to Claims.buyCountFrames(vector.arr("block")) }) { rec, (a, b) ->
                check("buy_counts $i", framesOf(rec["frames"]!!), framesOf(a))
                check("buy_counts $i plain", framesOf(rec["plain"]!!), framesOf(b))
            }
        }
        for ((i, v) in doc.arr("cards").withIndex()) {
            val vector = v.asObj
            val document = vector["document"]?.takeIf { it != JNull }
            val today = vector.str("today")
            val label = "card $i ${compact(document)} ${vector["card"]} $today"
            replay("$label activate", vector["activate"], { Claims.activateCard(document?.deepCopy(), vector.long("card"), today) }) { rec, out ->
                check("$label activate", compact(rec), compact(out))
            }
            replay("$label view", vector["view"], { Claims.cardView(document, today) }) { rec, out ->
                check("$label view", compact(rec), compact(JObj().also { o -> out.forEach { (k, x) -> o[k] = x } }))
            }
            val payload = vector["payload"]
            val actual = try { Claims.cardStatePayload(document, today).toHexString() } catch (e: NotPorted) { throw e } catch (e: Exception) { null }
            if (isError(payload)) check("$label payload error", true, actual == null) else check("$label payload", (payload as JStr).value, actual)
        }
        report("claims rules")
    }
}
