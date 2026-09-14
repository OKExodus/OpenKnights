package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JNull
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.hexBytes
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.jvalue
import io.github.okexodus.openknights.exact.toHexString
import io.github.okexodus.openknights.gamedata.GameTables
import io.github.okexodus.openknights.gamedata.TableSource
import io.github.okexodus.openknights.protocol.WireWriter
import io.github.okexodus.openknights.server.DeviceClock
import io.github.okexodus.openknights.server.store.StateStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigInteger

/**
 * CI checks of the quests slice on made-up tables and saves: each expected value is the reference's output for the same
 * made-up input. The full proof against the recorded saves and the APK tables is [G5QuestsVectorsTest] (local).
 */
class G5QuestsMadeUpTest {
    private class TextTables(private val files: Map<String, String>) : TableSource {
        override fun names() = files.keys.sorted()
        override fun raw(name: String): ByteArray {
            val file = if (name.endsWith(".csv")) name else "$name.csv"
            return (files[file] ?: throw NoSuchElementException("Missing table: $file")).toByteArray()
        }
    }

    private val inputs = DailyInputs(GameTables(TextTables(mapOf(
        "quest.csv" to "101,106,108,109,110,111,113,114,115,116,117,118,119,120,121,124,125\n" +
            "9001,1,0,23,0,5,0,500,70,3,1,9501,2,0,1,10,\n9002,1,3,23,0,50,0,0,0,0,0,0,0,9001,1,4,\n" +
            "9003,2,0,12,0,3,0,100,40,0,0,0,0,9001,1,7,\n9004,1,9,23,0,9,0,0,0,0,0,0,0,9001,1,1,\n" +
            "700101,2,0,0,0,0,0,1000,300,20,0,0,0,0,,50,\n700102,2,0,0,0,0,0,1000,300,20,0,0,0,0,,50,\n700103,2,0,0,0,0,0,1000,300,20,0,0,0,0,,50,\n700104,2,0,0,0,0,0,1000,300,20,0,0,0,0,,50,\n700105,2,0,0,0,0,0,1000,300,20,0,0,0,0,,50,\n700106,2,0,0,0,0,0,1000,300,20,0,0,0,0,,50,\n700107,2,0,0,0,0,0,1000,300,20,0,0,0,0,,50,\n700108,2,0,0,0,0,0,1000,300,20,0,0,0,0,,50,\n700109,2,0,0,0,0,0,1000,300,20,0,0,0,0,,50,\n700110,2,0,0,0,0,0,1000,300,20,0,0,0,0,,50,\n700111,2,0,0,0,0,0,1000,300,20,0,0,0,0,,50,\n700112,2,0,0,0,0,0,1000,300,20,0,0,0,0,,50,\n700113,2,0,0,0,0,0,1000,300,20,0,0,0,0,,50,\n700114,2,0,0,0,0,0,1000,300,20,0,0,0,0,,50,\n700115,2,0,0,0,0,0,1000,300,20,0,0,0,0,,50,\n",
        "quest_bounty.csv" to "101,102,103,104,105,106,107,108,109\n1,1,50,0,0,0,0,300,5\n2,2,40,2000,2000,2000,2000,360,6\n" +
            "3,3,30,5000,5000,5000,5000,480,8\n4,4,20,10000,10000,10000,10000,720,12\n5,5,10,20000,20000,20000,20000,900,15\n",
        "property.csv" to "101,102\n517,4000\n9001,10\n300103,10\n",
        "roleexp.csv" to "101,102\n1,100\n2,200\n3,300\n4,400\n5,500\n6,600\n",
        "item.csv" to "101,102,104,105,106,107,110,203,204,205,206,207,305\n9501,1,2,0,0,0,0,0,0,0,0,,1\n",
        "xiuxing.csv" to "101,102,201,203,204,301,302,303,304,401,501,502,503,601,602\n" +
            "401,0,6,0,0,10000,0,0,-1,28800,0,1,999,20000,2500\n402,1,6,3,10,11500,500,100,28800,28800,2,20,60,40000,5000\n",
        "title.csv" to "101,201,202,203\n7,100,20,30\n",
        "mubiao.csv" to "101,102,103,104\n1,11,0,1\n",
        "mubiaoquest.csv" to "101,102,103,104,105,106,107,108,109,110,111,112,113,114,115,116,117,118,119,120\n" +
            "5101,1,1,16,1,0,1,1,20003,5,1,9501,2,0,0,0,0,0,0,1\n5102,2,1,1,3,0,1,1,20001,50,1,20002,6,0,0,0,0,0,0,2\n"))))

