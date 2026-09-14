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

    // --- the fixes of the reference's oddities (each expected value is the fixed reference's output) ------------------

    private class TextTables(private val files: Map<String, String>) : io.github.okexodus.openknights.gamedata.TableSource {
        override fun names() = files.keys.sorted()
        override fun raw(name: String): ByteArray {
            val file = if (name.endsWith(".csv")) name else "$name.csv"
            return (files[file] ?: throw NoSuchElementException("Missing table: $file")).toByteArray()
        }
    }

    private fun owned(state: JObj, properties: String = "101,102\n") = Owned(io.github.okexodus.openknights.server.store.StateStore.Current(
        1, "", "", state, ByteArray(0), 0, null, io.github.okexodus.openknights.exact.JArr(), emptyList(),
        io.github.okexodus.openknights.exact.JArr(), emptyList(), null, null, null, null, null, emptyMap()),
        DailyInputs(io.github.okexodus.openknights.gamedata.GameTables(TextTables(mapOf("property.csv" to properties, "item.csv" to "101,102\n")))))

    private fun prop(id: Int, tag: Int, bits: Long) = jobj("id" to id, "value" to jobj("tag" to tag, "bits" to bits))

    private fun ladder(id: Long, vararg rows: JObj) = jobj("wire_u32_1" to id, "cstring_hex" to listOf("61", "30", "30", "72"),
        "wire_u32_after_strings" to 1000, "rows" to jobj("count" to rows.size, "entries" to rows.toList()))

    private fun pairRow(text: String, flag: Int, button: String? = null) = jobj("cstring_hex" to hx(text),
        "pairs" to jobj("count" to 1, "entries" to listOf(jobj("wire_values" to jarr(20001, 50)))), "wire_u8_flag" to flag,
        "conditional_cstring_hex" to (button?.let { hx(it) } ?: JNull))

    @Test
    fun `an event claim sends the other moved VIP ladders first`() {
        val state = jobj("role_properties" to listOf(prop(27, 5, 9), prop(6, 8, 100)), "items" to listOf<Any>(),
            "item_capacity_values" to listOf(10, 10, 10), "subsystems" to jobj("game_activities" to jobj("first_list" to jobj("count" to 2,
                "entries" to listOf(ladder(4242, pairRow("VIP Level:5/1", 2, "Claim Reward"), pairRow("VIP Level 4 Times", 0)),
                    ladder(4343, pairRow("Used 30/20 Diamonds", 2, "Claim Reward"), pairRow("Use 90 Diamonds", 0)))))))
        val plan = Claims.planActivityClaim(jobj("activity" to 4343), owned(state), 999)
        assertEquals(listOf(
            "1188:9210000002564950204c6576656c3a392f310001214e00003200000002436c61696d2052657761726400564950204c6576656c20342054696d65730001214e00003200000000",
            "128:0106089600000000000000",
            "1188:f710000002557365203230204469616d6f6e64730001214e00003200000001436c61696d656400557365642033302f3930204469616d6f6e64730001214e00003200000001436c61696d2052657761726400"),
            plan.packets.filter { it.first != 1186 }.map { "${it.first}:${it.second.toHexString()}" })
        assertEquals(listOf(1188, 128, 1186, 1188), plan.packets.map { it.first })
    }

    @Test
    fun `a wheel class without a coupon and a refresh without its voucher rows are clean refusals`() {
        for (classByte in listOf(0, 4)) {
            val state = jobj("role_properties" to listOf<Any>(), "items" to listOf<Any>(), "subsystems" to jobj("game_activities" to jobj(
                "roulette" to jobj("wire_values" to jarr(classByte, 10, 2000, 0, 0, 1)), "roulette_items" to jobj("count" to 0, "entries" to listOf<Any>()))))
            val e = org.junit.jupiter.api.Assertions.assertThrows(Acquisition.Rejected::class.java) {
                Shops.planSpin(jobj("class_byte" to classByte, "times" to 1), owned(state), JObj(), java.math.BigInteger.ONE, 500)
            }
            assertEquals(Acquisition.ERROR_INVALID, e.code)
            assertEquals("The served wheel's class byte has no coupon (1..3)", e.message)
        }
        val catalog = jobj("lucky" to jobj("store_type" to 2, "flag" to 1, "refresh_anchor_epoch" to 0, "refresh_period_s" to 86400,
            "entries" to listOf(jobj("id" to 100, "type" to 2, "pool" to 3, "c6" to 0, "bought" to 0, "item" to 11, "qty" to 1, "kind" to 1,
                "currency" to 90003, "price" to 5)),
            "pools" to jobj("3" to jobj("item" to 11, "qty" to 1, "kind" to 1, "currency" to 90003, "price" to 5))))
        val empty = jobj("role_properties" to listOf<Any>(), "items" to listOf<Any>())
        for (properties in listOf("101,102\n407,1\n", "101,102\n405,77\n", "101,102\n405,77\n407,x\n")) {
            val e = org.junit.jupiter.api.Assertions.assertThrows(Acquisition.Rejected::class.java) {
                Shops.planLuckyInfo(jobj("store_type" to 2, "value" to 1), owned(empty, properties), owned(empty, properties).inputs, catalog, null, 500,
                    seed = java.math.BigInteger.ONE, poolPolicy = true)
            }
            assertEquals(Acquisition.ERROR_WRONG_TYPE, e.code)
            assertEquals("The Lucky Shop refresh voucher is not configured (property 405 / 407)", e.message)
        }
    }

    @Test
    fun `only the exact Lucky info form is a query, and a rank record writes only a change`() {
        assertEquals(true, AcquisitionRoutes.isReadOnly(2725, "0200000000".hexBytes()))
        for (form in listOf("00000000", "0100000000", "020000000000", "050200000000", "0201000000", "")) {
            assertEquals(false, AcquisitionRoutes.isReadOnly(2725, form.hexBytes()), form)
        }
        val document = RouletteRank.emptyDocument()
        val five = io.github.okexodus.openknights.exact.JInt(5)
        val nine = io.github.okexodus.openknights.exact.JInt(9)
        assertEquals(jobj("role" to 7), RouletteRank.recordChange(document, 7, five, nine, "2031-01-02", "2031-01-01").second)
        assertNull(RouletteRank.recordChange(document, 7, five, nine, "2031-01-02", "2031-01-01").second)
        assertEquals(jobj("role" to 7), RouletteRank.recordChange(document, 7, five, nine, "2031-01-03", "2031-01-02").second)
        assertEquals(Rename.ERROR_NO_CARD, Rename.NoRenameCard().code)
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
