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

/**
 * CI checks of the Hidden Training actions on made-up tables and saves: each expected value is the reference's output for
 * the same made-up input. The full proof against the recorded saves and the APK tables is [G6TrainingVectorsTest] (local).
 */
class G6TrainingMadeUpTest {
    private class TextTables(private val files: Map<String, String>) : TableSource {
        override fun names() = files.keys.sorted()
        override fun raw(name: String): ByteArray {
            val file = if (name.endsWith(".csv")) name else "$name.csv"
            return (files[file] ?: throw NoSuchElementException("Missing table: $file")).toByteArray()
        }
    }

    private val inputs = DailyInputs(GameTables(TextTables(mapOf(
        "xiuxing.csv" to "101,102,201,203,204,301,302,303,304,401,501,502,503,601,602\n" +
            "401,4,6,0,0,10000,0,0,-1,28800,0,1,999,20000,2500\n402,1,3,3,10,11500,500,100,28800,28800,2,20,60,40000,5000\n" +
            "403,1,3,3,10,11500,500,100,28800,28800,2,61,999,40000,5000\n404,2,2,6,30,14000,1500,200,28800,28800,8,20,999,20000,2500\n",
        "title.csv" to "101,201,202,203\n7,100,20,30\n",
        "roleexp.csv" to "101,102\n1,100\n2,200\n3,300\n4,400\n5,500\n6,600\n",
        "property.csv" to "101,102\n244,50\n245,80\n252,1000\n253,20\n300203,9\n411,100\n412,100\n",
        "qianchuibailian.csv" to "101,103\n1,700\n",
        "equip.csv" to "101,106,112,113,304,306\n4101,2,10,10000,0,1\n",
        "equipjinhua.csv" to "101,102,103,104,113\n1,2,1,1,5\n",
        "equipexp.csv" to "101,102\n1,100\n2,200\n3,300\n4,400\n5,500\n",
        "jewelry.csv" to "101,106,115,121\n5101,3,20000,1\n",
        "jewelry_jinhua.csv" to "101,102,103,104,113\n1,3,2,1,6\n",
        "jewelry_exp.csv" to "101,102\n1,50\n2,60\n3,70\n4,80\n5,90\n6,100\n",
        "yingxiongyuanzheng.csv" to "101,102,103,104,105,106,107,108,109,110,201,202,203,204,205,206,207,208,209,210,211,212,301,302,303\n" +
            "11,9101,1,30,0,600,1,9601,5,300,1,9701,1,50,1,9702,2,50,0,0,0,0,40,40,20\n" +
            "12,9102,2,30,10,900,1,9601,0,400,1,9701,3,70,1,9702,1,30,0,0,0,0,0,0,50\n" +
            "13,9103,3,20,10,1200,2,9601,7,500,1,9703,1,10,1,0,0,0,0,0,0,0,100,0,0\n" +
            "14,9104,4,0,40,1500,1,9601,1,600,1,9701,1,10,1,9702,1,10,1,9703,1,10,0,10,0\n",
        "text.csv" to "101,102\n9101,Mossy Cave\n9102,Old Mill\n9103,Salt Road\n9701,Small Box\n9702,Big Box\n" +
            "4653,##0## went to ##1## and found ##2##; ##3## took it: ##4##. ##5## got ##6## EXP.\n" +
            "4654,##0## went to ##1## and found ##2##; met ##3##. ##4## got ##5## EXP.\n" +
            "4655,##0## went to ##1## and found ##2##; met ##3## who gave ##4##. ##5## got ##6## EXP.\n",
        "item.csv" to "101,102,104,105,106,107,110,203,204,205,206,207,305\n9601,0,2,0,0,0,0,0,0,0,0,,1\n" +
            "9701,9701,2,0,0,0,0,0,0,0,0,,1\n9702,9702,2,0,0,0,0,0,0,0,0,,1\n9703,0,2,0,0,0,0,0,0,0,0,,1\n",
        "hero.csv" to "101,140\n99,0\n"))))

    private val now = 1_900_000_000L
    private val owner = "made-up"

    @BeforeEach
    fun clock() {
        DeviceClock.active = DeviceClock(null, timeSource = { 0L }, offsetSource = { 0 })
    }

    @AfterEach
    fun reset() {
        DeviceClock.active = null
    }

    private fun field(id: Long, bits: Long, tag: Int = 4) = jobj("id" to id, "value" to jobj("tag" to tag, "bits" to bits))

    /** A 40-byte formation jewel block: uid, template, EXP, level, grade, super flag 1, enchant 77, zero tail. */
    private fun jewelBlock(uid: Long, template: Long, level: Long, exp: Long, grade: Int): String =
        WireWriter().u32(uid).u32(template).u32(exp).u32(level).u8(grade).u8(1).u8(0).u8(0).u32(77).bytes().toHexString() + "00".repeat(16)

    private fun state(): JObj = jobj(
        "role_properties" to jarr(field(0, 4242), jobj("id" to 2, "value" to jobj("tag" to 12, "raw_hex" to "416e6e")), field(3, 30),
            field(4, 150), field(6, 5000, 8), field(7, 10), field(8, 120), field(22, 7), field(27, 3)),
        "items" to jarr(jobj("wire_values" to jarr(1, 9601, 12), "timed_flag" to 0)), "item_capacity_values" to jarr(50, 50, 50),
        "equipment" to jarr(jobj("wire_values" to jarr(5, 4101, 1, 50, 1, 0, 0)), jobj("wire_values" to jarr(6, 4101, 5, 0, 1, 0, 0))),
        "bag_equipment_uids" to JArr(),
        "heroes" to jarr(jarr(field(0, 1), field(1, 7001), field(2, 12), field(4, 900), field(6, 300), field(8, 150), field(10, 50),
            field(22, 5), field(23, 7)), jarr(field(0, 2), field(1, 8001), field(2, 3), field(4, 90))),
        "formation" to jarr(jobj("slot_id" to 1, "hero_uid" to 1, "assignments" to JArr(), "groups" to JArr(),
            "blocks_40" to jarr(jobj("id" to 0, "raw_hex" to jewelBlock(31, 5101, 2, 10, 2))))),
        "captain_slot" to 1,
        "subsystems" to jobj("achievements" to jobj("entries" to jarr(jobj("wire_values" to jarr(4, 1, 0)), jobj("wire_values" to jarr(18, 2, 40)))),
            "game_activities" to jobj("first_list" to jobj("count" to 0, "entries" to JArr()))))

