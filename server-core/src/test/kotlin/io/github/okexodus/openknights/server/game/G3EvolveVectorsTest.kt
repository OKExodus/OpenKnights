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
import io.github.okexodus.openknights.exact.toHexString
import io.github.okexodus.openknights.gamedata.ApkTables
import io.github.okexodus.openknights.gamedata.GameTables
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Local only (OPENKNIGHTS_DEV_DIR with `vectors-g3/` from the maintainer's private exporter, OPENKNIGHTS_ORIGINALS with
 * the player's APK): gear / jewelry evolve replayed on the reference's own outputs — every owned gear and equipped jewel
 * of the recorded saves at every configured grade, with Gold, role level, item level and material variations.
 */
class G3EvolveVectorsTest {
    private val dev: Path? = System.getenv("OPENKNIGHTS_DEV_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
    private val originals: Path? = System.getenv("OPENKNIGHTS_ORIGINALS")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
    private val failures = ArrayList<String>()
    private var checks = 0

    private fun file(): Path? = dev?.resolve("vectors-g3")?.resolve("equip_evolve.json")?.takeIf { Files.isRegularFile(it) }

    private fun check(label: String, expected: Any?, actual: Any?) {
        checks++
        if (expected != actual) failures.add("$label\n  expected: ${expected.toString().take(900)}\n  actual:   ${actual.toString().take(900)}")
    }

    private fun compact(v: JValue?): String = Json.dumps(v ?: JNull, itemSeparator = ",", keySeparator = ":")

    private fun isError(v: JValue?): Boolean = (v as? JObj)?.let { it.containsKey("error") && it.containsKey("message") } == true

    /** Compare a recorded outcome ({error, message, code} or a value) with the port's. */
    private fun outcome(label: String, recorded: JValue?, run: () -> JValue): JValue? {
        val actual = try { run() } catch (e: NotPorted) { throw e } catch (e: IllegalArgumentException) {
            if (!isError(recorded)) { failures.add("$label: unexpected ${e::class.simpleName}: ${e.message}"); return null }
            val rec = recorded as JObj
            check("$label message", rec.str("message"), e.message)
            if (rec["code"] != null && rec["code"] != JNull) check("$label code", rec["code"], JInt((e as? EquipEvolve.EquipEvolveRejected)?.code ?: 102))
            return null
        }
        if (isError(recorded)) { failures.add("$label: expected ${(recorded as JObj).str("error")} (${recorded.str("message")}), got ${compact(actual).take(200)}"); return null }
        check(label, compact(recorded), compact(actual))
        return actual
    }

    @Test
    fun `gear and jewelry evolve on every recorded save`() {
        val path = file()
        assumeTrue(path != null && originals != null, "OPENKNIGHTS_DEV_DIR / OPENKNIGHTS_ORIGINALS not set: local-only test skipped")
        val doc = Json.loads(Files.readString(path!!)).asObj
        val blobs = doc.obj("blobs")
        val inputs = EquipEvolveInputs(GameTables(ApkTables(originals!!.resolve("com.enjoygame.hero2d.apk"))))
        var accepted = 0
        for ((i, v) in doc.arr("vectors").withIndex()) {
            val vector = v.asObj
            val kind = vector.str("kind")
            val template = vector.long("template")
            val grade = vector.long("grade")
            val label = "$i $kind $template/$grade ${compact(vector["variant"])}"
            fun load(): JObj = if (kind == "gear") inputs.gear(template, grade) else inputs.jewelry(template, grade)
            val inputsError = vector["inputs_error"]
            if (inputsError != null) {
                val e = try { load(); null } catch (x: IllegalArgumentException) { x }
                check("$label inputs error", inputsError.asObj.str("message"), e?.message)
                continue
            }
            val loaded = if (template == 0L) null else load()
            val records = vector.obj("records").entries.associate { (k, r) -> k.toLong() to r.asArr.map { (it as JInt).value.toLong() } }
            val items = vector.obj("items").entries.associate { (k, r) -> k.toLong() to r.asArr }
            val roleLevel = (vector["role_level"] as? JInt)?.value?.toLong()
            val extra = vector.arr("extra").map { (it as JInt).value.toLong() }
            val request = vector.obj("request")
            val plan = outcome(label, vector["plan"]) {
                if (kind == "gear") EquipEvolve.planGearEvolve(request, records, items, (vector["gold"] as JInt).value, roleLevel, loaded, extra)
                else EquipEvolve.planJewelEvolve(request, records, items, roleLevel, loaded, extra)
            } as? JObj ?: continue
            accepted++
            val gold = if (kind == "gear") TransactionPackets.goldPropertyPayload((plan["gold_after"] as JInt).value) else null
            val expected = vector.arr("packets").map { f -> "${f.asArr[0]}:${blobs.str((f.asArr[1] as JStr).value)}" }
            check("$label packets", expected, EquipEvolve.evolvePackets(plan, gold).map { "${it.first}:${it.second.toHexString()}" })
        }
        for (c in doc.arr("codecs")) {
            val payload = c.asObj.str("payload").hexBytes()
            outcome("codec ${c.asObj.str("payload")}", c.asObj["decoded"]) { EquipEvolve.decodeRequest(payload) }
        }
        for (l in doc.arr("lists")) {
            val payload = l.asObj.str("payload").hexBytes()
            outcome("list ${l.asObj.str("payload").take(20)}", l.asObj["decoded"]) {
                JArr(EquipEvolve.decodeJewelList(payload).mapTo(ArrayList()) { r -> JArr(r.mapTo(ArrayList<JValue>()) { JInt(it) }) })
            }
        }
        for (b in doc.arr("blocks")) {
            val block = b.asObj
            outcome("block ${block.long("grade")}", block["out"]) {
                JStr(EquipEvolve.jewelBlockWithGrade(block.str("raw").hexBytes(), block.long("grade")).toHexString())
            }
        }
        println("equip evolve: $accepted accepted plans, $checks checks, ${failures.size} failures")
        assertTrue(failures.isEmpty()) { "${failures.size} of $checks checks differ:\n" + failures.take(25).joinToString("\n") }
    }
}
