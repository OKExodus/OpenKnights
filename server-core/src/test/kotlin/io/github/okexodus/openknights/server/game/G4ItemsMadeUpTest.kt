package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.PyRandom
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.toHexString
import io.github.okexodus.openknights.gamedata.GameTables
import io.github.okexodus.openknights.gamedata.TableSource
import io.github.okexodus.openknights.server.store.StateStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigInteger

/**
 * CI checks of the items slice on made-up tables and saves: each expected value is the reference's output for the same
 * made-up input. The full proof against the recorded saves and the APK tables is [G4ItemsVectorsTest] (local).
 */
class G4ItemsMadeUpTest {
    private class TextTables(private val files: Map<String, String>) : TableSource {
        override fun names() = files.keys.sorted()
        override fun raw(name: String): ByteArray {
            val file = if (name.endsWith(".csv")) name else "$name.csv"
            return (files[file] ?: throw NoSuchElementException("Missing table: $file")).toByteArray()
        }
    }

    private val inputs = DailyInputs(GameTables(TextTables(mapOf(
        "item.csv" to "101,102,104,105,106,107,110,203,204,205,206,207,305\n" +
            "9101,1,1,0,90001,500,0,0,0,0,0,,1\n9102,2,1,0,80001,77,0,0,0,0,0,,1\n9103,3,4,0,0,88,0,0,0,0,0,,2\n" +
            "9104,4,2,0,0,0,0,0,0,0,0,,1\n9105,5,2,0,0,0,0,0,0,0,0,,1\n9106,6,2,0,0,0,0,40,0,0,0,,1\n",
        "box.csv" to "101,102,103,104,105,106,107,108,109,110,111,112,113,114,115,116,117,201,202,203\n" +
            "1,77,1,30,0,0,0,0,0,0,9104,2,0,0,0,0,0,0,0,0\n2,77,1,70,40,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0\n" +
            "3,77,2,100,0,0,0,0,0,0,9106,1,0,0,0,0,0,0,0,0\n4,77,3,5,0,0,0,0,0,0,9104,0,0,0,0,0,0,0,0,0\n" +
            "5,77,3,5,0,0,0,0,0,0,9105,3,0,0,0,0,0,0,0,0\n",
        "hecheng.csv" to "101,102,103,104,105,106,107,108,109,110,111,112,113,115,201,202,203,301,302,303,304\n" +
            "88,81000,9106,10,9103,3,9105,1,0,0,0,0,1,0,0,0,0,0,0,0,0\n",
        "item_rh.csv" to "101,102,103\n9105,9106,4\n9104,9101,2\n",
        "property.csv" to "101,102\n970,100\n971,50\n",
        "hero.csv" to "101,104,140\n4242,5,802\n4343,4,0\n4444,6,0\n",
        "text.csv" to "101,102\n801,##0## got a ##1##-Star ##2##!\n802,Sir Test\n"))))

    private fun field(id: Int, tag: Int, bits: Long) = jobj("id" to id, "value" to jobj("tag" to tag, "bits" to bits))

    private fun item(uid: Long, template: Long, count: Long) = jobj("wire_values" to jarr(uid, template, count), "timed_flag" to 0)

    private fun current(): StateStore.Current {
        val state = jobj("role_properties" to jarr(field(3, 4, 20), field(6, 8, 1000)),
            "items" to jarr(item(501, 9101, 5), item(502, 9102, 3), item(503, 9103, 7), item(504, 9105, 9), item(505, 9104, 1)),
            "item_capacity_values" to jarr(50, 50, 50), "heroes" to JArr(), "subsystems" to JObj())
        return StateStore.Current(3, "", "", state, ByteArray(0), 0, null, JArr(), emptyList(), JArr(), emptyList(), null, null, null,
            null, null, LinkedHashMap())
    }

