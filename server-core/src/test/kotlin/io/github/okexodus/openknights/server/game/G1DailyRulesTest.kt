package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JNull
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.hexBytes
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.toHexString
import io.github.okexodus.openknights.server.DeviceClock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigInteger

/**
 * The daily systems' table-free rules on made-up values, with the reference's outputs for the same inputs: the seeded
 * draws (Royal Door tasks, bounty weights, rebirth flags, Lucky Shop pools), the device-clock day rules and the frame
 * codecs of the login set.
 */
class G1DailyRulesTest {
    private val now = 1_900_000_000L

    @BeforeEach
    fun clock() {
        DeviceClock.active = DeviceClock(null, timeSource = { 0L }, offsetSource = { 3600 })
    }

    @AfterEach
    fun reset() {
        DeviceClock.active = null
    }

    private fun compact(v: JValue?): String = Json.dumps(v ?: JNull, itemSeparator = ",", keySeparator = ":")

    private fun json(text: String): JValue = Json.loads(text)

    @Test
    fun `seeded draws equal the reference's`() {
        assertEquals(listOf(2L, 3L, 5L, 7L), Daily.doorTasks("world-a", "2030-01-02"))
        assertEquals(listOf(1L, 4L, 6L, 7L), Daily.doorTasks("", "1999-12-31"))
        val board = Quests.rng("owner-x", "board|2030-01-02|1900000000")
        assertEquals(listOf(2L, 3L, 1L, 1L, 1L, 5L, 3L, 1L, 1L, 2L, 2L, 1L, 1L, 3L, 2L),
            (1..15).map { board.choices(listOf(1L, 2L, 3L, 4L, 5L), listOf(5000L, 3000L, 1000L, 500L, 500L))[0] })
        val shop = RebirthShop.rng("owner-x", "2030-01-02", 13L, "day")
        assertEquals(listOf(9909L, 2952L, 9042L, 5475L, 514L, 3772L), (1..6).map { shop.randrange(10000) })
        assertEquals(BigInteger("15821351036939033702"), Shops.cycleSeed(20000))
    }

    @Test
    fun `lucky shop pools, view and S3170`() {
        val pools = JObj()
        for (p in listOf(1L, 2L, 5L, 7L)) pools[p.toString()] = jobj("item" to 1000 + p, "qty" to p, "kind" to 1, "currency" to 90003, "price" to 10 * p)
        val entries = JArr((0 until 3).mapTo(ArrayList()) { i ->
            jobj("id" to 100 + i, "type" to 2, "pool" to 1, "c6" to 0, "bought" to 0, "item" to 1001, "qty" to 1, "kind" to 1, "currency" to 90003, "price" to 10)
        })
        val lucky = jobj("refresh_period_s" to 86400, "refresh_anchor_epoch" to 0, "store_type" to 2, "flag" to 1, "pools" to pools, "entries" to entries)
        assertEquals(listOf(1L, 7L, 5L), Shops.drawLuckyPools(lucky, Shops.cycleSeed(20000)))
        val (view, remaining, document) = Shops.luckyView(jobj("lucky" to lucky), jobj("profile" to "lucky_state_v1", "cycle" to 20000, "bought" to jarr(101)),
            20000L * 86400 + 5000, poolPolicy = true)
        assertEquals(81400L, remaining)
        assertEquals("""{"profile":"lucky_state_v1","cycle":20000,"bought":[101]}""", compact(document))
        assertEquals("[1,7,5]", compact(JArr(view.mapTo(ArrayList()) { it.asObj["pool"]!! })))
        assertEquals("036400000002010000000000e90300000100000001935f01000a0000006500000002070000000001ef0300000700000001935f01004600" +
            "00006600000002050000000000ed0300000500000001935f010032000000f83d0100f83d010001",
            Shops.encodeLuckyInfo(view, remaining, remaining, 1).toHexString())
    }