    private val now = 1_900_000_000L

    @BeforeEach
    fun clock() {
        DeviceClock.active = DeviceClock(null, timeSource = { 0L }, offsetSource = { 0 })
    }

    @AfterEach
    fun reset() {
        DeviceClock.active = null
    }

    private fun field(id: Long, bits: Long, tag: Int = 4) = jobj("id" to id, "value" to jobj("tag" to tag, "bits" to bits))

    private fun state(): JObj = jobj(
        "role_properties" to jarr(field(0, 4242), jobj("id" to 2, "value" to jobj("tag" to 12, "raw_hex" to "416e6e")), field(3, 2),
            field(4, 150), field(6, 1000, 8), field(7, 0), field(8, 30), field(22, 7)),
        "items" to JArr(), "item_capacity_values" to jarr(50, 50, 50), "equipment" to JArr(), "bag_equipment_uids" to JArr(),
        "heroes" to jarr(jarr(field(0, 1), field(1, 7001), field(2, 12), field(4, 900), field(6, 300), field(8, 150), field(10, 50),
            field(22, 5), field(23, 7))),
        "formation" to jarr(jobj("slot_id" to 1, "hero_uid" to 1, "assignments" to JArr(), "groups" to JArr())), "captain_slot" to 1,
        "subsystems" to jobj("achievements" to jobj("entries" to jarr(jobj("wire_values" to jarr(1, 1, 2)), jobj("wire_values" to jarr(4, 1, 0)),
            jobj("wire_values" to jarr(5, 1, 2)), jobj("wire_values" to jarr(18, 2, 40)))),
            "game_activities" to jobj("first_list" to jobj("count" to 0, "entries" to JArr()))))

    private fun current(documents: Map<String, JObj?> = emptyMap()): StateStore.Current =
        StateStore.Current(3, "", "", state(), ByteArray(0), 0, null, JArr(), emptyList(), JArr(), emptyList(), null, null, null, null, null,
            LinkedHashMap<String, JValue?>(documents))

