package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JNull
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * CI (no game data): the Event Hall decoders on made-up, guard-clean payloads and the `exchangeBody` /
 * `decodeExchangeBody` round-trip on a made-up type-9 body. Byte layout only — no table lookups.
 */
class G8EventHallMadeUpTest {
    private fun u32(v: Long): ByteArray = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())
    private fun compact(v: JValue?): String = Json.dumps(v ?: JNull, itemSeparator = ",", keySeparator = ":")

    @Test
    fun `decode_pie_request`() {
        assertEquals(compact(jobj("piece" to 0x65L)), compact(EventHall.decodePieRequest(u32(0x65))))
        assertEquals(compact(jobj("piece" to 0xFFFFFFFFL)), compact(EventHall.decodePieRequest(u32(0xFFFFFFFFL))))
        assertThrows(Acquisition.Rejected::class.java) { EventHall.decodePieRequest(ByteArray(0)) }
        assertThrows(Acquisition.Rejected::class.java) { EventHall.decodePieRequest(ByteArray(5)) }
    }

    @Test
    fun `decode_combine`() {
        val payload = u32(12_345_678) + byteArrayOf(4) + u32(11) + u32(22) + u32(33) + u32(44)
        assertEquals(compact(jobj("recipe" to 12_345_678L, "uids" to jarr(11, 22, 33, 44))), compact(EventHall.decodeCombine(payload)))
        assertThrows(Acquisition.Rejected::class.java) { EventHall.decodeCombine(ByteArray(20)) }          // wrong length
        assertThrows(Acquisition.Rejected::class.java) { EventHall.decodeCombine(u32(1) + byteArrayOf(3) + u32(1) + u32(2) + u32(3) + u32(4)) }  // count != 4
    }

    @Test
    fun `decode_exchange`() {
        val payload = u32(12_345_678) + byteArrayOf(2) + u32(99)
        assertEquals(compact(jobj("event" to 12_345_678L, "formula" to 2L, "amount" to 99L)), compact(EventHall.decodeExchange(payload)))
        assertThrows(Acquisition.Rejected::class.java) { EventHall.decodeExchange(ByteArray(8)) }
        assertThrows(Acquisition.Rejected::class.java) { EventHall.decodeExchange(ByteArray(10)) }
    }

    @Test
    fun `exchange_body round trips through decode_exchange_body`() {
        val events = listOf(
            jobj("id" to 12_345_678L, "name" to "Made", "desc" to "Up", "formulas" to jarr(
                jobj("materials" to jarr(jarr(1, 111, 2), jarr(2, 222, 1)), "result" to jarr(3, 333, 4), "limit" to 9),
                jobj("materials" to jarr(), "result" to jarr(9, 444, 1), "limit" to 1))),
            jobj("id" to 87_654_321L, "name" to "中文", "desc" to "", "formulas" to jarr(
                jobj("materials" to jarr(jarr(9, 555, 3)), "result" to jarr(1, 666, 100), "limit" to 50))))
        val served = 1_700_000_000L
        val body = EventHall.exchangeBody(events, null, served)
        val decoded = EventHall.decodeExchangeBody(body)
        assertEquals(2, decoded.size)
        // ids / names / desc preserved
        assertEquals("12345678", compact(decoded[0].asObj["id"]))
        assertEquals(io.github.okexodus.openknights.exact.JStr("Made"), decoded[0].asObj["name"])
        assertEquals(io.github.okexodus.openknights.exact.JStr("中文"), decoded[1].asObj["name"])
        // formula 0: materials, result, remaining = limit (nothing used)
        val f0 = decoded[0].asObj.arr("formulas")[0].asObj
        assertEquals(compact(jarr(jarr(1, 111, 2), jarr(2, 222, 1))), compact(f0["materials"]))
        assertEquals(compact(jarr(3, 333, 4)), compact(f0["result"]))
        assertEquals("9", compact(f0["remaining"]))
        // cd of a non-scoped event is the permanent horizon (5 years, below the 0x7fffffff cap)
        assertEquals("157680000", compact(decoded[0].asObj["cd"]))
        assertThrows(io.github.okexodus.openknights.server.game.PyValues.ValueError::class.java) { EventHall.decodeExchangeBody(byteArrayOf(0, 1)) }
    }
}