    private fun current(jewels: JArr? = null): StateStore.Current =
        StateStore.Current(3, "", "", state(), ByteArray(0), 0, null, JArr(), emptyList(), JArr(), emptyList(), null, null, null, null, null,
            LinkedHashMap<String, JValue?>()).also { it.jewelEntriesView = jewels }

    /** The reference's outputs for the same made-up inputs (plan, frames, state after; or the refusal). */
    private val expected: Map<String, String> = mapOf(
        "create PLAN" to "{\"room\":2,\"row\":402,\"price\":10,\"training_state_after\":{\"profile\":\"training_state_v1\",\"next_room\":3,\"rooms\":[{\"uid\":2,\"row\":402,\"password_hex\":\"\",\"expires_at\":1900028800}],\"seat\":null},\"evidence_class\":\"native_use_policy\"}",
        "create FRAMES" to "[[578, \"120232000000\"], [128, \"0108046e00\"], [2114, \"0200000092010000416e6e0080700000000000000000\"]]",
        "create STATE" to "{\"roles\":{\"8\":110},\"items\":[],\"new\":[],\"equipment\":[{\"wire_values\":[5,4101,1,50,1,0,0]},{\"wire_values\":[6,4101,5,0,1,0,0]}],\"blocks\":[{\"id\":0,\"raw_hex\":\"1f000000ed1300000a00000002000000020100004d00000000000000000000000000000000000000\"}]}",
        "create_none REFUSED" to "[\"Please select a room\", 102]",
        "create_vip REFUSED" to "[\"VIP level too low to create this room\", 102]",
        "create_again REFUSED" to "[\"You already have a training room\", 102]",
        "create_type4 REFUSED" to "[\"Training room does not exist\", 38000]",
        "seat PLAN" to "{\"room\":2,\"seat\":1,\"training_state_after\":{\"profile\":\"training_state_v1\",\"next_room\":3,\"rooms\":[{\"uid\":2,\"row\":402,\"password_hex\":\"\",\"expires_at\":1900000500}],\"seat\":{\"room\":2,\"row\":402,\"seat\":1,\"seated_at\":1900000000}},\"evidence_class\":\"capture_observed\"}",
        "seat FRAMES" to "[[2114, \"0200000092010000416e6e00f4010000f401000000010192100000416e6e00591b0000\"]]",
        "seat STATE" to "{\"roles\":{},\"items\":[],\"new\":[],\"equipment\":[{\"wire_values\":[5,4101,1,50,1,0,0]},{\"wire_values\":[6,4101,5,0,1,0,0]}],\"blocks\":[{\"id\":0,\"raw_hex\":\"1f000000ed1300000a00000002000000020100004d00000000000000000000000000000000000000\"}]}",
        "seat_taken REFUSED" to "[\"The training position is occupied\", 38005]",
        "seat_waiting REFUSED" to "[\"Training reward is available\", 38002]",
        "password PLAN" to "{\"room\":2,\"training_state_after\":{\"profile\":\"training_state_v1\",\"next_room\":3,\"rooms\":[{\"uid\":2,\"row\":402,\"password_hex\":\"31323334\",\"expires_at\":1900000500}],\"seat\":null},\"evidence_class\":\"native_use_policy\"}",
        "password FRAMES" to "[[2114, \"0200000092010000416e6e00f401000000000000313233340000\"]]",
        "password STATE" to "{\"roles\":{},\"items\":[],\"new\":[],\"equipment\":[{\"wire_values\":[5,4101,1,50,1,0,0]},{\"wire_values\":[6,4101,5,0,1,0,0]}],\"blocks\":[{\"id\":0,\"raw_hex\":\"1f000000ed1300000a00000002000000020100004d00000000000000000000000000000000000000\"}]}",
        "password_default REFUSED" to "[\"Only room owner can use this function\", 38006]",
        "add_time PLAN" to "{\"room\":2,\"price\":2,\"training_state_after\":{\"profile\":\"training_state_v1\",\"next_room\":3,\"rooms\":[{\"uid\":2,\"row\":402,\"password_hex\":\"\",\"expires_at\":1900029300}],\"seat\":null},\"evidence_class\":\"native_use_policy\"}",
        "add_time FRAMES" to "[[578, \"12022a000000\"], [128, \"0108047600\"], [2114, \"0200000092010000416e6e0074720000000000000000\"]]",
        "add_time STATE" to "{\"roles\":{\"8\":118},\"items\":[],\"new\":[],\"equipment\":[{\"wire_values\":[5,4101,1,50,1,0,0]},{\"wire_values\":[6,4101,5,0,1,0,0]}],\"blocks\":[{\"id\":0,\"raw_hex\":\"1f000000ed1300000a00000002000000020100004d00000000000000000000000000000000000000\"}]}",
        "add_time_expired PLAN" to "{\"room\":2,\"price\":2,\"training_state_after\":{\"profile\":\"training_state_v1\",\"next_room\":3,\"rooms\":[{\"uid\":2,\"row\":402,\"password_hex\":\"\",\"expires_at\":1900028800}],\"seat\":null},\"evidence_class\":\"native_use_policy\"}",
        "add_time_expired FRAMES" to "[[578, \"12022a000000\"], [128, \"0108047600\"], [2114, \"0200000092010000416e6e0080700000000000000000\"]]",
        "add_time_expired STATE" to "{\"roles\":{\"8\":118},\"items\":[],\"new\":[],\"equipment\":[{\"wire_values\":[5,4101,1,50,1,0,0]},{\"wire_values\":[6,4101,5,0,1,0,0]}],\"blocks\":[{\"id\":0,\"raw_hex\":\"1f000000ed1300000a00000002000000020100004d00000000000000000000000000000000000000\"}]}",
        "claim PLAN" to "{\"seconds\":49,\"attack_score\":11425,\"boost\":10000,\"exp\":18,\"honor\":0,\"levels_gained\":0,\"training_state_after\":{\"profile\":\"training_state_v1\",\"next_room\":3,\"rooms\":[{\"uid\":2,\"row\":402,\"password_hex\":\"\",\"expires_at\":1900000500}],\"seat\":null},\"evidence_class\":\"capture_observed_calculation_attack_score_policy\"}",
        "claim FRAMES" to "[[128, \"010404a800\"], [2118, \"0e00000012000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000\"]]",
        "claim STATE" to "{\"roles\":{\"4\":168},\"items\":[],\"new\":[],\"equipment\":[{\"wire_values\":[5,4101,1,50,1,0,0]},{\"wire_values\":[6,4101,5,0,1,0,0]}],\"blocks\":[{\"id\":0,\"raw_hex\":\"1f000000ed1300000a00000002000000020100004d00000000000000000000000000000000000000\"}]}",
        "claim_none REFUSED" to "[\"Training hasn't started or was claimed\", 38007]",
        "smith PLAN" to "{\"uid\":5,\"level\":4,\"exp\":150,\"forge_state_after\":{\"profile\":\"forge_state_v1\",\"smith\":[1900014400,0],\"craft\":[0,0]},\"evidence_class\":\"capture_observed\"}",
        "smith FRAMES" to "[[106, \"05000000051000000400000096000000010000000000\"], [1762, \"050e0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000105000000bc02000003000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000\"], [128, \"0107040a00\"], [1760, \"054038000000000000\"]]",
        "smith STATE" to "{\"roles\":{},\"items\":[],\"new\":[],\"equipment\":[{\"wire_values\":[5,4101,4,150,1,0,0]},{\"wire_values\":[6,4101,5,0,1,0,0]}],\"blocks\":[{\"id\":0,\"raw_hex\":\"1f000000ed1300000a00000002000000020100004d00000000000000000000000000000000000000\"}]}",
        "smith_cap REFUSED" to "[\"Already at the maximum level\", 102]",
        "smith_cooling REFUSED" to "[\"The slot is cooling down\", 102]",
        "craft_list PLAN" to "{\"jewel_entries_after\":[{\"record\":[40,5101,1,0,2,0,0],\"tail\":null},{\"record\":[41,5101,6,0,2,0,0],\"tail\":null}],\"uid\":41,\"level\":6,\"exp\":0,\"forge_state_after\":{\"profile\":\"forge_state_v1\",\"smith\":[0,0],\"craft\":[0,1900014400]},\"evidence_class\":\"capture_observed\"}",
        "craft_list FRAMES" to "[[3080, \"29000000ed1300000600000000000000020000000000\"], [1762, \"050e000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000129000000bc020000030000000000000000000000000000000000000000000000\"], [3726, \"0000000040380000\"]]",
        "craft_list STATE" to "{\"roles\":{},\"items\":[],\"new\":[],\"equipment\":[{\"wire_values\":[5,4101,1,50,1,0,0]},{\"wire_values\":[6,4101,5,0,1,0,0]}],\"blocks\":[{\"id\":0,\"raw_hex\":\"1f000000ed1300000a00000002000000020100004d00000000000000000000000000000000000000\"}]}",
        "craft_equipped PLAN" to "{\"uid\":31,\"level\":6,\"exp\":0,\"forge_state_after\":{\"profile\":\"forge_state_v1\",\"smith\":[0,0],\"craft\":[1900014400,0]},\"evidence_class\":\"capture_observed\"}",
        "craft_equipped FRAMES" to "[[3080, \"1f000000ed130000060000000000000002014d000000\"], [1762, \"050e00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000011f000000bc020000040000000000000000000000000000000000000000000000\"], [3726, \"4038000000000000\"]]",
        "craft_equipped STATE" to "{\"roles\":{},\"items\":[],\"new\":[],\"equipment\":[{\"wire_values\":[5,4101,1,50,1,0,0]},{\"wire_values\":[6,4101,5,0,1,0,0]}],\"blocks\":[{\"id\":0,\"raw_hex\":\"1f000000ed1300000000000006000000020100004d00000000000000000000000000000000000000\"}]}",
        "craft_missing REFUSED" to "[\"Jewelry is not owned\", 102]",
        "no_cd_smith PLAN" to "{\"slot\":0,\"price\":9,\"forge_state_after\":{\"profile\":\"forge_state_v1\",\"smith\":[0,0],\"craft\":[0,1900000005]},\"evidence_class\":\"capture_observed\"}",
        "no_cd_smith FRAMES" to "[[578, \"120231000000\"], [128, \"0108046f00\"], [1760, \"050000000000000000\"]]",
        "no_cd_smith STATE" to "{\"roles\":{\"8\":111},\"items\":[],\"new\":[],\"equipment\":[{\"wire_values\":[5,4101,1,50,1,0,0]},{\"wire_values\":[6,4101,5,0,1,0,0]}],\"blocks\":[{\"id\":0,\"raw_hex\":\"1f000000ed1300000a00000002000000020100004d00000000000000000000000000000000000000\"}]}",
        "no_cd_craft PLAN" to "{\"slot\":1,\"price\":9,\"forge_state_after\":{\"profile\":\"forge_state_v1\",\"smith\":[1900000060,0],\"craft\":[0,0]},\"evidence_class\":\"native_use_candidate_reply\"}",
        "no_cd_craft FRAMES" to "[[578, \"120231000000\"], [128, \"0108046f00\"], [3726, \"0000000000000000\"]]",
        "no_cd_craft STATE" to "{\"roles\":{\"8\":111},\"items\":[],\"new\":[],\"equipment\":[{\"wire_values\":[5,4101,1,50,1,0,0]},{\"wire_values\":[6,4101,5,0,1,0,0]}],\"blocks\":[{\"id\":0,\"raw_hex\":\"1f000000ed1300000a00000002000000020100004d00000000000000000000000000000000000000\"}]}",
        "no_cd_idle REFUSED" to "[\"The slot is not cooling down\", 102]",
        "buy PLAN" to "{\"slot\":0,\"price\":50,\"explore_state_after\":{\"profile\":\"explore_state_v1\",\"slots\":[{\"pos\":0,\"hero\":0,\"state\":0,\"choices\":[],\"explore\":0,\"returns_at\":0,\"total\":0,\"text_hex\":\"\",\"reward\":null}],\"seed\":null},\"evidence_class\":\"native_use_policy\"}",
        "buy FRAMES" to "[[578, \"12025a000000\"], [128, \"0108044600\"], [2272, \"0000000000\"]]",
        "buy STATE" to "{\"roles\":{\"8\":70},\"items\":[],\"new\":[],\"equipment\":[{\"wire_values\":[5,4101,1,50,1,0,0]},{\"wire_values\":[6,4101,5,0,1,0,0]}],\"blocks\":[{\"id\":0,\"raw_hex\":\"1f000000ed1300000a00000002000000020100004d00000000000000000000000000000000000000\"}]}",
        "buy_third PLAN" to "{\"slot\":4,\"price\":0,\"explore_state_after\":{\"profile\":\"explore_state_v1\",\"slots\":[{\"pos\":0,\"hero\":0,\"state\":0,\"choices\":[],\"explore\":0,\"returns_at\":0,\"total\":0,\"text_hex\":\"\",\"reward\":null},{\"pos\":3,\"hero\":0,\"state\":0,\"choices\":[],\"explore\":0,\"returns_at\":0,\"total\":0,\"text_hex\":\"\",\"reward\":null},{\"pos\":4,\"hero\":0,\"state\":0,\"choices\":[],\"explore\":0,\"returns_at\":0,\"total\":0,\"text_hex\":\"\",\"reward\":null}],\"seed\":null},\"evidence_class\":\"native_use_policy\"}",
        "buy_third FRAMES" to "[[2272, \"0400000000\"]]",
        "buy_third STATE" to "{\"roles\":{},\"items\":[],\"new\":[],\"equipment\":[{\"wire_values\":[5,4101,1,50,1,0,0]},{\"wire_values\":[6,4101,5,0,1,0,0]}],\"blocks\":[{\"id\":0,\"raw_hex\":\"1f000000ed1300000a00000002000000020100004d00000000000000000000000000000000000000\"}]}",
        "hero PLAN" to "{\"slot\":0,\"hero\":1,\"explore_state_after\":{\"profile\":\"explore_state_v1\",\"slots\":[{\"pos\":0,\"hero\":1,\"state\":1,\"choices\":[12,13,11],\"explore\":0,\"returns_at\":0,\"total\":0,\"text_hex\":\"\",\"reward\":null}],\"seed\":null},\"evidence_class\":\"native_use_policy\"}",
        "hero FRAMES" to "[[2272, \"000100000001030c0000000d0000000b000000\"]]",
        "hero STATE" to "{\"roles\":{},\"items\":[],\"new\":[],\"equipment\":[{\"wire_values\":[5,4101,1,50,1,0,0]},{\"wire_values\":[6,4101,5,0,1,0,0]}],\"blocks\":[{\"id\":0,\"raw_hex\":\"1f000000ed1300000a00000002000000020100004d00000000000000000000000000000000000000\"}]}",
        "hero_busy REFUSED" to "[\"Hero already sets out in another slot\", 51002]",
        "go PLAN" to "{\"slot\":0,\"explore\":11,\"explore_state_after\":{\"profile\":\"explore_state_v1\",\"slots\":[{\"pos\":0,\"hero\":1,\"state\":2,\"choices\":[11,12],\"explore\":11,\"returns_at\":1900000600,\"total\":600,\"text_hex\":\"\",\"reward\":null}],\"seed\":null},\"evidence_class\":\"capture_observed\"}",
        "go FRAMES" to "[[68, \"010100000007000000\"], [2272, \"0001000000025802000058020000\"]]",
        "go STATE" to "{\"roles\":{},\"items\":[[1,7]],\"new\":[],\"equipment\":[{\"wire_values\":[5,4101,1,50,1,0,0]},{\"wire_values\":[6,4101,5,0,1,0,0]}],\"blocks\":[{\"id\":0,\"raw_hex\":\"1f000000ed1300000a00000002000000020100004d00000000000000000000000000000000000000\"}]}",
        "go_short PLAN" to "{\"slot\":0,\"explore\":13,\"explore_state_after\":{\"profile\":\"explore_state_v1\",\"slots\":[{\"pos\":0,\"hero\":1,\"state\":2,\"choices\":[13],\"explore\":13,\"returns_at\":1900001200,\"total\":1200,\"text_hex\":\"\",\"reward\":null}],\"seed\":null},\"evidence_class\":\"capture_observed\"}",
        "go_short FRAMES" to "[[2272, \"000100000002b0040000b0040000\"]]",
        "go_short STATE" to "{\"roles\":{},\"items\":[],\"new\":[],\"equipment\":[{\"wire_values\":[5,4101,1,50,1,0,0]},{\"wire_values\":[6,4101,5,0,1,0,0]}],\"blocks\":[{\"id\":0,\"raw_hex\":\"1f000000ed1300000a00000002000000020100004d00000000000000000000000000000000000000\"}]}",
        "refresh_gold PLAN" to "{\"slot\":0,\"method\":1,\"price\":1000,\"explore_state_after\":{\"profile\":\"explore_state_v1\",\"slots\":[{\"pos\":0,\"hero\":1,\"state\":1,\"choices\":[12,13,11],\"explore\":0,\"returns_at\":0,\"total\":0,\"text_hex\":\"\",\"reward\":null}],\"seed\":null},\"evidence_class\":\"native_use_policy\"}",
        "refresh_gold FRAMES" to "[[128, \"010608a00f000000000000\"], [2272, \"000100000001030c0000000d0000000b000000\"]]",
        "refresh_gold STATE" to "{\"roles\":{\"6\":4000},\"items\":[],\"new\":[],\"equipment\":[{\"wire_values\":[5,4101,1,50,1,0,0]},{\"wire_values\":[6,4101,5,0,1,0,0]}],\"blocks\":[{\"id\":0,\"raw_hex\":\"1f000000ed1300000a00000002000000020100004d00000000000000000000000000000000000000\"}]}",
        "refresh_diamond PLAN" to "{\"slot\":0,\"method\":2,\"price\":20,\"explore_state_after\":{\"profile\":\"explore_state_v1\",\"slots\":[{\"pos\":0,\"hero\":1,\"state\":1,\"choices\":[14,12,13],\"explore\":0,\"returns_at\":0,\"total\":0,\"text_hex\":\"\",\"reward\":null}],\"seed\":null},\"evidence_class\":\"native_use_policy\"}",
        "refresh_diamond FRAMES" to "[[578, \"12023c000000\"], [128, \"0108046400\"], [2272, \"000100000001030e0000000c0000000d000000\"]]",
        "refresh_diamond STATE" to "{\"roles\":{\"8\":100},\"items\":[],\"new\":[],\"equipment\":[{\"wire_values\":[5,4101,1,50,1,0,0]},{\"wire_values\":[6,4101,5,0,1,0,0]}],\"blocks\":[{\"id\":0,\"raw_hex\":\"1f000000ed1300000a00000002000000020100004d00000000000000000000000000000000000000\"}]}",
        "return_11 PLAN" to "{\"slot\":0,\"explore_state_after\":{\"profile\":\"explore_state_v1\",\"slots\":[{\"pos\":0,\"hero\":1,\"state\":3,\"choices\":[11],\"explore\":11,\"returns_at\":1899999999,\"total\":600,\"text_hex\":\"4865726f2077656e7420746f204d6f737379204361766520616e6420666f756e642042696720426f783b206d657420612074726176656c65722e204865726f20676f7420333030204558502e\",\"reward\":{\"kind\":\"met\",\"items\":[[9702,2]],\"hero_exp\":300}}],\"seed\":null},\"evidence_class\":\"native_use_policy\"}",
        "return_11 FRAMES" to "[[2272, \"0001000000034865726f2077656e7420746f204d6f737379204361766520616e6420666f756e642042696720426f783b206d657420612074726176656c65722e204865726f20676f7420333030204558502e00\"]]",
        "return_11 STATE" to "{\"roles\":{},\"items\":[],\"new\":[],\"equipment\":[{\"wire_values\":[5,4101,1,50,1,0,0]},{\"wire_values\":[6,4101,5,0,1,0,0]}],\"blocks\":[{\"id\":0,\"raw_hex\":\"1f000000ed1300000a00000002000000020100004d00000000000000000000000000000000000000\"}]}",
        "return_12 PLAN" to "{\"slot\":0,\"explore_state_after\":{\"profile\":\"explore_state_v1\",\"slots\":[{\"pos\":0,\"hero\":1,\"state\":3,\"choices\":[12],\"explore\":12,\"returns_at\":1900000002,\"total\":600,\"text_hex\":\"4865726f2077656e7420746f204f6c64204d696c6c20616e6420666f756e642042696720426f783b206d657420612074726176656c65722077686f20676176652042696720426f782e204865726f20676f7420343030204558502e\",\"reward\":{\"kind\":\"gift\",\"items\":[[9702,1],[9702,1]],\"hero_exp\":400}}],\"seed\":null},\"evidence_class\":\"native_use_policy\"}",
        "return_12 FRAMES" to "[[2272, \"0001000000034865726f2077656e7420746f204f6c64204d696c6c20616e6420666f756e642042696720426f783b206d657420612074726176656c65722077686f20676176652042696720426f782e204865726f20676f7420343030204558502e00\"]]",
        "return_12 STATE" to "{\"roles\":{},\"items\":[],\"new\":[],\"equipment\":[{\"wire_values\":[5,4101,1,50,1,0,0]},{\"wire_values\":[6,4101,5,0,1,0,0]}],\"blocks\":[{\"id\":0,\"raw_hex\":\"1f000000ed1300000a00000002000000020100004d00000000000000000000000000000000000000\"}]}",
        "return_13 PLAN" to "{\"slot\":0,\"explore_state_after\":{\"profile\":\"explore_state_v1\",\"slots\":[{\"pos\":0,\"hero\":1,\"state\":3,\"choices\":[13],\"explore\":13,\"returns_at\":1900000008,\"total\":600,\"text_hex\":\"4865726f2077656e7420746f2053616c7420526f616420616e6420666f756e6420393730333b206120726f6262657220746f6f6b2069743a206e6f7468696e6720776173206c6566742e204865726f20676f7420353030204558502e\",\"reward\":{\"kind\":\"robbed\",\"items\":[],\"robbed\":[9703,1],\"hero_exp\":500}}],\"seed\":null},\"evidence_class\":\"native_use_policy\"}",
        "return_13 FRAMES" to "[[2272, \"0001000000034865726f2077656e7420746f2053616c7420526f616420616e6420666f756e6420393730333b206120726f6262657220746f6f6b2069743a206e6f7468696e6720776173206c6566742e204865726f20676f7420353030204558502e00\"]]",
        "return_13 STATE" to "{\"roles\":{},\"items\":[],\"new\":[],\"equipment\":[{\"wire_values\":[5,4101,1,50,1,0,0]},{\"wire_values\":[6,4101,5,0,1,0,0]}],\"blocks\":[{\"id\":0,\"raw_hex\":\"1f000000ed1300000a00000002000000020100004d00000000000000000000000000000000000000\"}]}",
        "return_14 PLAN" to "{\"slot\":0,\"explore_state_after\":{\"profile\":\"explore_state_v1\",\"slots\":[{\"pos\":0,\"hero\":1,\"state\":3,\"choices\":[14],\"explore\":14,\"returns_at\":1900000000,\"total\":600,\"text_hex\":\"4865726f2077656e7420746f203f20616e6420666f756e6420536d616c6c20426f783b206d657420612074726176656c65722e204865726f20676f7420363030204558502e\",\"reward\":{\"kind\":\"met\",\"items\":[[9701,1]],\"hero_exp\":600}}],\"seed\":null},\"evidence_class\":\"native_use_policy\"}",
        "return_14 FRAMES" to "[[2272, \"0001000000034865726f2077656e7420746f203f20616e6420666f756e6420536d616c6c20426f783b206d657420612074726176656c65722e204865726f20676f7420363030204558502e00\"]]",
        "return_14 STATE" to "{\"roles\":{},\"items\":[],\"new\":[],\"equipment\":[{\"wire_values\":[5,4101,1,50,1,0,0]},{\"wire_values\":[6,4101,5,0,1,0,0]}],\"blocks\":[{\"id\":0,\"raw_hex\":\"1f000000ed1300000a00000002000000020100004d00000000000000000000000000000000000000\"}]}",
        "return_early REFUSED" to "[\"Status Error\", 51002]",
        "claim_out PLAN" to "{\"slot\":0,\"items\":[[9701,3],[9701,3]],\"hero_exp\":{\"uid\":1,\"awarded\":400,\"granted\":0,\"levels_gained\":0,\"withheld\":\"ValueError: Hero base 7 must resolve to exactly one row\"},\"explore_state_after\":{\"profile\":\"explore_state_v1\",\"slots\":[{\"pos\":0,\"hero\":1,\"state\":1,\"choices\":[13,12,11],\"explore\":0,\"returns_at\":0,\"total\":0,\"text_hex\":\"\",\"reward\":null}],\"seed\":null},\"evidence_class\":\"native_use_policy\"}",
        "claim_out FRAMES" to "[[64, \"0102000000e52500000300000000\"], [68, \"010200000006000000\"], [2276, \"0e000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000002e525000003000000e52500000300000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000\"], [2272, \"000100000001030d0000000c0000000b000000\"]]",
        "claim_out STATE" to "{\"roles\":{},\"items\":[],\"new\":[[2,9701,6]],\"equipment\":[{\"wire_values\":[5,4101,1,50,1,0,0]},{\"wire_values\":[6,4101,5,0,1,0,0]}],\"blocks\":[{\"id\":0,\"raw_hex\":\"1f000000ed1300000a00000002000000020100004d00000000000000000000000000000000000000\"}]}",
        "claim_back PLAN" to "{\"slot\":0,\"items\":[[9701,1],[9702,2]],\"hero_exp\":{\"uid\":2,\"awarded\":300,\"granted\":0,\"levels_gained\":0,\"withheld\":\"ValueError: Hero base 8 must resolve to exactly one row\"},\"explore_state_after\":{\"profile\":\"explore_state_v1\",\"slots\":[{\"pos\":0,\"hero\":2,\"state\":1,\"choices\":[13,12,11],\"explore\":0,\"returns_at\":0,\"total\":0,\"text_hex\":\"\",\"reward\":null}],\"seed\":null},\"evidence_class\":\"native_use_policy\"}",
        "claim_back FRAMES" to "[[64, \"0102000000e52500000100000000\"], [64, \"0103000000e62500000200000000\"], [2276, \"0e000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000002e525000001000000e62500000200000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000\"], [2272, \"000200000001030d0000000c0000000b000000\"]]",
        "claim_back STATE" to "{\"roles\":{},\"items\":[],\"new\":[[2,9701,1],[3,9702,2]],\"equipment\":[{\"wire_values\":[5,4101,1,50,1,0,0]},{\"wire_values\":[6,4101,5,0,1,0,0]}],\"blocks\":[{\"id\":0,\"raw_hex\":\"1f000000ed1300000a00000002000000020100004d00000000000000000000000000000000000000\"}]}",
        "claim_none PLAN" to "{\"slot\":0,\"items\":[],\"hero_exp\":{\"uid\":1,\"awarded\":0,\"granted\":0,\"levels_gained\":0,\"withheld\":\"no EXP\"},\"explore_state_after\":{\"profile\":\"explore_state_v1\",\"slots\":[{\"pos\":0,\"hero\":1,\"state\":1,\"choices\":[13,12,11],\"explore\":0,\"returns_at\":0,\"total\":0,\"text_hex\":\"\",\"reward\":null}],\"seed\":null},\"evidence_class\":\"native_use_policy\"}",
        "claim_none FRAMES" to "[[2276, \"0e00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000\"], [2272, \"000100000001030d0000000c0000000b000000\"]]",
        "claim_none STATE" to "{\"roles\":{},\"items\":[],\"new\":[],\"equipment\":[{\"wire_values\":[5,4101,1,50,1,0,0]},{\"wire_values\":[6,4101,5,0,1,0,0]}],\"blocks\":[{\"id\":0,\"raw_hex\":\"1f000000ed1300000a00000002000000020100004d00000000000000000000000000000000000000\"}]}",
        "cancel PLAN" to "{\"slot\":0,\"explore_state_after\":{\"profile\":\"explore_state_v1\",\"slots\":[{\"pos\":0,\"hero\":1,\"state\":1,\"choices\":[11],\"explore\":0,\"returns_at\":0,\"total\":0,\"text_hex\":\"\",\"reward\":null}],\"seed\":null},\"evidence_class\":\"native_use_policy\"}",
        "cancel FRAMES" to "[[2272, \"000100000001010b000000\"]]",
        "cancel STATE" to "{\"roles\":{},\"items\":[],\"new\":[],\"equipment\":[{\"wire_values\":[5,4101,1,50,1,0,0]},{\"wire_values\":[6,4101,5,0,1,0,0]}],\"blocks\":[{\"id\":0,\"raw_hex\":\"1f000000ed1300000a00000002000000020100004d00000000000000000000000000000000000000\"}]}",
        "cancel_idle REFUSED" to "[\"Status Error\", 51002]",
    )