    /** The reference's outputs for the same made-up inputs (plan, frames, role values after; or the refusal). */
    private val expected: Map<String, String> = mapOf(
        "story PLAN" to "{\"quest\":9001,\"points\":10,\"quest_state_after\":{\"profile\":\"quest_state_v1\",\"quests\":[[9003,2,1],[9002,2,0]],\"points\":15,\"seed\":null,\"claimed\":[9001]},\"evidence_class\":\"capture_observed_csv_calculation\"}",
        "story FRAMES" to "[[128, \"020304040004049600\"], [578, \"010104000000\"], [128, \"0206082e0400000000000007040300\"], [64, \"01010000001d2500000200000000\"], [324, \"292300000e000000f401000000000000030000004600000000000000000000000000000000000000000000000000000000000000011d2500000200000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000\"], [320, \"022a23000002000000002b23000002010000000f000000\"], [578, \"040101000000\"]]",
        "story ROLES" to "{\"4\": 150, \"3\": 4, \"6\": 1070, \"7\": 3}",
        "story_old PLAN" to "{\"quest\":9001,\"points\":10,\"quest_state_after\":{\"profile\":\"quest_state_v1\",\"quests\":[[9003,2,1],[9002,2,0]],\"points\":15,\"seed\":null},\"evidence_class\":\"capture_observed_csv_calculation\"}",
        "story_old FRAMES" to "[[128, \"020304040004049600\"], [578, \"010104000000\"], [128, \"0206082e0400000000000007040300\"], [64, \"01010000001d2500000200000000\"], [324, \"292300000e000000f401000000000000030000004600000000000000000000000000000000000000000000000000000000000000011d2500000200000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000\"], [320, \"022a23000002000000002b23000002010000000f000000\"], [578, \"040101000000\"]]",
        "story_old ROLES" to "{\"4\": 150, \"3\": 4, \"6\": 1070, \"7\": 3}",
        "story_running REFUSED" to "[\"The quest is not claimable\", 70107]",
        "accept PLAN" to "{\"opcode\":259,\"quest\":700105,\"bounty_board_after\":{\"profile\":\"bounty_board_v1\",\"day\":\"2030-03-17\",\"rows\":[[700101,2,0,2],[700102,2,0,3],[700103,3,0,4],[700104,4,0,5],[700105,2,0,1],[700106,1,0,2],[700107,1,0,3],[700108,1,0,4],[700109,1,0,5],[700110,1,0,1],[700111,1,0,2],[700112,1,0,3],[700113,1,0,4],[700114,1,0,5],[700115,1,0,1]],\"used\":1,\"limit\":25,\"board_until\":1900000600,\"auto_id\":0,\"auto_until\":0,\"free\":1},\"evidence_class\":\"capture_observed\"}",
        "accept FRAMES" to "[[326, \"c9ae0a000200000000010100000019000000000000000000000001000000\"]]",
        "accept ROLES" to "{}",
        "accept_running REFUSED" to "[\"Not an available bounty quest\", 11000]",
        "accept_limit REFUSED" to "[\"Bounty Quest limit reached today\", 11003]",
        "quit PLAN" to "{\"opcode\":263,\"quest\":700101,\"bounty_board_after\":{\"profile\":\"bounty_board_v1\",\"day\":\"2030-03-17\",\"rows\":[[700101,1,0,2],[700102,2,0,3],[700103,3,0,4],[700104,4,0,5],[700105,1,0,1],[700106,1,0,2],[700107,1,0,3],[700108,1,0,4],[700109,1,0,5],[700110,1,0,1],[700111,1,0,2],[700112,1,0,3],[700113,1,0,4],[700114,1,0,5],[700115,1,0,1]],\"used\":0,\"limit\":25,\"board_until\":1900000600,\"auto_id\":0,\"auto_until\":0,\"free\":1},\"evidence_class\":\"native_use_policy\"}",
        "quit FRAMES" to "[[326, \"c5ae0a000100000000020000000019000000000000000000000001000000\"]]",
        "quit ROLES" to "{}",
        "stars_free PLAN" to "{\"opcode\":271,\"quest\":700102,\"bounty_board_after\":{\"profile\":\"bounty_board_v1\",\"day\":\"2030-03-17\",\"rows\":[[700101,2,0,2],[700102,2,0,2],[700103,3,0,4],[700104,4,0,5],[700105,1,0,1],[700106,1,0,2],[700107,1,0,3],[700108,1,0,4],[700109,1,0,5],[700110,1,0,1],[700111,1,0,2],[700112,1,0,3],[700113,1,0,4],[700114,1,0,5],[700115,1,0,1]],\"used\":0,\"limit\":25,\"board_until\":1900000600,\"auto_id\":0,\"auto_until\":0,\"free\":0},\"evidence_class\":\"capture_observed\"}",
        "stars_free FRAMES" to "[[326, \"c6ae0a000200000000020000000019000000000000000000000000000000\"]]",
        "stars_free ROLES" to "{}",
        "stars_paid PLAN" to "{\"opcode\":271,\"quest\":700102,\"bounty_board_after\":{\"profile\":\"bounty_board_v1\",\"day\":\"2030-03-17\",\"rows\":[[700101,2,0,2],[700102,2,0,2],[700103,3,0,4],[700104,4,0,5],[700105,1,0,1],[700106,1,0,2],[700107,1,0,3],[700108,1,0,4],[700109,1,0,5],[700110,1,0,1],[700111,1,0,2],[700112,1,0,3],[700113,1,0,4],[700114,1,0,5],[700115,1,0,1]],\"used\":0,\"limit\":25,\"board_until\":1900000600,\"auto_id\":0,\"auto_until\":0,\"free\":0},\"evidence_class\":\"capture_observed\"}",
        "stars_paid FRAMES" to "[[326, \"c6ae0a000200000000020000000019000000000000000000000000000000\"], [578, \"120232000000\"], [128, \"0108041400\"]]",
        "stars_paid ROLES" to "{\"8\": 20}",
        "stars_ready REFUSED" to "[\"Stars cannot be refreshed now\", 70109]",
        "auto PLAN" to "{\"opcode\":273,\"quest\":700101,\"bounty_board_after\":{\"profile\":\"bounty_board_v1\",\"day\":\"2030-03-17\",\"rows\":[[700101,2,0,2],[700102,2,0,3],[700103,3,0,4],[700104,4,0,5],[700105,1,0,1],[700106,1,0,2],[700107,1,0,3],[700108,1,0,4],[700109,1,0,5],[700110,1,0,1],[700111,1,0,2],[700112,1,0,3],[700113,1,0,4],[700114,1,0,5],[700115,1,0,1]],\"used\":0,\"limit\":25,\"board_until\":1900000600,\"auto_id\":700101,\"auto_until\":1900000360,\"free\":1},\"evidence_class\":\"capture_observed\"}",
        "auto FRAMES" to "[[326, \"c5ae0a000200000000020000000019000000c5ae0a006801000001000000\"]]",
        "auto ROLES" to "{}",
        "auto_busy REFUSED" to "[\"Another bounty quest is auto completing\", 70102]",
        "timer PLAN" to "{\"opcode\":275,\"quest\":700101,\"bounty_board_after\":{\"profile\":\"bounty_board_v1\",\"day\":\"2030-03-17\",\"rows\":[[700101,3,0,2],[700102,2,0,3],[700103,3,0,4],[700104,4,0,5],[700105,1,0,1],[700106,1,0,2],[700107,1,0,3],[700108,1,0,4],[700109,1,0,5],[700110,1,0,1],[700111,1,0,2],[700112,1,0,3],[700113,1,0,4],[700114,1,0,5],[700115,1,0,1]],\"used\":0,\"limit\":25,\"board_until\":1900000600,\"auto_id\":0,\"auto_until\":0,\"free\":1},\"evidence_class\":\"native_use_policy\"}",
        "timer FRAMES" to "[[326, \"c5ae0a000300000000020000000019000000000000000000000001000000\"]]",
        "timer ROLES" to "{}",
        "timer_early REFUSED" to "[\"No finished auto completion\", 70104]",
        "claim PLAN" to "{\"opcode\":261,\"quest\":700103,\"points\":100,\"bounty_board_after\":{\"profile\":\"bounty_board_v1\",\"day\":\"2030-03-17\",\"rows\":[[700101,2,0,2],[700102,2,0,3],[700103,4,0,4],[700104,4,0,5],[700105,1,0,1],[700106,1,0,2],[700107,1,0,3],[700108,1,0,4],[700109,1,0,5],[700110,1,0,1],[700111,1,0,2],[700112,1,0,3],[700113,1,0,4],[700114,1,0,5],[700115,1,0,1]],\"used\":0,\"limit\":25,\"board_until\":1900000600,\"auto_id\":0,\"auto_until\":0,\"free\":1},\"quest_state_after\":{\"profile\":\"quest_state_v1\",\"quests\":[[9001,3,0],[9003,2,1]],\"points\":105,\"seed\":null,\"claimed\":[]},\"evidence_class\":\"native_use_policy\"}",
        "claim FRAMES" to "[[128, \"020304060004045e01\"], [578, \"010106000000\"], [128, \"020608c80500000000000007042000\"], [324, \"c7ae0a000e000000400600000000000020000000e0010000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000\"], [326, \"c7ae0a000400000000040000000019000000000000000000000001000000\"], [320, \"022923000003000000002b230000020100000069000000\"], [578, \"050103000000\"]]",
        "claim ROLES" to "{\"4\": 350, \"3\": 6, \"6\": 1480, \"7\": 32}",
        "claim_running REFUSED" to "[\"The quest is not claimable\", 70107]",
        "expedite PLAN" to "{\"opcode\":269,\"quest\":700101,\"points\":60,\"bounty_board_after\":{\"profile\":\"bounty_board_v1\",\"day\":\"2030-03-17\",\"rows\":[[700101,4,0,2],[700102,2,0,3],[700103,3,0,4],[700104,4,0,5],[700105,1,0,1],[700106,1,0,2],[700107,1,0,3],[700108,1,0,4],[700109,1,0,5],[700110,1,0,1],[700111,1,0,2],[700112,1,0,3],[700113,1,0,4],[700114,1,0,5],[700115,1,0,1]],\"used\":0,\"limit\":25,\"board_until\":1900000600,\"auto_id\":0,\"auto_until\":0,\"free\":1},\"quest_state_after\":{\"profile\":\"quest_state_v1\",\"quests\":[[9001,3,0],[9003,2,1]],\"points\":65,\"seed\":null,\"claimed\":[]},\"evidence_class\":\"capture_observed_csv_calculation\"}",
        "expedite FRAMES" to "[[128, \"02030405000404d200\"], [578, \"010105000000\"], [128, \"020608080500000000000007041300\"], [324, \"c5ae0a000e000000c0030000000000001300000020010000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000\"], [326, \"c5ae0a000400000000020000000019000000000000000000000001000000\"], [320, \"022923000003000000002b230000020100000041000000\"], [578, \"12022b000000\"], [128, \"0108041b00\"], [578, \"050103000000\"]]",
        "expedite ROLES" to "{\"4\": 210, \"3\": 5, \"6\": 1288, \"7\": 19, \"8\": 27}",
        "expedite_poor REFUSED" to "[\"Not enough Diamonds\", 4000]",
        "expedite_other REFUSED" to "[\"This bounty quest is not auto completing\", 70104]",
        "refresh_paid PLAN" to "{\"opcode\":265,\"bounty_board_after\":{\"profile\":\"bounty_board_v1\",\"day\":\"2030-03-17\",\"rows\":[[700101,1,0,1],[700102,1,0,5],[700103,1,0,3],[700104,1,0,2],[700105,1,0,1],[700106,1,0,1],[700107,1,0,1],[700108,1,0,3],[700109,1,0,1],[700110,1,0,1],[700111,1,0,1],[700112,1,0,4],[700113,1,0,3],[700114,1,0,2],[700115,1,0,3]],\"used\":0,\"limit\":25,\"board_until\":1900086400,\"auto_id\":0,\"auto_until\":0,\"free\":1},\"evidence_class\":\"native_use_policy\"}",
        "refresh_paid FRAMES" to "[[322, \"0fc5ae0a00010000000001c6ae0a00010000000005c7ae0a00010000000003c8ae0a00010000000002c9ae0a00010000000001caae0a00010000000001cbae0a00010000000001ccae0a00010000000003cdae0a00010000000001ceae0a00010000000001cfae0a00010000000001d0ae0a00010000000004d1ae0a00010000000003d2ae0a00010000000002d3ae0a00010000000003000000001900000080510100000000000000000001000000\"], [578, \"120232000000\"], [128, \"0108041400\"]]",
        "refresh_paid ROLES" to "{\"8\": 20}",
        "refresh_free PLAN" to "{\"opcode\":265,\"bounty_board_after\":{\"profile\":\"bounty_board_v1\",\"day\":\"2030-03-17\",\"rows\":[[700101,1,0,1],[700102,1,0,5],[700103,1,0,3],[700104,1,0,2],[700105,1,0,1],[700106,1,0,1],[700107,1,0,1],[700108,1,0,3],[700109,1,0,1],[700110,1,0,1],[700111,1,0,1],[700112,1,0,4],[700113,1,0,3],[700114,1,0,2],[700115,1,0,3]],\"used\":7,\"limit\":25,\"board_until\":1900086400,\"auto_id\":0,\"auto_until\":0,\"free\":1},\"evidence_class\":\"native_use_policy\"}",
        "refresh_free FRAMES" to "[[322, \"0fc5ae0a00010000000001c6ae0a00010000000005c7ae0a00010000000003c8ae0a00010000000002c9ae0a00010000000001caae0a00010000000001cbae0a00010000000001ccae0a00010000000003cdae0a00010000000001ceae0a00010000000001cfae0a00010000000001d0ae0a00010000000004d1ae0a00010000000003d2ae0a00010000000002d3ae0a00010000000003070000001900000080510100000000000000000001000000\"]]",
        "refresh_free ROLES" to "{}",
        "missing REFUSED" to "[\"Cannot find related bounty quest\", 70100]",
        "refresh_auto REFUSED" to "[\"There are still bounty quests being auto completing, please wait.\", 70102]",
        "stars_auto REFUSED" to "[\"Stars cannot be refreshed now\", 70109]",
        "timer_none REFUSED" to "[\"No finished auto completion\", 70104]",
        "story_old_low PLAN" to "{\"quest\":9001,\"points\":10,\"quest_state_after\":{\"profile\":\"quest_state_v1\",\"quests\":[[9002,2,0],[9003,2,0]],\"points\":15,\"seed\":null},\"evidence_class\":\"capture_observed_csv_calculation\"}",
        "story_old_low FRAMES" to "[[128, \"020304040004049600\"], [578, \"010104000000\"], [128, \"0206082e0400000000000007040300\"], [64, \"01010000001d2500000200000000\"], [324, \"292300000e000000f401000000000000030000004600000000000000000000000000000000000000000000000000000000000000011d2500000200000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000\"], [320, \"022a23000002000000002b23000002000000000f000000\"], [578, \"040101000000\"]]",
        "story_old_low ROLES" to "{\"4\": 150, \"3\": 4, \"6\": 1070, \"7\": 3}",
        "goal PLAN" to "{\"goal\":5101,\"kind\":16,\"rewards\":[[1,20003,5],[1,9501,2]],\"reward\":{\"version\":14,\"exp\":0,\"exploit\":0,\"gold\":0,\"diamond\":5,\"stamina\":0,\"energy\":0,\"friend_point\":0,\"reputation\":0,\"arena_chance\":0,\"items\":[[9501,2]],\"heroes\":[],\"equips\":[],\"hero_grow\":[],\"equip_grow\":[],\"partner_friend_point\":0,\"vip_exp\":0,\"buffs\":[],\"gems\":[],\"courage\":0,\"hero_levels\":[],\"equip_levels\":[],\"double_charge_raw\":0,\"flag_17d_raw\":0,\"donation\":0,\"equip_grades\":[],\"jewels\":[],\"jewel_grow\":[],\"kind_door_score\":0,\"soul_hero\":0,\"soul_equip\":0,\"soul_jewel\":0,\"vip_pt\":0},\"goal_state_after\":{\"profile\":\"goal_state_v1\",\"rows\":[[5101,4,1],[5102,2,1]],\"start_day\":\"2030-03-17\",\"level_seen\":2,\"seed\":null},\"evidence_class\":\"native_use_load_rewards_candidate_order\"}",
        "goal FRAMES" to "[[64, \"01010000001d2500000200000000\"], [128, \"0108042300\"], [3104, \"0e0000000000000000000000000000000000000000000000050000000000000000000000000000000000000000000000011d2500000200000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000\"], [3106, \"ed1300000401000000\"]]",
        "goal ROLES" to "{\"8\": 35}",
        "goal_running REFUSED" to "[\"The goal is not claimable\", 70107]",
        "goal_missing REFUSED" to "[\"The goal is not on the list\", 11004]",
        "goal_off REFUSED" to "[\"Goals are not open for this character\", 11004]",
        "enter_now FRAMES" to "[[2114, \"010000009101000000000000004f70000000010292100000416e6e00591b0000\"]]",
        "enter_now_free FRAMES" to "[[2114, \"01000000910100000000000000000000000000\"]]",
        "enter_own REFUSED" to "[\"Your level is too low\", 38004]",
        "enter_missing REFUSED" to "[\"Training room does not exist\", 38000]",
        "kick_default REFUSED" to "[\"Only room owner can use this function\", 38006]",
        "kick_own FRAMES" to "[[2114, \"0200000092010000416e6e00f4010000000000006162630000\"]]",
        "preview FRAMES" to "[[2116, \"0e000000120000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000004f700000\"]]",
        "preview_none REFUSED" to "[\"Training hasn't started or was claimed\", 38007]",
    )

