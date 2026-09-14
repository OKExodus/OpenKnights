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
import org.junit.jupiter.api.Assertions.assertTrue
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
            "9104,4,2,0,0,0,0,0,0,0,0,,1\n9105,5,2,0,0,0,0,0,0,0,0,,1\n9106,6,2,0,0,0,0,40,0,0,0,,1\n9107,7,2,0,0,0,0,0,0,0,0,,1\n",
        "box.csv" to "101,102,103,104,105,106,107,108,109,110,111,112,113,114,115,116,117,201,202,203\n" +
            "1,77,1,30,0,0,0,0,0,0,9104,2,0,0,0,0,0,0,0,0\n2,77,1,70,40,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0\n" +
            "3,77,2,100,0,0,0,0,0,0,9106,1,0,0,0,0,0,0,0,0\n4,77,3,5,0,0,0,0,0,0,9104,0,0,0,0,0,0,0,0,0\n" +
            "5,77,3,5,0,0,0,0,0,0,9105,3,0,0,0,0,0,0,0,0\n",
        "hecheng.csv" to "101,102,103,104,105,106,107,108,109,110,111,112,113,115,201,202,203,301,302,303,304\n" +
            "88,81000,9106,10,9103,3,9105,1,0,0,0,0,1,0,0,0,0,0,0,0,0\n",
        "item_rh.csv" to "101,102,103\n9105,9106,4\n9104,9101,2\n9107,20001,7\n",
        "property.csv" to "101,102\n970,100\n971,50\n533,2000\n902,4\n",
        "hero.csv" to "101,103,104,132,140,143,145,146,511,512,513,514,996,997\n4242,1,5,7,802,0,3000,1000,300,40,30,20,20,30\n" +
            "4343,1,4,7,0,0,0,0,10,10,10,10,0,0\n4444,1,6,7,0,0,0,0,10,10,10,10,0,0\n",
        "jinhua.csv" to "101,102,103,104,503\n1,5,1,7,500\n2,5,2,7,700\n3,5,3,7,900\n",
        "jinhuazhujue.csv" to "101,102,103,104,503\n",
        "herojuexing.csv" to "101\n",
        "herojuexingskill.csv" to "101\n",
        "herorh.csv" to "101,102,103,104,105,106,107,108,109,110,116,119,121,122\n51,5,0,9105,4,1,9107,1,2,6000,3,10000,9104,5\n",
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
        assertEquals("""{"target":4,"materials":[9]}""", Json.compact(Compose.decodeFuseRequest(byteArrayOf(4, 0, 0, 0, 1, 9, 0, 0, 0))))
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

    // --- operator fixes 2026-09-14 ---

    @Test
    fun `a fuse needs at least one material`() {
        val refused = assertThrows(Acquisition.Rejected::class.java) { Compose.decodeFuseRequest(byteArrayOf(4, 0, 0, 0, 0)) }
        assertEquals("C1249 is u32 target, u8 n (>= 1), n x u32" to 102, refused.message to refused.code)
    }

    @Test
    fun `a currency product of an item refine is a role grant after the item stacks`() {
        val cur = current()
        cur.state.arr("items").add(item(506, 9107, 4))
        val owned = Owned(cur, inputs)
        val plan = Compose.planItemRefine(jarr(jarr(506, 2), jarr(504, 1)), owned, inputs)
        assertEquals(REFINE_PLAN, Json.compact(plan.data))
        assertEquals(REFINE_PACKETS, hex(plan.packets))
        assertEquals(1014L, gold(owned))
    }

    @Test
    fun `a successful fuse keeps the hero's progress`() {
        val cur = current()
        cur.state["heroes"] = JArr((listOf(Json.loads(TARGET_FIELDS)) + (Json.loads(MATERIAL_FIELDS) as JArr)).toMutableList())
        cur.state["offline_hero_uids"] = jarr(601, 602, 603)
        cur.state.arr("items").add(item(507, 9107, 5))
        val owned = Owned(cur, inputs)
        val plan = Compose.planFuse(jobj("target" to 601, "materials" to jarr(602, 603)), owned, inputs, JObj(), BigInteger.valueOf(77))
        assertEquals(FUSE_PLAN, Json.compact(plan.data))
        assertEquals(FUSE_PACKETS, hex(plan.packets))
        assertEquals(HEROES_AFTER, Json.compact(owned.state.arr("heroes")))
        // a target the stat model does not reproduce is refused before the roll, nothing consumed
        val odd = current()
        val tampered = Json.loads(TARGET_FIELDS) as JArr
        tampered[4].let { (it as JObj).obj("value")["bits"] = io.github.okexodus.openknights.exact.JInt(12104) }
        odd.state["heroes"] = JArr((listOf<io.github.okexodus.openknights.exact.JValue>(tampered) + (Json.loads(MATERIAL_FIELDS) as JArr)).toMutableList())
        odd.state["offline_hero_uids"] = jarr(601, 602, 603)
        odd.state.arr("items").add(item(507, 9107, 5))
        val oddOwned = Owned(odd, inputs)
        val refused = assertThrows(Acquisition.Rejected::class.java) {
            Compose.planFuse(jobj("target" to 601, "materials" to jarr(602)), oddOwned, inputs, JObj(), BigInteger.ONE)
        }
        assertEquals(2001, refused.code)
        assertEquals(5L, oddOwned.item(507).count)
    }

    @Test
    fun `a missing free timer takes the new document's default`() {
        val cur = StateStore.Current(3, "", "", jobj("subsystems" to jobj("achievements" to jobj("count" to 0, "entries" to JArr()))),
            ByteArray(0), 0, null, JArr(), emptyList(), JArr(), emptyList(), null, null, null,
            jobj("document" to jobj("created_at_utc" to "2026-01-02T03:04:05+00:00")), null, LinkedHashMap())
        assertEquals("""{"free_next_epoch":{"3":5,"2":1767409445}}""",
            Json.compact(Summon.withDefaultTimers(jobj("free_next_epoch" to jobj("3" to 5)), cur, 1000)))
        val complete = jobj("free_next_epoch" to jobj("2" to 1, "3" to 2))
        assertTrue(Summon.withDefaultTimers(complete, cur, 1000) === complete)
    }

    private companion object {
        const val REFINE_PLAN = """{"entries":[[506,2],[504,1]],"refined":[{"uid":506,"template":9107,"count":2,"product":20001,"per_unit":7},{"uid":504,"template":9105,"count":1,"product":9106,"per_unit":4}],"products":{"20001":14,"9106":4},"reward":{"version":14,"exp":0,"exploit":0,"gold":0,"diamond":0,"stamina":0,"energy":0,"friend_point":0,"reputation":0,"arena_chance":0,"items":[[20001,14],[9106,4]],"heroes":[],"equips":[],"hero_grow":[],"equip_grow":[],"partner_friend_point":0,"vip_exp":0,"buffs":[],"gems":[],"courage":0,"hero_levels":[],"equip_levels":[],"double_charge_raw":0,"flag_17d_raw":0,"donation":0,"equip_grades":[],"jewels":[],"jewel_grow":[],"kind_door_score":0,"soul_hero":0,"soul_equip":0,"soul_jewel":0,"vip_pt":0},"evidence_class":"config_candidate_capture_confirmed"}"""
        val REFINE_PACKETS = listOf(68 to "01fa01000002000000", 68 to "01f801000008000000", 64 to "01fb010000922300000400000000", 128 to "010608f603000000000000", 3340 to "0e000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000002214e00000e000000922300000400000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000")
        const val TARGET_FIELDS = """[{"id":0,"value":{"tag":6,"bits":601}},{"id":1,"value":{"tag":6,"bits":4242003}},{"id":2,"value":{"tag":4,"bits":30}},{"id":3,"value":{"tag":6,"bits":555}},{"id":4,"value":{"tag":6,"bits":12103}},{"id":5,"value":{"tag":4,"bits":336}},{"id":6,"value":{"tag":6,"bits":1589}},{"id":7,"value":{"tag":4,"bits":44}},{"id":8,"value":{"tag":6,"bits":1191}},{"id":9,"value":{"tag":4,"bits":33}},{"id":10,"value":{"tag":6,"bits":793}},{"id":11,"value":{"tag":4,"bits":22}},{"id":12,"value":{"tag":6,"bits":1000}},{"id":13,"value":{"tag":6,"bits":0}},{"id":14,"value":{"tag":2,"bits":0}},{"id":15,"value":{"tag":6,"bits":7}},{"id":16,"value":{"tag":6,"bits":5}},{"id":17,"value":{"tag":6,"bits":3}},{"id":18,"value":{"tag":6,"bits":1}},{"id":19,"value":{"tag":5,"bits":1}},{"id":20,"value":{"tag":5,"bits":0}},{"id":21,"value":{"tag":5,"bits":1}},{"id":22,"value":{"tag":6,"bits":111}},{"id":23,"value":{"tag":6,"bits":222}},{"id":24,"value":{"tag":5,"bits":0}}]"""
        const val MATERIAL_FIELDS = """[[{"id":0,"value":{"tag":6,"bits":602}},{"id":1,"value":{"tag":6,"bits":4242001}},{"id":2,"value":{"tag":4,"bits":1}},{"id":3,"value":{"tag":6,"bits":0}},{"id":4,"value":{"tag":6,"bits":1500}},{"id":5,"value":{"tag":4,"bits":300}},{"id":6,"value":{"tag":6,"bits":200}},{"id":7,"value":{"tag":4,"bits":40}},{"id":8,"value":{"tag":6,"bits":150}},{"id":9,"value":{"tag":4,"bits":30}},{"id":10,"value":{"tag":6,"bits":100}},{"id":11,"value":{"tag":4,"bits":20}},{"id":12,"value":{"tag":6,"bits":1000}},{"id":13,"value":{"tag":6,"bits":0}},{"id":14,"value":{"tag":2,"bits":0}},{"id":15,"value":{"tag":6,"bits":0}},{"id":16,"value":{"tag":6,"bits":0}},{"id":17,"value":{"tag":6,"bits":0}},{"id":18,"value":{"tag":6,"bits":0}},{"id":19,"value":{"tag":5,"bits":0}},{"id":20,"value":{"tag":5,"bits":0}},{"id":21,"value":{"tag":5,"bits":0}},{"id":22,"value":{"tag":6,"bits":90}},{"id":23,"value":{"tag":6,"bits":60}},{"id":24,"value":{"tag":5,"bits":0}}],[{"id":0,"value":{"tag":6,"bits":603}},{"id":1,"value":{"tag":6,"bits":4242001}},{"id":2,"value":{"tag":4,"bits":1}},{"id":3,"value":{"tag":6,"bits":0}},{"id":4,"value":{"tag":6,"bits":1500}},{"id":5,"value":{"tag":4,"bits":300}},{"id":6,"value":{"tag":6,"bits":200}},{"id":7,"value":{"tag":4,"bits":40}},{"id":8,"value":{"tag":6,"bits":150}},{"id":9,"value":{"tag":4,"bits":30}},{"id":10,"value":{"tag":6,"bits":100}},{"id":11,"value":{"tag":4,"bits":20}},{"id":12,"value":{"tag":6,"bits":1000}},{"id":13,"value":{"tag":6,"bits":0}},{"id":14,"value":{"tag":2,"bits":0}},{"id":15,"value":{"tag":6,"bits":0}},{"id":16,"value":{"tag":6,"bits":0}},{"id":17,"value":{"tag":6,"bits":0}},{"id":18,"value":{"tag":6,"bits":0}},{"id":19,"value":{"tag":5,"bits":0}},{"id":20,"value":{"tag":5,"bits":0}},{"id":21,"value":{"tag":5,"bits":0}},{"id":22,"value":{"tag":6,"bits":90}},{"id":23,"value":{"tag":6,"bits":60}},{"id":24,"value":{"tag":5,"bits":0}}]]"""
        const val FUSE_PLAN = """{"target":601,"materials":[602,603],"template_before":4242003,"template_after":4242103,"fields_changed":[1,4,6,8,10],"rate":12000,"luck_before":0,"success":true,"consumed":[602,603],"luck_after":{"5":0},"seed":77,"reward":{"version":14,"exp":0,"exploit":0,"gold":0,"diamond":0,"stamina":0,"energy":0,"friend_point":0,"reputation":0,"arena_chance":0,"items":[],"heroes":[],"equips":[],"hero_grow":[[601,0,0,336,44,33,22,0,0,0,0]],"equip_grow":[],"partner_friend_point":0,"vip_exp":0,"buffs":[],"gems":[],"courage":0,"hero_levels":[],"equip_levels":[],"double_charge_raw":0,"flag_17d_raw":0,"donation":0,"equip_grades":[],"jewels":[],"jewel_grow":[],"kind_door_score":0,"soul_hero":0,"soul_equip":0,"soul_jewel":0,"vip_pt":0},"evidence_class":"preservation_policy_fuse_roll"}"""
        val FUSE_PACKETS = listOf(68 to "01fb01000003000000", 1568 to "0159020000190006590200000106b7ba400002041e0003062b0200000406ba3800000504500106067107000007042c00080694050000090421000a06b70300000b0416000c06e80300000d06000000000e02000f060700000010060500000011060300000012060100000013050100000014050000000015050100000016066f0000001706de0000001805000000000e000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001590200000000000000000000500100002c0000002100000016000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000073697a653d322c20726174653d313230303000", 40 to "025a0200005b020000", 34 to "025a0200005b020000", 1572 to "010500")
        const val HEROES_AFTER = """[[{"id":0,"value":{"tag":6,"bits":601}},{"id":1,"value":{"tag":6,"bits":4242103}},{"id":2,"value":{"tag":4,"bits":30}},{"id":3,"value":{"tag":6,"bits":555}},{"id":4,"value":{"tag":6,"bits":14522}},{"id":5,"value":{"tag":4,"bits":336}},{"id":6,"value":{"tag":6,"bits":1905}},{"id":7,"value":{"tag":4,"bits":44}},{"id":8,"value":{"tag":6,"bits":1428}},{"id":9,"value":{"tag":4,"bits":33}},{"id":10,"value":{"tag":6,"bits":951}},{"id":11,"value":{"tag":4,"bits":22}},{"id":12,"value":{"tag":6,"bits":1000}},{"id":13,"value":{"tag":6,"bits":0}},{"id":14,"value":{"tag":2,"bits":0}},{"id":15,"value":{"tag":6,"bits":7}},{"id":16,"value":{"tag":6,"bits":5}},{"id":17,"value":{"tag":6,"bits":3}},{"id":18,"value":{"tag":6,"bits":1}},{"id":19,"value":{"tag":5,"bits":1}},{"id":20,"value":{"tag":5,"bits":0}},{"id":21,"value":{"tag":5,"bits":1}},{"id":22,"value":{"tag":6,"bits":111}},{"id":23,"value":{"tag":6,"bits":222}},{"id":24,"value":{"tag":5,"bits":0}}]]"""
    }

    private object BattleRewardFixture {
        fun reward(): JObj = Acquisition.emptyReward().also {
            it["items"] = jarr(jarr(9104, 2))
            it["gold"] = io.github.okexodus.openknights.exact.JInt(50)
        }
    }
}