    private fun framesText(frames: List<Frame>) = "[" + frames.joinToString(", ") { "[${it.first}, \"${it.second.toHexString()}\"]" } + "]"

    private fun refusal(e: Acquisition.Rejected) = "[${Json.dumps(JStr(e.message!!))}, ${e.code}]"

    private fun stateText(owned: Owned): String = Json.compact(jobj(
        "roles" to JObj().also { o -> owned.roleChanges.forEach { (f, c) -> o[f.toString()] = JInt(c.second) } },
        "items" to JArr(owned.itemChanges.entries.mapTo(ArrayList()) { (u, c) -> jarr(u, c) }),
        "new" to JArr(owned.newItems.entries.mapTo(ArrayList()) { (u, e) -> jarr(u, e.first, e.second) }),
        "equipment" to owned.state["equipment"], "blocks" to (owned.state.arr("formation")[0] as JObj)["blocks_40"]))

    /** Run one planner on a fresh made-up save and compare plan, frames and state (or the refusal) with the reference's. */
    private fun show(name: String, jewels: JArr? = null, planner: (Owned, StateStore.Current) -> Plan) {
        val cur = current(jewels)
        val owned = Owned(cur, inputs)
        val plan = try {
            planner(owned, cur)
        } catch (e: Acquisition.Rejected) {
            assertEquals(expected.getValue("$name REFUSED"), refusal(e), name)
            return
        }
        assertEquals(expected.getValue("$name PLAN"), Json.compact(plan.data), "$name plan")
        assertEquals(expected.getValue("$name FRAMES"), framesText(plan.packets), "$name frames")
        assertEquals(expected.getValue("$name STATE"), stateText(owned), "$name state")
    }