    @Test
    fun `device clock days, check in and the Great Offer window`() {
        assertEquals("2030-03-17", Shops.dayOf(now))
        assertEquals(1_900_018_800L, Shops.nextDayStart(now))
        val month = Shops.localDatetime(now).monthValue
        val sign = Daily.seedSignIn(byteArrayOf((month - 1).toByte(), 30, 2, 5, 2, 3, 4), now, jobj("source" to "test"))
        assertEquals("""{"profile":"sign_in_state_v1","month":"2030-03","signed":[3,4],"chain_day":null,"row":1,"available_at":0,"seed":{"source":"test"}}""",
            compact(sign))
        assertEquals("021f0511020304", Daily.monthPayload(sign, now).toHexString())
        val waiting = PyDocs.shallow(sign).also { it["row"] = JInt(1); it["available_at"] = JInt(now + 100) }
        assertEquals("0164000000", Daily.giftPayload(waiting, now).toHexString())
        assertEquals("05ffffffff", Daily.giftPayload(PyDocs.shallow(sign).also { it["row"] = JInt(5) }, now).toHexString())
        assertNull(EventHall.greatOfferWindow(now))
        assertEquals("2030-03" to 1_898_809_200L, EventHall.greatOfferWindow(1_898_679_600L))
        assertNull(EventHall.greatOfferWindow(1_898_679_600L + 2 * 86400))
        assertEquals("""{"profile":"castle_state_v1","day":"2030-03-17","collected":{"1":0,"2":0,"4":0},"alchemy":null,"servants":{}}""",
            compact(Castle.castleDocument(jobj("profile" to "castle_state_v1", "day" to "2000-01-01", "collected" to jobj("1" to 3)), now)))
    }

    @Test
    fun `exchanges, event definitions and the roulette window`() {
        val weekly = json("""{"id":9100001,"name":"A","desc":"B","limit_scope":"weekly","reset":{"weekday":4,"hour":6},
            "formulas":[{"materials":[[1,2001,3]],"result":[1,2002,1],"limit":5}]}""").asObj
        val daily = json("""{"id":9100002,"name":"C","limit_scope":"daily","formulas":[{"materials":[[1,2001,3],[9,3001,1]],"result":[3,4001,1],"limit":2}]}""").asObj
        assertEquals("2030-03-15T06" to 1_900_386_000L, EventHall.exchangePeriod(weekly, now))
        assertEquals("2030-03-17T00" to 1_900_018_800L, EventHall.exchangePeriod(daily, now))
        val document = json("""{"profile":"exchange_state_v2","periods":{},"used":{"9100001:0":2}}""")
        assertEquals("02e1da8a00d0e3050041004200010101d10700000300000001d20700000100000003000000e2da8a0070490000430000010201d10700000300" +
            "000009b90b00000100000003a10f00000100000002000000", EventHall.exchangeBody(listOf(weekly, daily), document, now, now).toHexString())
        val definitions = jobj("profile" to Events.PROFILE, "activities" to JArr(), "exchanges" to jarr(weekly, daily))
        Events.validate(definitions)
        val broken = jobj("profile" to Events.PROFILE, "activities" to JArr(), "exchanges" to jarr(PyDocs.shallow(weekly).also { it["formulas"] = JArr() }))
        assertEquals("9100001: 1 – 255 formulas", assertThrows(Events.EventDefinitionError::class.java) { Events.validate(broken) }.message)
        val section = json("""{"roulette":{"wire_values":[1,0,1800000000,5,9,0]}}""").asObj
        assertEquals(true, Events.rollRouletteWindow(section, jobj("roulette_window" to "rolling"), now))
        assertEquals("""{"roulette":{"wire_values":[1,1900000000,2057680000,5,9,0]}}""", compact(section))
    }