    private fun framesText(frames: List<Frame>) = "[" + frames.joinToString(", ") { "[${it.first}, \"${it.second.toHexString()}\"]" } + "]"

    private fun rolesText(owned: Owned) = "{" + owned.roleChanges.entries.joinToString(", ") { (f, c) -> "\"$f\": ${c.second}" } + "}"

    private fun refusal(e: Acquisition.Rejected) = "[${Json.dumps(JStr(e.message!!))}, ${e.code}]"

    /** Run one planner on a fresh made-up save and compare plan, frames and role changes (or the refusal) with the reference's. */
    private fun show(name: String, documents: Map<String, JObj?> = emptyMap(), planner: (Owned, StateStore.Current) -> Plan) {
        val cur = current(documents)
        val owned = Owned(cur, inputs)
        val plan = try {
            planner(owned, cur)
        } catch (e: Acquisition.Rejected) {
            assertEquals(expected.getValue("$name REFUSED"), refusal(e), name)
            return
        }
        assertEquals(expected.getValue("$name PLAN"), Json.compact(plan.data), "$name plan")
        assertEquals(expected.getValue("$name FRAMES"), framesText(plan.packets), "$name frames")
        assertEquals(expected.getValue("$name ROLES"), rolesText(owned), "$name roles")
    }