    private val room = jobj("uid" to 2, "row" to 402, "password_hex" to "", "expires_at" to now + 500)

    private fun training(vararg changes: Pair<String, Any?>): JObj =
        jobj("profile" to HiddenTraining.TRAINING_PROFILE, "next_room" to 3, "rooms" to jarr(room.deepCopy()), "seat" to JNull)
            .also { d -> changes.forEach { (k, v) -> d[k] = jvalue(v) } }

    private fun slot(pos: Int, hero: Int, state: Int, choices: List<Int> = emptyList(), explore: Int = 0, returnsAt: Long = 0, total: Int = 0,
                     reward: JObj? = null, textHex: String = ""): JObj =
        jobj("pos" to pos, "hero" to hero, "state" to state, "choices" to JArr(choices.mapTo(ArrayList<JValue>()) { JInt(it.toLong()) }),
            "explore" to explore, "returns_at" to returnsAt, "total" to total, "text_hex" to textHex, "reward" to reward)

    private fun explore(vararg slots: JObj): JObj = jobj("profile" to HiddenTraining.EXPLORE_PROFILE, "slots" to jarr(*slots), "seed" to JNull)

    @Test
    fun `decoders`() {
        assertEquals("""{"room":2,"seat":5}""", Json.compact(HiddenTraining.decodeTrain("0200000005".hexBytes())))
        assertEquals("""{"slot":1,"uid":4096}""", Json.compact(HiddenTraining.decodePick("0100100000".hexBytes(), 1643)))
        assertEquals("""{"slot":3}""", Json.compact(HiddenTraining.decodeU32Slot("03000000".hexBytes(), 3731)))
        assertEquals("""{"slot":1,"uid":7}""", Json.compact(HiddenTraining.decodeExplorePick("0107000000".hexBytes(), 2113)))
        assertEquals("""{"slot":1,"explore":7}""", Json.compact(HiddenTraining.decodeExplorePick("0107000000".hexBytes(), 2115)))
        assertEquals("""{"slot":2,"method":1}""", Json.compact(HiddenTraining.decodeRefresh("0201".hexBytes())))
        assertEquals("""{"slot":4}""", Json.compact(HiddenTraining.decodeU8Slot("04".hexBytes(), 2119)))
        for ((message, call) in listOf<Pair<String, () -> Any>>(
            "C1767 is u32 room + u8 seat" to { HiddenTraining.decodeTrain(ByteArray(4)) },
            "C3747 is u8 slot + u32 uid" to { HiddenTraining.decodePick(ByteArray(6), 3747) },
            "C3753 is u32 slot" to { HiddenTraining.decodeU32Slot(ByteArray(1), 3753) },
            "C2115 is u8 slot + u32" to { HiddenTraining.decodeExplorePick(ByteArray(0), 2115) },
            "C2117 is u8 slot + u8 method" to { HiddenTraining.decodeRefresh(ByteArray(1)) },
            "C2125 is u8 slot" to { HiddenTraining.decodeU8Slot(ByteArray(2), 2125) })) {
            val e = assertThrows(Acquisition.Rejected::class.java) { call() }
            assertEquals(message to 102, e.message to e.code)
        }
    }

