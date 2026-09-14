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
 * Local only (OPENKNIGHTS_DEV_DIR with `vectors-g6/castle_*.json` from the maintainer's private exporter,
 * OPENKNIGHTS_ORIGINALS with the player's APK): the castle slice replayed on the reference's own outputs — the decoders
 * and formulas, the Sacrifice / Reforge returns on described cards, every Castle planner and every card reset planner on
 * the recorded saves (as they are and primed). Every frame, plan, document and state must be identical.
 */
class G6CastleVectorsTest {
    private val dev: Path? = System.getenv("OPENKNIGHTS_DEV_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
    private val originals: Path? = System.getenv("OPENKNIGHTS_ORIGINALS")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
    private val failures = ArrayList<String>()
    private var checks = 0

    @AfterEach
    fun reset() {
        DeviceClock.active = null
        Events.setActive(null)
    }

    private fun file(name: String): Path? = dev?.resolve("vectors-g6")?.resolve("castle_$name.json")

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

    // --- rules ----------------------------------------------------------------------------------------------------------

    @Test
    fun `decoders, costs, multipliers, recruit Gold, reset prices and property values`() {
        assumeTrue(available("rules"), "OPENKNIGHTS_DEV_DIR / OPENKNIGHTS_ORIGINALS not set: local-only test skipped")
        val doc = vectors("rules")
        val inputs = inputs()
        for ((i, v) in doc.arr("decode").withIndex()) {
            val vector = v.asObj
            val payload = vector.str("payload").hexBytes()
            val opcode = vector.long("opcode").toInt()
            replay("decode $i ${vector.str("fn")} C$opcode ${vector.str("payload")}", vector["result"], {
                when (vector.str("fn")) {
                    "u8" -> JInt(Castle.decodeU8(payload, opcode))
                    "u32" -> JInt(Castle.decodeU32(payload, opcode))
                    else -> CardReset.decodeReset(payload, opcode)
                }
            }) { rec, r -> check("decode $i", compact(rec), compact(r)) }
        }
        for ((i, v) in doc.arr("costs").withIndex()) {
            val vector = v.asObj
            val actual: JValue = when (vector.str("fn")) {
                "building" -> JInt(Castle.buildingCost(vector.long("factor"), vector.long("level")))
                "tech" -> JInt(Castle.techCost(vector.long("factor"), vector.long("level")))
                else -> Castle.guildTechCosts(inputs.guildTech(vector.long("tech"))!!, vector.long("level")).let { jarr(it.first, it.second) }
            }
            check("costs $i ${compact(vector)}", compact(vector["result"]), compact(actual))
        }
        for ((i, v) in doc.arr("guild_gold_bonus").withIndex()) {
            val vector = v.asObj
            replay("guild_gold_bonus $i", vector["result"], { Castle.guildGoldBonus(vector["doc"]?.takeIf { it != JNull } as JObj?) }) { rec, r ->
                check("guild_gold_bonus $i", compact(rec), compact(JInt(r)))
            }
        }
        for ((i, v) in doc.arr("roll_multiplier").withIndex()) {
            val vector = v.asObj
            val n = vector.long("n")
            val rng = io.github.okexodus.openknights.exact.PyRandom.seeded(seed("collect", "char_x", "2026-09-14", n.toString(), vector.long("seed").toString()))
            val row = inputs.collectRow(n)!!
            check("roll_multiplier $i", compact(vector["draws"]), compact(JArr(MutableList(5) { JInt(Castle.rollMultiplier(row, rng)) })))
        }
        for ((i, v) in doc.arr("servant_outcome").withIndex()) {
            val vector = v.asObj
            replay("servant_outcome $i", vector["result"], {
                Castle.servantOutcome(BigInteger.valueOf(vector.long("player")), vector["recruit"]!!, vector["lab"]!!, vector.long("remaining"), inputs)
            }) { rec, r -> check("servant_outcome $i ${compact(vector)}", compact(rec), compact(JInt(r))) }
        }
        for ((i, v) in doc.arr("reset_diamonds").withIndex()) {
            val vector = v.asObj
            replay("reset_diamonds $i", vector["result"], {
                CardReset.resetDiamonds(inputs, vector.str("kind"), vector.long("star"), vector.long("level"), vector.long("grade"), vector.long("mode"))
            }) { rec, r -> check("reset_diamonds $i ${compact(vector)}", compact(rec), compact(JInt(r))) }
        }
        for ((i, v) in doc.arr("property_value").withIndex()) {
            val vector = v.asObj
            replay("property_value $i", vector["result"], { inputs.cardPropertyValue(vector.str("kind"), vector.arr("record")) }) { rec, r ->
                check("property_value $i ${compact(vector["record"])}", compact(rec), compact(JInt(r)))
            }
        }
        report("rules")
    }

    /** The seed of the reference's `_rng(*parts)`. */
    private fun seed(vararg parts: String): BigInteger {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(parts.joinToString("|").toByteArray(Charsets.UTF_8))
        return BigInteger(1, digest.copyOfRange(0, 8).reversedArray())
    }

    // --- returns ----------------------------------------------------------------------------------------------------------

    private fun described(card: JObj): ResetReturns.Described {
        val kind = card.str("kind")
        if (kind == "hero") {
            return ResetReturns.Described(kind, card.long("template"), card.long("level"), card.long("exp"), awaken = card.long("awaken"),
                godSkills = card.arr("god_skills").toList(), dev = card.arr("dev").map { it.long }, stats = card.arr("stats").map { it.long },
                rebornLevel = card.long("reborn_level"), rebornGrade = card.long("reborn_grade"))
        }
        return ResetReturns.Described(kind, card.long("template"), card.long("level"), card.long("exp"), grade = card.long("grade"),
            superFlag = card.long("super"), enchant = card.long("enchant"), propertyValue = (card["property_value"] as? JInt)?.value?.toLong())
    }

    @Test
    fun `Sacrifice and Reforge returns of described cards`() {
        assumeTrue(available("returns"), "OPENKNIGHTS_DEV_DIR / OPENKNIGHTS_ORIGINALS not set: local-only test skipped")
        val doc = vectors("returns")
        val tables = ResetReturns.tablesFor(inputs())
        for ((i, v) in doc.arr("vectors").withIndex()) {
            val vector = v.asObj
            val card = described(vector.obj("card"))
            val level = (vector["level"] as? JInt)?.value
            replay("returns $i", vector["result"], {
                if (vector.str("mode") == "sacrifice") ResetReturns.sacrifice(card, tables, level) else ResetReturns.reforge(card, tables, level)
            }) { rec, r -> check("returns $i ${vector.str("mode")} ${compact(vector["card"])} $level", compact(rec), compact(r)) }
        }
        report("returns")
    }

    // --- planners ---------------------------------------------------------------------------------------------------------

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
        (ov["documents"] as? JObj)?.forEach { (table, doc) -> if (doc == JNull) out.obj("documents").remove(table) else out.obj("documents")[table] = doc.deepCopy() }
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

    private fun runPlanners(name: String) {
        loadSaves()
        val doc = vectors(name)
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
            val label = "$name $i ${source.str("id")} ${vector.str("ov").take(6)} C$opcode ${vector.str("payload")}"
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
        println("$name: $ran vectors")
        report(name)
    }

    @Test
    fun `every Castle planner on the recorded saves`() {
        assumeTrue(available("saves", "plans"), "OPENKNIGHTS_DEV_DIR / OPENKNIGHTS_ORIGINALS not set: local-only test skipped")
        runPlanners("plans")
    }

    @Test
    fun `every Sacrifice and Reforge planner on primed saves`() {
        assumeTrue(available("saves", "resets"), "OPENKNIGHTS_DEV_DIR / OPENKNIGHTS_ORIGINALS not set: local-only test skipped")
        runPlanners("resets")
    }
}