    private val quests = jobj("profile" to Quests.QUEST_PROFILE, "quests" to jarr(jarr(9001, 3, 0), jarr(9003, 2, 1)), "points" to 5,
        "seed" to JNull, "claimed" to JArr())

    private fun rows(): JArr {
        val rows = JArr((700101L..700115L).mapTo(ArrayList()) { jarr(it, 1, 0, 1 + it % 5) })
        for ((i, s) in listOf(2, 2, 3, 4).withIndex()) (rows[i] as JArr)[1] = JInt(s)
        return rows
    }

    private fun board(vararg changes: Pair<String, Any?>): JObj {
        val doc = jobj("profile" to Quests.BOARD_PROFILE, "day" to "2030-03-17", "rows" to rows(), "used" to 0, "limit" to 25,
            "board_until" to now + 600, "auto_id" to 0, "auto_until" to 0, "free" to 1)
        for ((k, v) in changes) doc[k] = jvalue(v)
        return doc
    }

    private fun bounty(name: String, opcode: Int, quest: Long?, doc: JObj) = show(name) { owned, _ ->
        Quests.planBounty(opcode, if (quest == null) JObj() else jobj("quest" to quest), owned, inputs, quests.deepCopy(), doc.deepCopy(), now,
            "made-up", serverTime = now)
    }