    private val emptyRewardJson = """"exp":0,"exploit":0,"gold":GOLD,"diamond":0,"stamina":0,"energy":0,"friend_point":0,"reputation":0,""" +
        """"arena_chance":0,"items":ITEMS,"heroes":[],"equips":[],"hero_grow":[],"equip_grow":[],"partner_friend_point":0,"vip_exp":0,""" +
        """"buffs":[],"gems":[],"courage":0,"hero_levels":[],"equip_levels":[],"double_charge_raw":0,"flag_17d_raw":0,"donation":0,""" +
        """"equip_grades":[],"jewels":[],"jewel_grow":[],"kind_door_score":0,"soul_hero":0,"soul_equip":0,"soul_jewel":0,"vip_pt":0"""

    private fun reward(gold: Long, items: String) = "{\"version\":14," + emptyRewardJson.replace("GOLD", gold.toString()).replace("ITEMS", items) + "}"

    private fun hex(packets: List<Frame>) = packets.map { it.first to it.second.toHexString() }

    private fun gold(owned: Owned) = owned.roleBits(6).toLong()

    @Test
    fun `a resource item pays its currency`() {
        val owned = Owned(current(), inputs)
        val plan = Acquisition.planUse(jobj("uid" to 501, "count" to 2), owned, inputs, roleLevel = 20, vipLevel = 0)
        assertEquals("""{"uid":501,"template":9101,"count":2,"family":"resource","resolved":{"family":"resource","code":90001,"amount":500},""" +
            """"draws":[],"reward":${reward(1000, "[]")},"evidence_class":"capture_observed_or_config_fixed","seed":null}""", Json.compact(plan.data))
        assertEquals(listOf(128 to "010608d007000000000000", 68 to "01f501000003000000", 70 to "0e000000000000000000000000000000e8030000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"), hex(plan.packets))
        assertEquals(2000L, gold(owned))
    }

    @Test
    fun `a random box draws per slot with the recorded seed`() {
        val owned = Owned(current(), inputs)
        val plan = Acquisition.planUse(jobj("uid" to 502, "count" to 3), owned, inputs, boxPolicy = JObj(), seed = BigInteger.valueOf(4242),
            roleLevel = 20, vipLevel = 0)
        assertEquals("""{"uid":502,"template":9102,"count":3,"family":"box","resolved":{"family":"box","group":77,"supported":true,"fixed":false,""" +
            """"excluded_rows":[]},"draws":[{"slot":1,"row":2,"kind":"gold","id":0,"count":40},{"slot":2,"row":3,"kind":"item","id":9106,"count":1},""" +
            """{"slot":3,"row":4,"kind":"nothing","id":0,"count":0},{"slot":1,"row":1,"kind":"item","id":9104,"count":2},""" +
            """{"slot":2,"row":3,"kind":"item","id":9106,"count":1},{"slot":3,"row":5,"kind":"item","id":9105,"count":3},""" +
            """{"slot":1,"row":2,"kind":"gold","id":0,"count":40},{"slot":2,"row":3,"kind":"item","id":9106,"count":1},""" +
            """{"slot":3,"row":5,"kind":"item","id":9105,"count":3}],"reward":${reward(80, "[[9106,3],[9104,2],[9105,6]]")},""" +
            """"evidence_class":"preservation_policy_box_draw","seed":4242}""", Json.compact(plan.data))
        assertEquals(listOf(64 to "01fa010000922300000300000000", 68 to "01f901000003000000", 68 to "01f80100000f000000",
            128 to "0106083804000000000000", 66 to "01f6010000"), hex(plan.packets).dropLast(1))
        assertEquals(1080L, gold(owned))
        // no policy: refused before any draw
        val refused = assertThrows(Acquisition.Rejected::class.java) {
            Acquisition.planUse(jobj("uid" to 502, "count" to 1), Owned(current(), inputs), inputs, roleLevel = 20, vipLevel = 0)
        }
        assertEquals("Random box without the labeled local box policy" to 2001, refused.message to refused.code)
    }

