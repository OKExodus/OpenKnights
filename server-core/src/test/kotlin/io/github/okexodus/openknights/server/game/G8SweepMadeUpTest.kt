package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.hexBytes
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.toHexString
import io.github.okexodus.openknights.gamedata.GameTables
import io.github.okexodus.openknights.gamedata.TableSource
import io.github.okexodus.openknights.protocol.WireWriter
import io.github.okexodus.openknights.server.store.StateStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * CI checks of the sweep-feature planners on made-up, guard-clean inputs: each expected value is the reference's output
 * for the same input. The full proof against the recorded saves and the APK tables is [G8SweepVectorsTest] (local).
 */
class G8SweepMadeUpTest {
    private class TextTables(private val files: Map<String, String>) : TableSource {
        override fun names() = files.keys.sorted()
        override fun raw(name: String): ByteArray {
            val file = if (name.endsWith(".csv")) name else "$name.csv"
            return (files[file] ?: throw NoSuchElementException("Missing table: $file")).toByteArray()
        }
    }

    private val inputs = DailyInputs(GameTables(TextTables(mapOf(
        "property.csv" to "101,102\n703,20\n",
        "viplv.csv" to "101,102,307,308,309\n1,0,20,10,10\n2,4,30,15,15\n"))))

    private fun framesText(frames: List<Frame>) = "[" + frames.joinToString(", ") { "[${it.first}, \"${it.second.toHexString()}\"]" } + "]"

    private fun u32(v: Long) = WireWriter().u32(v).bytes()

    private fun current(state: JValue, documents: Map<String, JValue?> = emptyMap()): StateStore.Current {
        val docs = LinkedHashMap<String, JValue?>(); docs.putAll(documents)
        return StateStore.Current(1, "", "", state as io.github.okexodus.openknights.exact.JObj, ByteArray(0), 0, null,
            JArr(), emptyList(), JArr(), emptyList(), null, null, null, null, null, docs)
    }

    @Test
    fun `decode_request`() {
        assertEquals("""{"shop":13}""", Json.compact(RebirthShop.decodeRequest(3939, u32(13))))
        assertEquals("""{"shop":13,"position":2}""", Json.compact(RebirthShop.decodeRequest(3937, WireWriter().u32(13).u32(2).bytes())))
        for ((message, call) in listOf<Pair<String, () -> Any>>(
            "C3939 carries 1 u32" to { RebirthShop.decodeRequest(3939, ByteArray(0)) },
            "C3937 carries 2 u32" to { RebirthShop.decodeRequest(3937, u32(13)) },
            "No such shop" to { RebirthShop.decodeRequest(3939, u32(99)) },
            "No such shop position" to { RebirthShop.decodeRequest(3937, WireWriter().u32(13).u32(0).bytes()) })) {
            val e = assertThrows(Acquisition.Rejected::class.java) { call() }
            assertEquals(message to 102, e.message to e.code)
        }
    }

    @Test
    fun `refresh_limit`() {
        assertEquals(20L, RebirthShop.refreshLimit(inputs, 13, io.github.okexodus.openknights.exact.JInt(0)))
        assertEquals(15L, RebirthShop.refreshLimit(inputs, 14, io.github.okexodus.openknights.exact.JInt(4)))
        assertEquals(0L, RebirthShop.refreshLimit(inputs, 13, io.github.okexodus.openknights.exact.JInt(99)))
    }

    @Test
    fun `stateless replies`() {
        val cases = listOf(
            Triple(1633, "0100000000", jobj("event_type" to 1, "policy" to "closed_event_frame")),
            Triple(1639, "030000000002", jobj("event_type" to 3, "policy" to "closed_event_frame")),
            Triple(1645, "0600", jobj("event_type" to 6, "policy" to "closed_event_frame")),
            Triple(1651, "0800", jobj("event_type" to 8, "policy" to "closed_event_frame")),
            Triple(1667, "0800", jobj("event_type" to 8, "policy" to "closed_event_frame")))
        for ((op, hex, fields) in cases) {
            val (frames, log) = SweepFeatures.statelessReply(op, ByteArray(0), null, null)
            assertEquals("[[1760, \"$hex\"]]", framesText(frames), "C$op frames")
            assertEquals(Json.compact(fields), Json.compact(log), "C$op fields")
        }
        // C643 roulette (dead branch, but ported): u8 tab -> [706, tab, 0]
        val (rframes, rlog) = SweepFeatures.statelessReply(643, byteArrayOf(2), null, null)
        assertEquals("[[706, \"0200\"]]", framesText(rframes))
        assertEquals("""{"tab":2,"policy":"empty_rank_lists"}""", Json.compact(rlog))
        // refusals
        for ((message, code, call) in listOf<Triple<String, Int, () -> Any>>(
            Triple("The old event card is not active", 53000, { SweepFeatures.statelessReply(1671, ByteArray(0), null, null) }),
            Triple("Not a stateless sweep request", 102, { SweepFeatures.statelessReply(900, ByteArray(0), null, null) }),
            Triple("C643 carries one tab byte 1-3", 102, { SweepFeatures.statelessReply(643, ByteArray(0), null, null) }))) {
            val e = assertThrows(Acquisition.Rejected::class.java) { call() }
            assertEquals(message to code, e.message to e.code)
        }
        assertEquals("0100000000", SweepFeatures.levelGiftFrame(null).toHexString())
    }