    @Test
    fun `decoders`() {
        assertEquals("""{"quest":4096}""", Json.compact(Quests.decodeTask("00100000".hexBytes(), 259)))
        val short = assertThrows(Acquisition.Rejected::class.java) { Quests.decodeTask("0010".hexBytes(), 271) }
        assertEquals("C271 is u32 quest id" to 102, short.message to short.code)
        assertEquals(4096L, Goals.decodeClaim("00100000".hexBytes()))
        val claim = assertThrows(Acquisition.Rejected::class.java) { Goals.decodeClaim(ByteArray(5)) }
        assertEquals("C2657 carries one u32 goal id" to 102, claim.message to claim.code)
        val secret = HiddenTraining.decodeRoomPassword("0200000061626300".hexBytes(), 1765)
        assertEquals(2L to "616263", secret.room.value.toLong() to secret.password.toHexString())
        for (bad in listOf("02000000", "0200000061", "020000006100620000")) {
            val e = assertThrows(Acquisition.Rejected::class.java) { HiddenTraining.decodeRoomPassword(bad.hexBytes(), 1769) }
            assertEquals("C1769 is u32 room + cstring", e.message)
        }
    }

    @Test
    fun `story claims level up, grant the item and unlock the next quests`() {
        show("story") { owned, _ -> Quests.planStoryClaim(jobj("quest" to 9001), owned, inputs, quests.deepCopy(), 2) }
        val old = quests.deepCopy().also { it.remove("claimed") }
        show("story_old") { owned, _ -> Quests.planStoryClaim(jobj("quest" to 9001), owned, inputs, old.deepCopy(), 2) }
        val oldLow = old.deepCopy().also { it["quests"] = jarr(jarr(9001, 3, 0)) }
        show("story_old_low") { owned, _ -> Quests.planStoryClaim(jobj("quest" to 9001), owned, inputs, oldLow.deepCopy(), 2) }
        show("story_running") { owned, _ -> Quests.planStoryClaim(jobj("quest" to 9003), owned, inputs, quests.deepCopy(), 2) }
        // mode 2 x level 6 x 4000 / 10000 x (1 + the 4-star bonus); points x (1 + the points bonus)
        val reward = Quests.questReward(inputs.quest(700103)!!, BigInteger.valueOf(6), inputs, JInt(4))
        assertEquals(listOf(4800L, 1440L, 96L, 100L), listOf(reward.exp, reward.gold, reward.honor, reward.points).map { it.toLong() })
    }

