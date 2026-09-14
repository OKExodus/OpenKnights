package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JNull
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.hexBytes
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.toHexString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** CI checks of the shops slice with made-up values (the local vectors test replays the reference's own outputs). */
class G4ShopsMadeUpTest {
    private fun hx(text: String) = text.toByteArray(Charsets.UTF_8).toHexString()

    private fun row(text: String, flag: Int, button: String? = null) = jobj("cstring_hex" to hx(text),
        "pairs" to jobj("count" to 1, "entries" to listOf(jobj("wire_values" to jarr(11, 3)))), "wire_u8_flag" to flag,
        "conditional_cstring_hex" to (button?.let { hx(it) } ?: JNull))

    private fun texts(activity: JObj) = activity.obj("rows").arr("entries").map {
        Triple(String(it.asObj.str("cstring_hex").hexBytes()), it.asObj.long("wire_u8_flag"),
            (it.asObj["conditional_cstring_hex"] as? JStr)?.let { b -> String(b.value.hexBytes()) })
    }

    @Test
    fun `a Diamond spend advances the ladder and a claim moves to the next tier`() {
        val activity = jobj("wire_u32_1" to 4242, "cstring_hex" to listOf("61", "30", "30", "72"), "wire_u32_after_strings" to 1000,
            "rows" to jobj("count" to 3, "entries" to listOf(row("Use 10Diamonds", 1, "Claimed"), row("Used 7/20 Diamonds", 1, "Claim Reward"),
                row("Use 50 Diamonds", 0))))
        val state = jobj("subsystems" to jobj("game_activities" to jobj("first_list" to jobj("count" to 1, "entries" to listOf(activity)))))
        val frames = ActivityProgress.advance(state, "diamond_spend", 15, 999)
        assertEquals(1, frames.size)
        assertEquals(1188, frames[0].first)
        assertEquals("92100000035573652031304469616d6f6e647300010b0000000300000001436c61696d656400557365642032322f3230204469616d6f6e647300" +
            "010b0000000300000002436c61696d2052657761726400557365203530204469616d6f6e647300010b0000000300000000", frames[0].second.toHexString())
        val live = state.obj("subsystems").obj("game_activities").obj("first_list").arr("entries")[0].asObj
        val claimed = ActivityProgress.claimRow(live)!!
        assertEquals(1, claimed.first)
        assertEquals(jarr(jarr(11, 3)), claimed.second)
        assertEquals(listOf(Triple("Use 10Diamonds", 1L, "Claimed"), Triple("Use 20 Diamonds", 1L, "Claimed"),
            Triple("Used 22/50 Diamonds", 1L, "Claim Reward")), texts(live))
        // the activity's end has passed: nothing advances
        assertEquals(0, ActivityProgress.advance(state, "diamond_spend", 15, 1000).size)
        assertNull(ActivityProgress.claimRow(live))
    }

    @Test
    fun `shop counts, daily limits and the buy-back list`() {
        assertEquals("0207000000010000000c00000003000000010500000002000000",
            Shops.countsPayload(jobj("12" to 3, "7" to 1, "9" to 0), jobj("5" to 2)).toHexString())
        assertEquals(9L, Shops.dailyLimit(jobj("limit" to 7), 3000))
        assertEquals(3L, Shops.dailyLimit(jobj("limit" to 3), 0))
        assertEquals(12L, Shops.dailyLimit(jobj("limit" to 10), 2500))
        assertEquals("020002000000d204000005000000030000004d00000001000000", Warehouse.listPayload(listOf(
            jobj("entry" to 2, "template" to 1234, "count" to 5), jobj("entry" to 3, "template" to 77, "count" to 1))).toHexString())
        assertEquals(jarr(12, 34), Warehouse.decodePair("0c00000022000000".hexBytes(), "C83"))
    }

    @Test
    fun `Fate Store ranking lists, flags and records`() {
        val doc = jobj("profile" to RouletteRank.PROFILE,
            "days" to jobj("2031-01-02" to jobj("501" to 4500, "502" to 7000, "503" to 100), "2031-01-01" to jobj("501" to 9000)),
            "total" to jobj("501" to 13500, "502" to 7000, "504" to 7000))
        val rows = RouletteRank.listing(doc, RouletteRank.TOTAL, "2031-01-02", "2031-01-01", 4000)
        assertEquals(listOf(501L, 502L, 504L), rows.map { it.first })
        assertEquals(1 to 3, RouletteRank.ownFlag(rows, 504, null))
        assertEquals(2 to 2, RouletteRank.ownFlag(rows, 502, io.github.okexodus.openknights.exact.JInt(3)))
        assertEquals(0 to null, RouletteRank.ownFlag(rows, 999, null))
        assertEquals("0303f5010000416e6e00bc3400000000f6010000426f00581b00000000f801000000581b00000101",
            RouletteRank.rankPayload(3, rows, mapOf(501L to "Ann".toByteArray(), 502L to byteArrayOf(0x42, 0x6f, 0, 0x78)), mapOf(504L to 1)).toHexString())
        val recorded = RouletteRank.record(jobj("profile" to RouletteRank.PROFILE), 505, io.github.okexodus.openknights.exact.JInt(12),
            io.github.okexodus.openknights.exact.JInt(34), "2031-01-03", "2031-01-02")
        assertEquals("""{"profile":"roulette_rank_world_v1","days":{"2031-01-03":{"505":12}},"total":{"505":34}}""",
            io.github.okexodus.openknights.exact.Json.dumps(recorded, itemSeparator = ",", keySeparator = ":"))
    }

    @Test
    fun `Rename Card request and name frame`() {
        assertEquals("Kay", Rename.decodeRequest("4b617900".hexBytes()))
        assertEquals("0102614b617900", Rename.namePayload("Kay").toHexString())
        for (bad in listOf("", "00", "4b61", "4b00790000", "ff00")) {
            val failed = try { Rename.decodeRequest(bad.hexBytes()); false } catch (e: IllegalArgumentException) { true }
            assertEquals(bad != "00", failed, bad)
        }
    }
}