    @Test
    fun `merges and single merges`() {
        val owned = Owned(current(), inputs)
        val plan = Acquisition.planMerge(jobj("uid" to 503, "count" to 2), owned, inputs)
        assertEquals("""{"uid":503,"template":9103,"times":2,"recipe":88,"kind":"item","target":9106,"gold_cost":20,"substitute":null,""" +
            """"substitute_used":0,"currencies":[],"reward":${reward(0, "[[9106,2]]")},"evidence_class":"config_deterministic"}""", Json.compact(plan.data))
        assertEquals(listOf(64 to "01fa010000922300000200000000", 68 to "01f701000001000000", 68 to "01f801000007000000"), hex(plan.packets).take(3))
        assertEquals(820 to "02000e", hex(plan.packets)[3].let { it.first to it.second.take(6) })
        assertEquals(128 to "010608d403000000000000", hex(plan.packets)[4])
        val single = Acquisition.planMerge(Acquisition.decodeSingleMergeRequest(byteArrayOf(0xf7.toByte(), 1, 0, 0)), Owned(current(), inputs), inputs)
        assertEquals(818 to "000e", hex(single.packets)[3].let { it.first to it.second.take(4) })
        assertEquals(990L, single.data.long("gold_cost").let { 1000 - it })
        val refused = assertThrows(Acquisition.Rejected::class.java) { Acquisition.planMerge(jobj("uid" to 503, "count" to 51), Owned(current(), inputs), inputs) }
        assertEquals("Merge count outside 1..property 971", refused.message)
    }

    @Test
    fun `item refine merges the existing product stacks into one update`() {
        val owned = Owned(current(), inputs)
        val plan = Compose.planItemRefine(jarr(jarr(504, 3), jarr(505, 1)), owned, inputs)
        assertEquals("""{"entries":[[504,3],[505,1]],"refined":[{"uid":504,"template":9105,"count":3,"product":9106,"per_unit":4},""" +
            """{"uid":505,"template":9104,"count":1,"product":9101,"per_unit":2}],"products":{"9106":12,"9101":2},""" +
            """"reward":${reward(0, "[[9106,12],[9101,2]]")},"evidence_class":"config_candidate_capture_confirmed"}""", Json.compact(plan.data))
        assertEquals(listOf(68 to "01f801000006000000", 66 to "01f9010000", 68 to "01f501000007000000", 64 to "01fa010000922300000c00000000"),
            hex(plan.packets).dropLast(1))
    }

    @Test
    fun `box profile and draw`() {
        val rows = inputs.boxGroup(77)
        assertEquals("""{"slots":{"1":[1,2],"2":[3],"3":[4,5]},"supported":true,"fixed":false,"excluded_rows":[]}""", Json.compact(Acquisition.boxProfile(rows)))
        assertEquals(listOf(Triple(1L, 2L, Acquisition.Outcome("gold", 0, 40)), Triple(2L, 3L, Acquisition.Outcome("item", 9106, 1)),
            Triple(3L, 5L, Acquisition.Outcome("item", 9105, 3))), Acquisition.drawBox(rows, PyRandom.seeded(99)))
    }

    @Test
    fun `frame encoders`() {
        val reward = BattleRewardFixture.reward()
        assertEquals("03000e000000000000000000000000000000320000000000000000000000000000000000000000000000000000000000000001902300000200000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
            Acquisition.mergeResultPayload(3, reward).toHexString())
        assertEquals("000e00000000000000000000000000000032000000000000000000000000000000000000000000000000000000000000000190230000020000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
            Acquisition.singleMergeResultPayload(reward).toHexString())
        assertEquals("0251ba4000da4442000e000000000000000000000000000000320000000000000000000000000000000000000000000000000000000000000001902300000200000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
            Summon.lotResultPayload(listOf(4242001, 4343002), reward).toHexString())
        assertEquals("0202090703", Compose.luckPayload(jobj("7" to 3, "2" to 9)).toHexString())
        assertEquals("00", Compose.luckPayload(JObj()).toHexString())
        assertEquals("9210000001fbffffff", Compose.composeResultPayload(4242, 1, -5).toHexString())
        assertEquals("0202000000070000000400000032000000", Acquisition.buffsPayload(jobj("ends" to jobj("4" to 1050, "2" to 1007, "9" to 900)), 1000).toHexString())
    }