    @Test
    fun `the bounty board`() {
        bounty("accept", 259, 700105, board())
        bounty("accept_running", 259, 700101, board())
        bounty("accept_limit", 259, 700105, board("used" to 25))
        bounty("quit", 263, 700101, board())
        bounty("stars_free", 271, 700102, board())
        bounty("stars_paid", 271, 700102, board("free" to 0))
        bounty("stars_ready", 271, 700103, board())
        bounty("auto", 273, 700101, board())
        bounty("auto_busy", 273, 700102, board("auto_id" to 700101, "auto_until" to now + 100))
        bounty("timer", 275, 700101, board("auto_id" to 700101, "auto_until" to now + 2))
        bounty("timer_early", 275, 700101, board("auto_id" to 700101, "auto_until" to now + 4))
        bounty("claim", 261, 700103, board())
        bounty("claim_running", 261, 700101, board())
        bounty("expedite", 269, 700101, board("auto_id" to 700101, "auto_until" to now + 121))
        bounty("expedite_poor", 269, 700101, board("auto_id" to 700101, "auto_until" to now + 3601))
        bounty("expedite_other", 269, 700102, board("auto_id" to 700101, "auto_until" to now + 121))
        bounty("refresh_paid", 265, null, board())
        bounty("refresh_free", 265, null, board("board_until" to now, "used" to 7))
        bounty("missing", 259, 700199, board())
        // a running auto completion: no board refresh (70102), no star refresh of that task (70109); no timer end without one
        bounty("refresh_auto", 265, null, board("auto_id" to 700101, "auto_until" to now + 100))
        bounty("stars_auto", 271, 700101, board("auto_id" to 700101, "auto_until" to now + 100))
        bounty("timer_none", 275, 0, board())
    }