    @Test
    fun `training rooms`() {
        show("create") { o, _ -> HiddenTraining.planCreateRoom(1, o, inputs, null, now, now) }
        show("create_none") { o, _ -> HiddenTraining.planCreateRoom(3, o, inputs, null, now, now) }
        show("create_vip") { o, _ -> HiddenTraining.planCreateRoom(2, o, inputs, null, now, now) }
        show("create_again") { o, _ -> HiddenTraining.planCreateRoom(1, o, inputs, training(), now, now) }
        show("create_type4") { o, _ -> HiddenTraining.planCreateRoom(4, o, inputs, null, now, now) }
        show("seat") { o, _ -> HiddenTraining.planTrain(jobj("room" to 2, "seat" to 1), o, inputs, training(), now) }
        show("seat_taken") { o, _ -> HiddenTraining.planTrain(jobj("room" to 2, "seat" to 3), o, inputs, training(), now) }
        val waiting = training("seat" to jobj("room" to 1, "row" to 401, "seat" to 2, "seated_at" to now - 28800))
        show("seat_waiting") { o, _ -> HiddenTraining.planTrain(jobj("room" to 2, "seat" to 0), o, inputs, waiting, now) }
        show("password") { o, _ -> HiddenTraining.planPassword(HiddenTraining.RoomSecret(JInt(2), "31323334".hexBytes()), o, inputs, training(), now) }
        show("password_default") { o, _ -> HiddenTraining.planPassword(HiddenTraining.RoomSecret(JInt(1), "78".hexBytes()), o, inputs, training(), now) }
        show("add_time") { o, _ -> HiddenTraining.planAddTime(jobj("room" to 2), o, inputs, training(), now, now) }
        val expired = training("rooms" to jarr(room.deepCopy().also { it["expires_at"] = JInt(now - 5) }))
        show("add_time_expired") { o, _ -> HiddenTraining.planAddTime(jobj("room" to 2), o, inputs, expired, now, now) }
        val running = training("seat" to jobj("room" to 1, "row" to 401, "seat" to 2, "seated_at" to now - 49))
        show("claim") { o, _ -> HiddenTraining.planClaim(o, inputs, running, now) }
        show("claim_none") { o, _ -> HiddenTraining.planClaim(o, inputs, training(), now) }
    }