    @Test
    fun `request decoders`() {
        assertEquals("""{"uid":258,"count":3}""", Json.compact(Acquisition.decodeUseRequest(byteArrayOf(2, 1, 0, 0, 3, 0, 0, 0))))
        assertEquals("C73 is u32 uid, u32 count", assertThrows(Acquisition.Rejected::class.java) { Acquisition.decodeUseRequest(ByteArray(7)) }.message)
        assertEquals("""{"uid":1,"count":2,"option":5}""", Json.compact(Acquisition.decodeChooseRequest(byteArrayOf(1, 0, 0, 0, 2, 0, 0, 0, 5))))
        assertEquals("""{"uid":1,"count":513}""", Json.compact(Acquisition.decodeMergeRequest(byteArrayOf(1, 0, 0, 0, 1, 2))))
        assertEquals("""{"uid":7,"count":1,"single":true}""", Json.compact(Acquisition.decodeSingleMergeRequest(byteArrayOf(7, 0, 0, 0))))
        assertEquals("""{"lot":1,"mode":1,"ronghe":0}""", Json.compact(Summon.decodeSummonRequest(byteArrayOf(1, 1, 0))))
        assertEquals("""{"lot":2,"mode":0,"ronghe":null}""", Json.compact(Summon.decodeSummonRequest(byteArrayOf(2, 0))))
        assertEquals("The ronghe byte is only sent with lot-1 multi summons",
            assertThrows(Acquisition.Rejected::class.java) { Summon.decodeSummonRequest(byteArrayOf(2, 1, 0)) }.message)
        assertEquals("""{"uids":[5,6]}""", Json.compact(Summon.decodeRefineRequest(byteArrayOf(2, 5, 0, 0, 0, 6, 0, 0, 0))))
        assertEquals(listOf(9L), Compose.decodeUidList(byteArrayOf(1, 9, 0, 0, 0), "C2051"))
        assertEquals("C2633 is u8 n (>= 1), n x u32", assertThrows(Acquisition.Rejected::class.java) { Compose.decodeUidList(byteArrayOf(0), "C2633") }.message)
        assertEquals("""{"target":4,"materials":[]}""", Json.compact(Compose.decodeFuseRequest(byteArrayOf(4, 0, 0, 0, 0))))
        assertEquals("""{"entries":[[3,2]]}""", Json.compact(Compose.decodeItemRefineRequest(byteArrayOf(1, 0, 0, 0, 3, 0, 0, 0, 2, 0, 0, 0))))
        assertEquals("C2055 is u32 target, u8 n (>= 1), n x u32",
            assertThrows(Acquisition.Rejected::class.java) { Compose.decodeComposeRequest(byteArrayOf(4, 0, 0, 0, 0), "C2055") }.message)
    }

    @Test
    fun `summon report records and frames`() {
        val heroes = SummonReports.qualifying(listOf(4242001, 4343002, 4444003), inputs)
        val entries = SummonReports.record(null, 90000077, "Tëst".toByteArray(), heroes, 1234)
        assertEquals("""[{"role":90000077,"name_hex":"54c3ab7374","hero":4242001,"at":1234},{"role":90000077,"name_hex":"54c3ab7374","hero":4444003,"at":1234}]""",
            Json.compact(JArr(entries.toMutableList())))
        assertEquals(listOf(672 to "01cd4a5d0554c3ab73740051ba4000b0040000", 768 to "54c3ab737420676f74206120352d537461722053697220546573742100",
            672 to "01cd4a5d0554c3ab73740063cf4300b0040000", 768 to "54c3ab737420676f74206120362d5374617220343434343030332100"),
            hex(SummonReports.summonFrames(entries, inputs, -34)))
        val doc = jobj("entries" to JArr((0 until 7).mapTo(ArrayList()) { SummonReports.entry(1, "a".toByteArray(), 4242001, it.toLong()) }))
        assertEquals((1..6).map { it.toLong() } + listOf(1234L, 1234L), SummonReports.append(doc, entries).map { (it as JObj).long("at") })
    }

    private object BattleRewardFixture {
        fun reward(): JObj = Acquisition.emptyReward().also {
            it["items"] = jarr(jarr(9104, 2))
            it["gold"] = io.github.okexodus.openknights.exact.JInt(50)
        }
    }
}