    @Test
    fun `goal claims pay currencies and items`() {
        val goalDoc = jobj("profile" to Goals.PROFILE, "rows" to jarr(jarr(5101, 3, 1), jarr(5102, 2, 1)), "start_day" to "2030-03-17",
            "level_seen" to 2, "seed" to JNull)
        for ((name, ident) in listOf("goal" to 5101L, "goal_running" to 5102L, "goal_missing" to 5199L)) {
            show(name, mapOf("goal_state" to goalDoc.deepCopy())) { owned, cur ->
                Goals.planClaim(WireWriter().u32(ident).bytes(), owned, cur, null, inputs, now)
            }
        }
        show("goal_off") { owned, cur -> Goals.planClaim(WireWriter().u32(5101).bytes(), owned, cur, null, inputs, now) }
    }

    @Test
    fun `training room queries`() {
        val training = jobj("profile" to HiddenTraining.TRAINING_PROFILE, "next_room" to 3,
            "rooms" to jarr(jobj("uid" to 2, "row" to 402, "password_hex" to "616263", "expires_at" to now + 500)),
            "seat" to jobj("room" to 1, "row" to 401, "seat" to 2, "seated_at" to now - 49))
        val free = training.deepCopy().also { it["seat"] = JNull }
        for ((name, request, document) in listOf(
            Triple("enter_now", 1765 to "0000000000", training), Triple("enter_now_free", 1765 to "0000000000", free),
            Triple("enter_own", 1765 to "0200000000", training), Triple("enter_missing", 1765 to "0900000000", training),
            Triple("kick_default", 1773 to "0100000000000000", training), Triple("kick_own", 1773 to "0200000000000000", training),
            Triple("preview", 1775 to "", training), Triple("preview_none", 1775 to "", null))) {
            val cur = current(mapOf("training_state" to document))
            val text = try {
                framesText(DailyRoutes.trainingQuery(request.first, request.second.hexBytes(), cur, inputs, now))
            } catch (e: Acquisition.Rejected) {
                assertEquals(expected.getValue("$name REFUSED"), refusal(e), name)
                continue
            }
            assertEquals(expected.getValue("$name FRAMES"), text, name)
        }
        assertEquals(BigInteger.valueOf(11425), HiddenTraining.attackScore(state(), inputs))
    }
}