    @Test
    fun `login frame codecs`() {
        val slots = ("04" + "01" + "07000000" + "02" + "100e0000" + "32000000" + "02" + "09000000" + "01" + "02" + "0b000000" + "0c000000" +
            "03" + "00000000" + "04" + "05000000" + "03" + "616200").hexBytes()
        val explore = HiddenTraining.exploreDocument(null, slots, now, jobj("source" to "test"))
        assertEquals("""{"profile":"explore_state_v1","slots":[{"pos":1,"hero":7,"state":2,"choices":[],"explore":0,"returns_at":1900000050,""" +
            """"total":3600,"text_hex":"","reward":null},{"pos":2,"hero":9,"state":1,"choices":[11,12],"explore":0,"returns_at":0,"total":0,""" +
            """"text_hex":"","reward":null},{"pos":3,"hero":0,"state":0,"choices":[],"explore":0,"returns_at":0,"total":0,"text_hex":"",""" +
            """"reward":null},{"pos":4,"hero":5,"state":3,"choices":[],"explore":0,"returns_at":0,"total":0,"text_hex":"6162","reward":null}],""" +
            """"seed":{"source":"test"}}""", compact(explore))
        assertEquals("04010700000002100e00001e000000020900000001020b0000000c0000000300000000040500000003616200",
            HiddenTraining.slotsPayload(explore, now + 20).toHexString())
        assertEquals("050a00000000000000", HiddenTraining.smithCdFrame(jobj("smith" to jarr(now + 10, 0)), now).second.toHexString())
        assertEquals("0000000000000000", HiddenTraining.craftCdFrame(null, now).second.toHexString())

        val campaign = Campaign.newDocument("02001e0000000a000000".hexBytes(), jobj("source" to "test"))
        assertEquals("""{"profile":"campaign_state_v1","day":null,"boxes":[30,10],"regen_anchor":null,"seed":{"source":"test"}}""", compact(campaign))
        assertEquals("02000a0000001e000000", Campaign.boxesPayload(campaign).toHexString())

        val rows = Goals.decodeRows(("02" + "ca000000" + "02" + "00000000" + "65000000" + "03" + "05000000").hexBytes())
        assertEquals("[[202,2,0],[101,3,5]]", compact(rows))
        assertEquals("02650000000305000000ca0000000200000000", Goals.listPayload(rows).toHexString())

        val quests = Quests.decodeQuests(("02" + "65350c00" + "02" + "00000000" + "05870100" + "03" + "01000000" + "28000000").hexBytes())
        assertEquals("""{"quests":[[800101,2,0],[100101,3,1]],"points":40}""", compact(quests))
        assertEquals("0205870100030100000065350c00020000000028000000", Quests.questsPayload(quests).toHexString())
        val board = Quests.seedBoard(("01" + "c5ae0a00" + "01" + "00000000" + "03" + "02000000" + "19000000" + "80510100" + "c5ae0a00" +
            "2c010000" + "0a000000").hexBytes(), now, jobj("source" to "test"))
        assertEquals("""{"profile":"bounty_board_v1","day":null,"rows":[[700101,1,0,3]],"used":2,"limit":25,"board_until":1900086400,""" +
            """"auto_id":700101,"auto_until":1900000300,"free":10,"seed":{"source":"test"}}""", compact(board))
        assertEquals("01c5ae0a000100000000030200000019000000f0f1ffffc5ae0a00000000000a000000", Quests.boardPayload(board, now + 90000).toHexString())

        val shops = JObj()
        for (s in listOf(13L, 14L, 15L)) shops[s.toString()] = jobj("entries" to (1L..6L).map { k -> listOf(s * 100 + k, k % 2, 0L) }, "used" to s - 13)
        val list = RebirthShop.listPayload(jobj("shops" to shops), now)
        assertEquals("030d000000061505000001001605000000001705000001001805000000001905000001001a0500000000000000000e00000006790500000100" +
            "7a05000000007b05000001007c05000000007d05000001007e0500000000010000000f00000006dd0500000100de0500000000df0500000100e005000000" +
            "00e10500000100e205000000000200000070490000", list.toHexString())
        val (decoded, cd) = RebirthShop.decodeList(list)
        assertEquals(18800L, cd)
        assertEquals(compact(shops), compact(decoded))

        val tech = Castle.decodeGuildTech(("01" + "02" + "65000000" + "0300" + "0a00" + "66000000" + "0100" + "0500").hexBytes())
        assertEquals("""{"profile":"guild_tech_state_v1","in_guild":true,"techs":[[101,3,10],[102,1,5]]}""", compact(tech))
        assertEquals("01026500000003000a006600000001000500", Castle.guildTechPayload(tech).toHexString())
        assertEquals("0a00000000002c01000000b80b000001040100002c01000000b80b0000", Claims.cardStatePayload(
            json("""{"3":{"owned":true,"days":4,"claim_day":"2030-01-02"},"4":{"owned":false}}"""), "2030-01-03").toHexString())
        assertEquals(1_893_546_245L, Summon.isoTimestamp("2030-01-02T03:04:05.678+02:00"))
    }
}