    @Test
    fun `signature codec`() {
        val (raw, text) = SweepFeatures.decodeSignature("6869".hexBytes() + byteArrayOf(0))
        assertEquals("6869", raw.toHexString()); assertEquals("hi", text)
        assertEquals("011561686900", SweepFeatures.signaturePayload("6869".hexBytes()).toHexString())
        assertEquals("01156100", SweepFeatures.signaturePayload(ByteArray(0)).toHexString())
        for ((message, call) in listOf<Pair<String, () -> Any>>(
            "C577 carries one NUL-terminated text" to { SweepFeatures.decodeSignature("6869".hexBytes()) },      // no NUL
            "C577 carries one NUL-terminated text" to { SweepFeatures.decodeSignature(byteArrayOf(0x61, 0, 0x62, 0)) },  // embedded NUL
            "Signature is not UTF-8" to { SweepFeatures.decodeSignature(byteArrayOf(0xff.toByte(), 0)) })) {
            val e = assertThrows(Acquisition.Rejected::class.java) { call() }
            assertEquals(message, e.message)
        }
    }

    @Test
    fun `totem lineup`() {
        val doc = jobj("profile" to SweepFeatures.TOTEM_PROFILE, "totems" to jarr(jarr(101, 1, 50, 0), jarr(105, 3, 10, 0)), "lineup" to 105)
        val switch = SweepFeatures.planTotemLineup(u32(101), current(jobj(), mapOf("totem_state" to doc.deepCopy())), null)
        assertEquals("""{"lineup_before":105,"lineup_after":101,"totem_state_after":{"profile":"totem_state_v1","totems":[[101,1,50,0],[105,3,10,0]],"lineup":101},"evidence_class":"native_use_layout_policy_refusal"}""",
            Json.compact(switch.data))
        assertEquals("[[2884, \"65000000\"]]", framesText(switch.packets))
        val same = assertThrows(SweepFeatures.Unchanged::class.java) { SweepFeatures.planTotemLineup(u32(105), current(jobj(), mapOf("totem_state" to doc.deepCopy())), null) }
        assertEquals("[[2884, \"69000000\"]]", framesText(same.packets))
        assertEquals("""{"lineup":105}""", Json.compact(same.fields))
        for ((message, payload) in listOf("That Mastery is not owned" to u32(999), "C2529 carries one u32" to byteArrayOf(1))) {
            val e = assertThrows(Acquisition.Rejected::class.java) { SweepFeatures.planTotemLineup(payload, current(jobj(), mapOf("totem_state" to doc.deepCopy())), null) }
            assertEquals(message to 102, e.message to e.code)
        }
    }

    @Test
    fun `tmp VIP claim`() {
        val state = jobj("role_properties" to jarr(jobj("id" to 27, "value" to jobj("tag" to 5, "bits" to 0))))
        val now = 1_000_000L
        val plan = SweepFeatures.planTmpVipClaim(ByteArray(0), current(state.deepCopy()), null, now)
        assertEquals("""{"tmp_vip_after":{"profile":"tmp_vip_v1","claimed":true,"claimed_at":1000000,"expires_at":1345600,"seed":{"source":"unclaimed_default_policy"},"duration_seconds":345600,"clock":"device_clock_epoch"},"tmp_vip_state_after":1,"expires_at":1345600,"duration_seconds":345600,"evidence_class":"native_use_layout_capture_states_policy_duration"}""",
            Json.compact(plan.data))
        assertEquals("[[1824, \"0100460500\"]]", framesText(plan.packets))
        // already active -> Unchanged
        val active = jobj("profile" to SweepFeatures.TMP_VIP_PROFILE, "claimed" to true, "claimed_at" to (now - 10), "expires_at" to (now + 5000),
            "duration_seconds" to 345600, "clock" to "device_clock_epoch")
        val unchanged = assertThrows(SweepFeatures.Unchanged::class.java) {
            SweepFeatures.planTmpVipClaim(ByteArray(0), current(state.deepCopy(), mapOf("tmp_vip" to active)), null, now)
        }
        assertEquals("[[1824, \"0188130000\"]]", framesText(unchanged.packets))
        assertEquals("""{"tmp_vip_state":1,"tmp_vip_left":5000}""", Json.compact(unchanged.fields))
        // payload present -> refusal
        val e = assertThrows(Acquisition.Rejected::class.java) { SweepFeatures.planTmpVipClaim(byteArrayOf(1), current(state.deepCopy()), null, now) }
        assertEquals("C25 carries no payload" to 102, e.message to e.code)
    }
}
