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
}