    @Test
    fun `Blacksmith, Crafting and cooldown removal`() {
        show("smith") { o, _ -> HiddenTraining.planSmith(jobj("slot" to 0, "uid" to 5), o, inputs, null, now) }
        show("smith_cap") { o, _ -> HiddenTraining.planSmith(jobj("slot" to 1, "uid" to 6), o, inputs, null, now) }
        show("smith_cooling") { o, _ ->
            HiddenTraining.planSmith(jobj("slot" to 0, "uid" to 5), o, inputs, jobj("profile" to HiddenTraining.FORGE_PROFILE, "smith" to jarr(now + 1, 0)), now)
        }
        val jewels = jarr(jobj("record" to jarr(40, 5101, 1, 0, 2, 0, 0), "tail" to JNull), jobj("record" to jarr(41, 5101, 3, 5, 2, 0, 0), "tail" to JNull))
        show("craft_list", jewels) { o, c -> HiddenTraining.planCraft(jobj("slot" to 1, "uid" to 41), o, c, inputs, null, now) }
        show("craft_equipped", jewels) { o, c -> HiddenTraining.planCraft(jobj("slot" to 0, "uid" to 31), o, c, inputs, null, now) }
        show("craft_missing") { o, c -> HiddenTraining.planCraft(jobj("slot" to 0, "uid" to 99), o, c, inputs, null, now) }
        val cooling = jobj("profile" to HiddenTraining.FORGE_PROFILE, "smith" to jarr(now + 60, 0), "craft" to jarr(0, now + 5))
        show("no_cd_smith") { o, _ -> HiddenTraining.planNoCd("smith", jobj("slot" to 0), o, inputs, cooling.deepCopy(), now, now) }
        show("no_cd_craft") { o, _ -> HiddenTraining.planNoCd("craft", jobj("slot" to 1), o, inputs, cooling.deepCopy(), now, now) }
        show("no_cd_idle") { o, _ -> HiddenTraining.planNoCd("craft", jobj("slot" to 0), o, inputs, cooling.deepCopy(), now, now) }
    }

