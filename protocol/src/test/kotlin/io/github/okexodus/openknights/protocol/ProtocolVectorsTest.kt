package io.github.okexodus.openknights.protocol

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.asArr
import io.github.okexodus.openknights.exact.asInt
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.hexBytes
import io.github.okexodus.openknights.exact.sha256Hex
import io.github.okexodus.openknights.exact.toHexString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class ProtocolVectorsTest {
    private fun load(name: String): JObj {
        val stream = javaClass.getResourceAsStream("/vectors/$name.json") ?: error("vector file $name.json is missing")
        return stream.use { Json.loads(it.readBytes()).asObj }.also { assertEquals("openknights_vectors_v1", it.str("profile")) }
    }

    private class Tally(val what: String) {
        var checked = 0
        val failures = ArrayList<String>()
        fun check(expected: Any?, actual: Any?, label: () -> String) {
            checked++
            if (expected != actual) failures.add("${label()}: expected <${expected.toString().take(300)}> but was <${actual.toString().take(300)}>")
        }
        fun done() {
            println("$what: ${checked - failures.size}/$checked match")
            if (failures.isNotEmpty()) fail<Unit>("$what: ${failures.size} of $checked differ:\n  " + failures.take(10).joinToString("\n  "))
        }
    }

    private fun pattern(n: Int) = ByteArray(n) { ((it * 7 + n) % 251).toByte() }

    @Test
    fun `frames encode and decode like the reference`() {
        val doc = load("frames")
        val t = Tally("frames")
        for (v in doc.arr("vectors")) {
            val c = v.asObj
            val wire = Frames.encode(c.long("opcode").toInt(), pattern(c.long("length").toInt()))
            t.check(c.long("wire_bytes").toInt(), wire.size) { "size ${c.long("length")}" }
            t.check(c.str("wire_sha256"), sha256Hex(wire)) { "sha ${c.long("length")}" }
        }
        for (v in doc.arr("streams")) {
            val c = v.asObj
            var wire = ByteArray(0)
            for (f in c.arr("frames")) wire += Frames.encode(f.asArr[0].asInt.toInt(), pattern(f.asArr[1].asInt.toInt()))
            val decoder = FrameDecoder()
            val out = JArr()
            var position = 0
            for (cut in c.arr("cuts")) {
                val size = cut.asInt.toInt()
                decoder.feed(wire.copyOfRange(position, position + size)).forEach {
                    out.add(io.github.okexodus.openknights.exact.jarr(it.opcode, it.payload.size, sha256Hex(it.payload), it.chunks))
                }
                position += size
            }
            decoder.finish()
            t.check(Json.compact(c.arr("decoded")), Json.compact(out)) { "stream" }
        }
        for (v in doc.arr("errors")) {
            val c = v.asObj
            val raw = c.strOrNull("hex")?.hexBytes() ?: (byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 7, 0) + ByteArray(0xFFFA))
            val result = try { FrameDecoder().apply { feed(raw); finish() }; "ok" } catch (e: ProtocolException) { "error" }
            t.check(c.str("result"), result) { "error case ${c.long("length")}" }
        }
        t.done()
    }

    @Test
    fun `typed values decode to the reference tree and re-encode exactly`() {
        val t = Tally("typed values")
        for (v in load("typed-values").arr("vectors")) {
            val c = v.asObj
            val payload = c.str("payload_hex").hexBytes()
            val reader = WireReader(payload)
            val parsed = TypedValues.readFields(reader)
            t.check(payload.size, reader.offset) { "consumed" }
            t.check(c.str("parsed"), Json.compact(parsed)) { "tree" }
            t.check(c.str("payload_hex"), TypedValues.encodeFieldsBytes(parsed).toHexString()) { "re-encode" }
        }
        t.done()
    }

    @Test
    fun `player records decode to the reference tree and re-encode exactly`() {
        val doc = load("player-state")
        val t = Tally("player state")
        for (v in doc.arr("vectors")) {
            val c = v.asObj
            val payload = c.str("payload_hex").hexBytes()
            val parsed = PlayerState.parse(payload)
            t.check(c.str("parsed"), Json.compact(parsed)) { "tree (${payload.size} bytes)" }
            t.check(c.str("payload_hex"), PlayerState.encode(parsed).toHexString()) { "re-encode" }
            // The stored form round-trips too: a save keeps this tree as JSON text and re-encodes it at every read.
            t.check(c.str("payload_hex"), PlayerState.encode(Json.loads(c.str("parsed")).asObj).toHexString()) { "from stored JSON" }
        }
        for (v in doc.arr("prefix_only")) {
            val c = v.asObj
            t.check(c.str("parsed"), Json.compact(PlayerState.parse(c.str("payload_hex").hexBytes(), prefixOnly = true))) { "prefix" }
        }
        for (v in doc.arr("truncated")) {
            val c = v.asObj
            val payload = c.str("payload_hex").hexBytes()
            val got = try { Json.compact(PlayerState.parse(payload)) } catch (e: ProtocolException) { "error" }
            t.check(if (c.str("result") == "error") "error" else c.str("parsed"), got) { "truncated at ${payload.size}" }
        }
        t.done()
    }

    @Test
    fun `inventory lists match the reference`() {
        val t = Tally("inventory")
        for (v in load("inventory").arr("vectors")) {
            val c = v.asObj
            val parsed = Inventory.decode(c.str("payload_hex").hexBytes())
            t.check(c.str("parsed"), Json.compact(parsed)) { "tree" }
            t.check(c.str("payload_hex"), Inventory.encode(parsed).toHexString()) { "re-encode" }
        }
        t.done()
    }

    @Test
    fun `battle reports, rewards and campaign packets match the reference`() {
        val doc = load("battle-report")
        val t = Tally("battle report")
        for (v in doc.arr("vectors")) {
            val c = v.asObj
            val parsed = BattleReport.parse(c.str("payload_hex").hexBytes())
            t.check(c.str("parsed"), Json.compact(parsed)) { "report" }
            t.check(c.str("payload_hex"), BattleReport.encode(parsed).toHexString()) { "re-encode" }
        }
        for (v in doc.arr("rewards")) {
            val c = v.asObj
            val parsed = BattleReport.readReward(WireReader(c.str("payload_hex").hexBytes()))
            t.check(c.str("parsed"), Json.compact(parsed)) { "reward" }
            t.check(c.str("payload_hex"), BattleReport.encodeReward(parsed).toHexString()) { "reward re-encode" }
        }
        for (v in doc.arr("campaign_packets")) {
            val c = v.asObj
            t.check(c.str("parsed"), Json.compact(BattleReport.parseCampaignPacket(c.long("opcode").toInt(), c.str("payload_hex").hexBytes()))) { "campaign ${c.long("opcode")}" }
        }
        t.done()
    }

    @Test
    fun `activity updates match the reference`() {
        val t = Tally("activity update")
        for (v in load("activity-update").arr("vectors")) {
            val c = v.asObj
            val parsed = PlayerSections.readActivityUpdate(c.str("payload_hex").hexBytes())
            t.check(c.str("parsed"), Json.compact(parsed)) { "tree" }
            t.check(c.str("payload_hex"), PlayerSections.encodeActivityUpdate(parsed.long("activity_id"), parsed.obj("rows")).toHexString()) { "re-encode" }
        }
        t.done()
    }

}
