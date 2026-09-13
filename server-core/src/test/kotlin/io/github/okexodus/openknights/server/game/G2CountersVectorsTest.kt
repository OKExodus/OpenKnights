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
 * Local only (OPENKNIGHTS_DEV_DIR with `vectors-g2/` from the maintainer's private exporter, OPENKNIGHTS_ORIGINALS with
 * the player's APK): the follow-up `daily_counters` revision replayed on the reference's own outputs — every distinct
 * character save of the recorded roots, as it is and primed so every counter can move, for every counted action — plus
 * the new-medal mail delivery, the Royal Door EXP rule and the event extraction on odd plans.
 */
class G2CountersVectorsTest {
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
        val file = dev?.resolve("vectors-g2")?.resolve("$name.json") ?: return null
        return if (Files.isRegularFile(file)) Json.loads(Files.readString(file)).asObj else null
    }

    private fun available(): Boolean = dev != null && originals != null && Files.isRegularFile(dev.resolve("vectors-g2").resolve("counters.json"))

    private fun check(label: String, expected: Any?, actual: Any?) {
        checks++
        if (expected != actual) failures.add("$label\n  expected: ${expected.toString().take(900)}\n  actual:   ${actual.toString().take(900)}")
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

    private fun frameList(recorded: JValue): List<Frame> = recorded.asArr.map { f -> (f.asArr[0] as JInt).value.toInt() to blobs.str((f.asArr[1] as JStr).value).hexBytes() }

    private fun isError(recorded: JValue?): Boolean = (recorded as? JObj)?.let { it.containsKey("error") && it.containsKey("message") && it.containsKey("value_error") } == true

    private fun currentOf(save: JObj): StateStore.Current {
        val docs = LinkedHashMap<String, JValue?>()
        for ((k, v) in save.obj("documents")) docs[k] = v.deepCopy()
        return StateStore.Current((save["revision"] as JInt).value.toLong(), "", "", save.obj("state").deepCopy(), ByteArray(0), 0, null,
            save.arr("inventory_items").deepCopy(), emptyList(), save.arr("acquired_items").deepCopy(), emptyList(), null, null, null,
            (save["character_profile"] as? JObj)?.deepCopy(), null, docs)
    }

    private fun applyOverrides(save: JObj, overrides: JObj): JObj {
        val out = save.deepCopy()
        (overrides["documents"] as? JObj)?.forEach { (table, doc) -> if (doc == JNull) out.obj("documents").remove(table) else out.obj("documents")[table] = doc.deepCopy() }
        (overrides["roles"] as? JObj)?.forEach { (field, bits) ->
            for (f in out.obj("state").arr("role_properties")) if (f.asObj["id"] == JInt(field.toLong())) f.asObj.obj("value")["bits"] = bits
        }
        (overrides["achievements"] as? JArr)?.let { out.obj("state").obj("subsystems").obj("achievements")["entries"] = it.deepCopy() }
        return out
    }

    private fun eventsOf(recorded: JArr): List<DailyHooks.Event> = recorded.map { e ->
        val a = e.asArr
        DailyHooks.Event((a[0] as JStr).value, (a[1] as JInt).value.toLong(), (a[2] as? JInt)?.value?.toLong())
    }

    private fun report(name: String) {
        println("$name: $checks checks, ${failures.size} failures")
        assertTrue(failures.isEmpty()) { "$name: ${failures.size} of $checks checks differ:\n" + failures.take(25).joinToString("\n") }
    }

    private fun sectionHashes(state: JObj): JObj {
        val out = jobj("state" to sha(state))
        for ((key, value) in state) {
            if (key == "subsystems") for ((name, section) in value.asObj) out["subsystems.$name"] = JStr(sha(section))
            else out[key] = JStr(sha(value))
        }
        return out
    }

    @Test
    fun `daily counters on every recorded save`() {
        assumeTrue(available(), "OPENKNIGHTS_DEV_DIR / OPENKNIGHTS_ORIGINALS not set: local-only test skipped")
        val savesDoc = vectors("counter_saves")!!
        val saves = savesDoc.arr("saves").associate { it.asObj.str("id") to it.asObj }
        Events.setActive(savesDoc.obj("events"))
        val doc = vectors("counters")!!
        blobs = doc.obj("blobs")
        val inputs = DailyInputs(GameTables(ApkTables(originals!!.resolve("com.enjoygame.hero2d.apk"))))
        val power = (doc["power"] as JInt).value
        var counted = 0
        for (v in doc.arr("vectors")) {
            val vector = v.asObj
            val save = applyOverrides(saves.getValue(vector.str("save")), vector.obj("overrides"))
            val label = "${save.str("label")} ${vector.str("variant")} ${vector.str("name")}"
            installClock(vector.obj("clock"))
            val now = vector.long("now")
            val action = vector.str("action")
            val plan = vector.obj("plan")
            val packets = frameList(vector.arr("packets"))
            val events = try {
                DailyHooks.eventsFor(action, plan, packets) + DailyHooks.achievementEvents(action, packets)
            } catch (e: NotPorted) { throw e } catch (e: Exception) {
                check("$label events error", true, isError(vector["events"])); continue
            }
            check("$label events", compact(vector["events"]), compact(JArr(events.mapTo(ArrayList()) { it.json() })))
            val rec = vector["result"] as? JObj ?: continue
            val cur = currentOf(save)
            val owned = Owned(cur, inputs)
            val result = try {
                DailyHooks.planCounters(owned, cur, events, inputs, now, vector["door"] as? JObj, now) { BigInteger.valueOf(0) + power }
            } catch (e: NotPorted) { throw e } catch (e: Exception) {
                if (!isError(rec)) failures.add("$label: unexpected ${e::class.simpleName}: ${e.message}\n${e.stackTrace.take(8).joinToString("\n")}")
                else check("$label error", rec.str("message"), e.message)
                continue
            }
            if (isError(rec)) { failures.add("$label: expected ${rec.str("error")} (${rec.str("message")})"); continue }
            check("$label keys", compact(rec["keys"]), compact(JArr(result.data.keys.mapTo(ArrayList()) { JStr(it) })))
            for ((k, h) in rec.obj("shas")) check("$label $k", h, JStr(sha(result.data[k])))
            rec["data"]?.let { check("$label data", compact(it), compact(result.data)) }
            check("$label frames", framesOf(rec["frames"]!!), framesOf(result.packets))
            check("$label counts_anything", (rec["counts_anything"] as JBool).value, DailyHooks.countsAnything(result))
            val o = rec.obj("owned")
            check("$label item_changes", compact(o["item_changes"]), compact(JArr(owned.itemChanges.entries.mapTo(ArrayList()) { (u, c) -> jarr(u, c) })))
            check("$label new_items", compact(o["new_items"]), compact(JArr(owned.newItems.entries.mapTo(ArrayList()) { (u, e) -> jarr(u, e.first, e.second) })))
            check("$label role_changes", compact(o["role_changes"]), compact(JArr(owned.roleChanges.entries.mapTo(ArrayList()) { (f, c) -> jarr(f, c.first, c.second) })))
            check("$label log", compact(o["log"]), compact(owned.log))
            val hashes = sectionHashes(owned.state)
            for ((k, h) in rec.obj("state")) check("$label state $k", h, hashes[k])
            counted++
        }
        println("daily counters: $counted vectors")
        report("daily_counters")
    }

    @Test
    fun `medal mails, Royal Door EXP and the event rules`() {
        assumeTrue(available(), "OPENKNIGHTS_DEV_DIR / OPENKNIGHTS_ORIGINALS not set: local-only test skipped")
        val doc = vectors("counters")!!
        blobs = doc.obj("blobs")
        val inputs = DailyInputs(GameTables(ApkTables(originals!!.resolve("com.enjoygame.hero2d.apk"))))
        for ((i, m) in doc.arr("mails").withIndex()) {
            val vector = m.asObj
            val document = vector.obj("document_before").deepCopy()
            val frames = Achievements.deliverMails(frameList(vector.arr("packets")), vector.arr("mails"),
                { change -> change(document).first }, vector.long("now"), vector.long("offset"))
            check("mail $i frames", framesOf(vector["frames"]!!), framesOf(frames))
            check("mail $i document", compact(vector["document_after"]), compact(document))
        }
        for ((i, d) in doc.arr("doors").withIndex()) {
            val vector = d.asObj
            val after = vector["after"]
            val actual = try { Daily.doorAfter(vector.obj("door"), vector.long("amount"), inputs) } catch (e: Exception) { null }
            if (isError(after)) check("door $i error", true, actual == null)
            else check("door $i", compact(after), compact(actual))
        }
        for ((i, e) in doc.arr("events").withIndex()) {
            val vector = e.asObj
            val action = vector.str("action")
            val plan = vector.obj("plan")
            val packets = frameList(vector.arr("packets"))
            val label = "event $i $action ${compact(plan)} ${vector.arr("packets").size}"
            for ((key, run) in listOf("events" to { DailyHooks.eventsFor(action, plan, packets) },
                    "achievement_events" to { DailyHooks.achievementEvents(action, packets) })) {
                val recorded = vector[key]
                val actual = try { JArr(run().mapTo(ArrayList()) { it.json() }) } catch (x: NotPorted) { throw x } catch (x: Exception) { null }
                if (isError(recorded)) check("$label $key error", true, actual == null)
                else check("$label $key", compact(recorded), compact(actual))
            }
        }
        report("counter rules")
    }
}