    @Test
    fun `Hero Set Out`() {
        show("buy") { o, _ -> HiddenTraining.planExploreBuy(o, inputs, explore(), now, now) }
        show("buy_third") { o, _ -> HiddenTraining.planExploreBuy(o, inputs, explore(slot(0, 0, 0), slot(3, 0, 0)), now, now) }
        show("hero") { o, _ -> HiddenTraining.planExploreHero(jobj("slot" to 0, "uid" to 1), o, inputs, explore(slot(0, 0, 0)), now, owner) }
        show("hero_busy") { o, _ ->
            HiddenTraining.planExploreHero(jobj("slot" to 1, "uid" to 1), o, inputs, explore(slot(0, 1, 1, listOf(11, 12)), slot(1, 0, 0)), now, owner)
        }
        show("go") { o, _ -> HiddenTraining.planExploreGo(jobj("slot" to 0, "explore" to 11), o, inputs, explore(slot(0, 1, 1, listOf(11, 12))), now) }
        show("go_short") { o, _ -> HiddenTraining.planExploreGo(jobj("slot" to 0, "explore" to 13), o, inputs, explore(slot(0, 1, 1, listOf(13))), now) }
        show("refresh_gold") { o, _ ->
            HiddenTraining.planExploreRefresh(jobj("slot" to 0, "method" to 1), o, inputs, explore(slot(0, 1, 1, listOf(11))), now, now, owner)
        }
        show("refresh_diamond") { o, _ ->
            HiddenTraining.planExploreRefresh(jobj("slot" to 0, "method" to 2), o, inputs, explore(slot(0, 1, 1, listOf(11))), now + 7, now, owner)
        }
        for ((name, ident, time) in listOf(Triple("return_11", 11, now), Triple("return_12", 12, now + 3), Triple("return_13", 13, now + 9),
                Triple("return_14", 14, now + 1))) {
            show(name) { o, _ ->
                HiddenTraining.planExploreReturn(jobj("slot" to 0), o, inputs, explore(slot(0, 1, 2, listOf(ident), ident, time - 1, 600)), time, owner)
            }
        }
        show("return_early") { o, _ ->
            HiddenTraining.planExploreReturn(jobj("slot" to 0), o, inputs, explore(slot(0, 1, 2, listOf(11), 11, now + 1)), now, owner)
        }
        show("claim_out") { o, _ -> HiddenTraining.planExploreClaim(jobj("slot" to 0), o, inputs, explore(slot(0, 1, 2, listOf(12), 12, now - 1)), now, owner) }
        val gift = jobj("kind" to "gift", "items" to jarr(jarr(9701, 1), jarr(9702, 2)), "hero_exp" to 300)
        show("claim_back") { o, _ ->
            HiddenTraining.planExploreClaim(jobj("slot" to 0), o, inputs, explore(slot(0, 2, 3, listOf(11), 11, 0, 0, gift, "4869")), now, owner)
        }
        show("claim_none") { o, _ -> HiddenTraining.planExploreClaim(jobj("slot" to 0), o, inputs, explore(slot(0, 1, 3, listOf(11), 11)), now, owner) }
        show("cancel") { o, _ -> HiddenTraining.planExploreCancel(jobj("slot" to 0), o, inputs, explore(slot(0, 1, 2, listOf(11), 11, now + 50, 600)), now) }
        show("cancel_idle") { o, _ -> HiddenTraining.planExploreCancel(jobj("slot" to 0), o, inputs, explore(slot(0, 1, 1, listOf(11))), now) }
    }
}
