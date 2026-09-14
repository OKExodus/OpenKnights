package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JNull
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.toHexString
import io.github.okexodus.openknights.gamedata.GameTables
import io.github.okexodus.openknights.gamedata.TableSource
import io.github.okexodus.openknights.server.DeviceClock
import io.github.okexodus.openknights.server.store.StateStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigInteger

/**
 * CI checks of the castle slice on made-up tables and a made-up save: each expected value is the reference's output for
 * the same made-up input. The full proof against the recorded saves and the APK tables is [G6CastleVectorsTest] (local).
 */
class G6CastleMadeUpTest {
    private class TextTables(private val files: Map<String, String>) : TableSource {
        override fun names() = files.keys.sorted()
        override fun raw(name: String): ByteArray {
            val file = if (name.endsWith(".csv")) name else "$name.csv"
            return (files[file] ?: throw NoSuchElementException("Missing table: $file")).toByteArray()
        }
    }

    private val tables = mapOf(
        "property.csv" to "101,102\n3,20\n73,900\n74,600\n75,250\n76,5000\n77,60\n78,30\n82,120\n88,700\n90,11\n91,33\n92,66\n94,7\n198,5\n215,3\n232,13\n761,40\n955,2\n4005,5\n11006,7009\n11007,7001\n11008,7002\n11009,7003\n11015,4\n11016,9\n",
        "zhengshou.csv" to "101,102,103,104,105,201,202,203\n1,0,1000,200,0,3000,1500,500\n2,4,1500,300,0,3000,1500,500\n3,9,2000,400,0,3000,1500,500\n",
        "viplv.csv" to "101,102,112,113,114,128\n1,0,0,0,0,0\n2,2,0,1000,500,3\n",
        "building.csv" to "101,103,106,109,110\n1,1,20,0,60\n2,0,0,0,0\n6,1,15,0,40\n7,1,10,0,60\n9,1,10,0,50\n",
        "technology.csv" to "101,103,105,110\n101,1,30,1\n102,1,25,2\n201,3,40,3\n",
        "juntuan_technolegy.csv" to "101,103,105,111,112\n102,0,100,25,25\n103,1,100,0,0\n",
        "lianjin.csv" to "101,102,104,105,106,107,108\n1,60,1000,0,0,0,60\n2,30,2000,500,0,0,30\n3,10,4000,9000,9501,2,20\n",
        "item.csv" to "101,102,104,105,106,107,110,203,204,205,206,207,305\n9501,1,2,0,0,0,0,0,0,0,0,,1\n7001,1,2,0,0,0,0,0,0,0,0,,1\n7002,1,2,0,0,0,0,0,0,0,0,,1\n7003,1,2,0,0,0,0,0,0,0,0,,1\n7004,1,2,0,0,0,0,0,0,0,0,,1\n7005,1,2,0,0,0,0,0,0,0,0,,1\n",
        "resetdiamond.csv" to "701,704,705,706,707,708\n203,50,20,300,40,600\n",
        "equip.csv" to "101,106,113,601\n4001,3,10000,0\n",
        "equipexp.csv" to "101,102\n1,0\n2,100\n3,250\n4,400\n5,800\n",
        "qianghua_itemexp.csv" to "101,102,103,104\n1,2,7001,500\n2,2,7002,100\n3,2,7003,10\n",
        "resetequipjinhua.csv" to "601,602,603,604,605,606,607,608,609\n1,3,2,7004,3,7005,1,0,0\n",
        "renascence.csv" to "501,502,503,504,505,506,507\n1,2,0,3,0,45,0\n",
    )

    private val expected: Map<String, String> = mapOf(
        "collect_gold" to "{\"plan\":{\"kind\":1,\"types\":[1],\"multipliers\":{\"1\":1},\"cost\":1,\"castle_state_after\":{\"profile\":\"castle_state_v1\",\"day\":\"2030-03-17\",\"collected\":{\"1\":2,\"2\":0,\"4\":2},\"alchemy\":null,\"servants\":{}},\"evidence_class\":\"capture_observed_csv_calculation_rng_policy\"},\"frames\":[[578,\"120229000000\"],[224,\"0104040e00000000000000000000000000000093060000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000\"],[128,\"020608e3c9000000000000080577000000\"],[226,\"0600000000000000060000000a000000\"]],\"roles\":{\"0\":4242,\"3\":12,\"6\":51683,\"7\":9000,\"8\":119,\"17\":0,\"27\":2,\"30\":700,\"33\":0},\"state\":{\"gems\":{\"count\":1,\"entries\":[{\"wire_values\":[104,2]}]},\"buildings\":{\"count\":4,\"entries\":[{\"wire_values\":[1,2]},{\"wire_values\":[6,1]},{\"wire_values\":[7,2]},{\"wire_values\":[9,1]}]},\"technologies\":{\"count\":2,\"entries\":[{\"wire_values\":[101,1]},{\"wire_values\":[102,0]}]},\"alchemy\":{\"wire_values\":[3,3,3,0,1,3,250,2,5]},\"servants\":{\"wire_u8_prefix\":[0,2],\"servants\":{\"count\":1,\"entries\":[{\"wire_u32_1\":55,\"cstring_hex\":\"426f62\",\"wire_values_after_string\":[20,0,4000,0,0,1,0]}]},\"optional_servant\":null},\"achievements\":{\"entries\":[{\"wire_values\":[3,1,0]},{\"wire_values\":[6,1,2]},{\"wire_values\":[18,2,41]}]}}}",
        "collect_all" to "{\"plan\":{\"kind\":7,\"types\":[1,2,4],\"multipliers\":{\"1\":10,\"2\":2,\"4\":2},\"cost\":5,\"castle_state_after\":{\"profile\":\"castle_state_v1\",\"day\":\"2030-03-17\",\"collected\":{\"1\":2,\"2\":1,\"4\":3},\"alchemy\":null,\"servants\":{}},\"evidence_class\":\"capture_observed_csv_calculation_rng_policy\"},\"frames\":[[578,\"12022d000000\"],[1536,\"c002000001000000\"],[1536,\"3101000001000000\"],[224,\"0402020e0000000000000000000000a4010000be410000000000000000000000000000000000000000000000000000000000000000000000000000000000000000023101000001000000c0020000010000000000000000000000000000000000000000000000000000000000000000000000000000\"],[128,\"0306080e050100000000000705cc240000080573000000\"],[226,\"06000000010000000000000005000000\"]],\"roles\":{\"0\":4242,\"3\":12,\"6\":66830,\"7\":9420,\"8\":115,\"17\":0,\"27\":2,\"30\":700,\"33\":0},\"state\":{\"gems\":{\"count\":3,\"entries\":[{\"wire_values\":[104,2]},{\"wire_values\":[305,1]},{\"wire_values\":[704,1]}]},\"buildings\":{\"count\":4,\"entries\":[{\"wire_values\":[1,2]},{\"wire_values\":[6,1]},{\"wire_values\":[7,2]},{\"wire_values\":[9,1]}]},\"technologies\":{\"count\":2,\"entries\":[{\"wire_values\":[101,1]},{\"wire_values\":[102,0]}]},\"alchemy\":{\"wire_values\":[3,3,3,0,1,3,250,2,5]},\"servants\":{\"wire_u8_prefix\":[0,2],\"servants\":{\"count\":1,\"entries\":[{\"wire_u32_1\":55,\"cstring_hex\":\"426f62\",\"wire_values_after_string\":[20,0,4000,0,0,1,0]}]},\"optional_servant\":null},\"achievements\":{\"entries\":[{\"wire_values\":[3,1,0]},{\"wire_values\":[6,1,2]},{\"wire_values\":[18,2,45]}]}}}",
        "collect_runes_new_day" to "{\"plan\":{\"kind\":4,\"types\":[4],\"multipliers\":{\"4\":1},\"cost\":0,\"castle_state_after\":{\"profile\":\"castle_state_v1\",\"day\":\"2030-03-17\",\"collected\":{\"1\":0,\"2\":0,\"4\":1},\"alchemy\":null,\"servants\":{}},\"evidence_class\":\"capture_observed_csv_calculation_rng_policy\"},\"frames\":[[1536,\"3001000001000000\"],[1536,\"5c02000001000000\"],[224,\"0404010e000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000230010000010000005c020000010000000000000000000000000000000000000000000000000000000000000000000000000000\"],[226,\"00000000000000000100000000000000\"]],\"roles\":{\"0\":4242,\"3\":12,\"6\":50000,\"7\":9000,\"8\":120,\"17\":0,\"27\":2,\"30\":700,\"33\":0},\"state\":{\"gems\":{\"count\":3,\"entries\":[{\"wire_values\":[104,2]},{\"wire_values\":[304,1]},{\"wire_values\":[604,1]}]},\"buildings\":{\"count\":4,\"entries\":[{\"wire_values\":[1,2]},{\"wire_values\":[6,1]},{\"wire_values\":[7,2]},{\"wire_values\":[9,1]}]},\"technologies\":{\"count\":2,\"entries\":[{\"wire_values\":[101,1]},{\"wire_values\":[102,0]}]},\"alchemy\":{\"wire_values\":[3,3,3,0,1,3,250,2,5]},\"servants\":{\"wire_u8_prefix\":[0,2],\"servants\":{\"count\":1,\"entries\":[{\"wire_u32_1\":55,\"cstring_hex\":\"426f62\",\"wire_values_after_string\":[20,0,4000,0,0,1,0]}]},\"optional_servant\":null},\"achievements\":{\"entries\":[{\"wire_values\":[3,1,0]},{\"wire_values\":[6,1,2]},{\"wire_values\":[18,2,40]}]}}}",
        "collect_limit" to "{\"refused\":[\"You've exceeded the collection limit\",3001]}",
        "collect_kind" to "{\"refused\":[\"Invalid Collect method\",3000]}",
        "building_castle" to "{\"plan\":{\"building\":1,\"level_after\":3,\"cost\":40,\"evidence_class\":\"capture_observed_native_formula\"},\"frames\":[[640,\"010300\"],[128,\"01060828c3000000000000\"],[192,\"c900000000000000\"]],\"roles\":{\"0\":4242,\"3\":12,\"6\":49960,\"7\":9000,\"8\":120,\"17\":0,\"27\":2,\"30\":700,\"33\":0},\"state\":{\"gems\":{\"count\":1,\"entries\":[{\"wire_values\":[104,2]}]},\"buildings\":{\"count\":4,\"entries\":[{\"wire_values\":[1,3]},{\"wire_values\":[6,1]},{\"wire_values\":[7,3]},{\"wire_values\":[9,3]}]},\"technologies\":{\"count\":3,\"entries\":[{\"wire_values\":[101,1]},{\"wire_values\":[102,0]},{\"wire_values\":[201,0]}]},\"alchemy\":{\"wire_values\":[3,3,3,0,1,3,250,2,5]},\"servants\":{\"wire_u8_prefix\":[0,2],\"servants\":{\"count\":1,\"entries\":[{\"wire_u32_1\":55,\"cstring_hex\":\"426f62\",\"wire_values_after_string\":[20,0,4000,0,0,1,0]}]},\"optional_servant\":null},\"achievements\":{\"entries\":[{\"wire_values\":[3,1,0]},{\"wire_values\":[6,1,2]},{\"wire_values\":[18,2,40]}]}}}",
        "building_warehouse" to "{\"plan\":{\"building\":6,\"level_after\":2,\"cost\":15,\"item_capacity_before\":[9,50,50],\"item_capacity_after\":[22,50,50],\"evidence_class\":\"capture_observed_native_formula\"},\"frames\":[[640,\"060200\"],[128,\"01060841c3000000000000\"],[72,\"160032003200\"]],\"roles\":{\"0\":4242,\"3\":12,\"6\":49985,\"7\":9000,\"8\":120,\"17\":0,\"27\":2,\"30\":700,\"33\":0},\"state\":{\"gems\":{\"count\":1,\"entries\":[{\"wire_values\":[104,2]}]},\"buildings\":{\"count\":4,\"entries\":[{\"wire_values\":[1,2]},{\"wire_values\":[6,2]},{\"wire_values\":[7,2]},{\"wire_values\":[9,1]}]},\"technologies\":{\"count\":2,\"entries\":[{\"wire_values\":[101,1]},{\"wire_values\":[102,0]}]},\"alchemy\":{\"wire_values\":[3,3,3,0,1,3,250,2,5]},\"servants\":{\"wire_u8_prefix\":[0,2],\"servants\":{\"count\":1,\"entries\":[{\"wire_u32_1\":55,\"cstring_hex\":\"426f62\",\"wire_values_after_string\":[20,0,4000,0,0,1,0]}]},\"optional_servant\":null},\"achievements\":{\"entries\":[{\"wire_values\":[3,1,0]},{\"wire_values\":[6,1,2]},{\"wire_values\":[18,2,40]}]}}}",
        "building_house_capped" to "{\"refused\":[\"Castle Lv has to > other buildings Lv\",18003]}",
        "building_fixed" to "{\"refused\":[\"Building is locked\",18000]}",
        "tech" to "{\"plan\":{\"tech\":101,\"level_after\":2,\"cost\":90,\"evidence_class\":\"capture_observed_native_formula\"},\"frames\":[[192,\"6500000002000000\"],[128,\"010705ce220000\"],[578,\"060102000000\"]],\"roles\":{\"0\":4242,\"3\":12,\"6\":50000,\"7\":8910,\"8\":120,\"17\":0,\"27\":2,\"30\":700,\"33\":0},\"state\":{\"gems\":{\"count\":1,\"entries\":[{\"wire_values\":[104,2]}]},\"buildings\":{\"count\":4,\"entries\":[{\"wire_values\":[1,2]},{\"wire_values\":[6,1]},{\"wire_values\":[7,2]},{\"wire_values\":[9,1]}]},\"technologies\":{\"count\":2,\"entries\":[{\"wire_values\":[101,2]},{\"wire_values\":[102,0]}]},\"alchemy\":{\"wire_values\":[3,3,3,0,1,3,250,2,5]},\"servants\":{\"wire_u8_prefix\":[0,2],\"servants\":{\"count\":1,\"entries\":[{\"wire_u32_1\":55,\"cstring_hex\":\"426f62\",\"wire_values_after_string\":[20,0,4000,0,0,1,0]}]},\"optional_servant\":null},\"achievements\":{\"entries\":[{\"wire_values\":[3,1,0]},{\"wire_values\":[6,1,2]},{\"wire_values\":[18,2,40]}]}}}",
        "tech_locked" to "{\"refused\":[\"The Tech is locked\",8001]}",
        "guild_tech" to "{\"plan\":{\"tech\":102,\"level_after\":5,\"contribution\":100,\"honor\":150,\"guild_tech_state_after\":{\"profile\":\"guild_tech_state_v1\",\"in_guild\":true,\"techs\":[[102,5,10],[103,0,5]]},\"evidence_class\":\"capture_observed_native_formula\"},\"frames\":[[128,\"020705922200001e0558020000\"],[2330,\"01026600000005000a006700000000000500\"]],\"roles\":{\"0\":4242,\"3\":12,\"6\":50000,\"7\":8850,\"8\":120,\"17\":0,\"27\":2,\"30\":600,\"33\":0},\"state\":{\"gems\":{\"count\":1,\"entries\":[{\"wire_values\":[104,2]}]},\"buildings\":{\"count\":4,\"entries\":[{\"wire_values\":[1,2]},{\"wire_values\":[6,1]},{\"wire_values\":[7,2]},{\"wire_values\":[9,1]}]},\"technologies\":{\"count\":2,\"entries\":[{\"wire_values\":[101,1]},{\"wire_values\":[102,0]}]},\"alchemy\":{\"wire_values\":[3,3,3,0,1,3,250,2,5]},\"servants\":{\"wire_u8_prefix\":[0,2],\"servants\":{\"count\":1,\"entries\":[{\"wire_u32_1\":55,\"cstring_hex\":\"426f62\",\"wire_values_after_string\":[20,0,4000,0,0,1,0]}]},\"optional_servant\":null},\"achievements\":{\"entries\":[{\"wire_values\":[3,1,0]},{\"wire_values\":[6,1,2]},{\"wire_values\":[18,2,40]}]}}}",
        "guild_tech_only" to "{\"refused\":[\"You cannot upgrade this Tech yet\",52017]}",
        "transmute_triple" to "{\"plan\":{\"shards_paid\":[3,3,3],\"gold\":21955,\"castle_state_after\":{\"profile\":\"castle_state_v1\",\"day\":\"2030-03-17\",\"collected\":{\"1\":0,\"2\":0,\"4\":0},\"alchemy\":{\"refresh_until\":1900000000,\"anchor\":1900000000},\"servants\":{}},\"evidence_class\":\"capture_observed_csv_calculation_rng_policy\"},\"frames\":[[800,\"020203000000000103fa0000000105\"],[64,\"010c0000001d2500000200000000\"],[810,\"0e000000000000000000000000000000c355000000000000000000000000000000000000000000000000000000000000011d2500000200000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000\"],[128,\"0106081319010000000000\"]],\"roles\":{\"0\":4242,\"3\":12,\"6\":71955,\"7\":9000,\"8\":120,\"17\":0,\"27\":2,\"30\":700,\"33\":0},\"state\":{\"gems\":{\"count\":1,\"entries\":[{\"wire_values\":[104,2]}]},\"buildings\":{\"count\":4,\"entries\":[{\"wire_values\":[1,2]},{\"wire_values\":[6,1]},{\"wire_values\":[7,2]},{\"wire_values\":[9,1]}]},\"technologies\":{\"count\":2,\"entries\":[{\"wire_values\":[101,1]},{\"wire_values\":[102,0]}]},\"alchemy\":{\"wire_values\":[2,2,3,0,1,3,250,1,5]},\"servants\":{\"wire_u8_prefix\":[0,2],\"servants\":{\"count\":1,\"entries\":[{\"wire_u32_1\":55,\"cstring_hex\":\"426f62\",\"wire_values_after_string\":[20,0,4000,0,0,1,0]}]},\"optional_servant\":null},\"achievements\":{\"entries\":[{\"wire_values\":[3,1,0]},{\"wire_values\":[6,1,2]},{\"wire_values\":[18,2,40]}]}}}",
        "refresh_free" to "{\"plan\":{\"shards_after\":[3,2,1],\"castle_state_after\":{\"profile\":\"castle_state_v1\",\"day\":\"2030-03-17\",\"collected\":{\"1\":0,\"2\":0,\"4\":0},\"alchemy\":{\"refresh_until\":1900000900,\"anchor\":1900000000},\"servants\":{},\"refreshes\":1},\"evidence_class\":\"native_use_policy\"},\"frames\":[[800,\"030201840300000103fa0000000205\"]],\"roles\":{\"0\":4242,\"3\":12,\"6\":50000,\"7\":9000,\"8\":120,\"17\":0,\"27\":2,\"30\":700,\"33\":0},\"state\":{\"gems\":{\"count\":1,\"entries\":[{\"wire_values\":[104,2]}]},\"buildings\":{\"count\":4,\"entries\":[{\"wire_values\":[1,2]},{\"wire_values\":[6,1]},{\"wire_values\":[7,2]},{\"wire_values\":[9,1]}]},\"technologies\":{\"count\":2,\"entries\":[{\"wire_values\":[101,1]},{\"wire_values\":[102,0]}]},\"alchemy\":{\"wire_values\":[3,2,1,900,1,3,250,2,5]},\"servants\":{\"wire_u8_prefix\":[0,2],\"servants\":{\"count\":1,\"entries\":[{\"wire_u32_1\":55,\"cstring_hex\":\"426f62\",\"wire_values_after_string\":[20,0,4000,0,0,1,0]}]},\"optional_servant\":null},\"achievements\":{\"entries\":[{\"wire_values\":[3,1,0]},{\"wire_values\":[6,1,2]},{\"wire_values\":[18,2,40]}]}}}",
        "refresh_paid" to "{\"plan\":{\"shards_after\":[3,2,1],\"castle_state_after\":{\"profile\":\"castle_state_v1\",\"day\":\"2030-03-17\",\"collected\":{\"1\":1,\"2\":0,\"4\":2},\"alchemy\":{\"refresh_until\":1900000050,\"anchor\":1899999900},\"servants\":{},\"refreshes\":1},\"evidence_class\":\"native_use_policy\"},\"frames\":[[800,\"030201320000000103fa0000000405\"],[128,\"01080571000000\"],[578,\"12022f000000\"]],\"roles\":{\"0\":4242,\"3\":12,\"6\":50000,\"7\":9000,\"8\":113,\"17\":0,\"27\":2,\"30\":700,\"33\":0},\"state\":{\"gems\":{\"count\":1,\"entries\":[{\"wire_values\":[104,2]}]},\"buildings\":{\"count\":4,\"entries\":[{\"wire_values\":[1,2]},{\"wire_values\":[6,1]},{\"wire_values\":[7,2]},{\"wire_values\":[9,1]}]},\"technologies\":{\"count\":2,\"entries\":[{\"wire_values\":[101,1]},{\"wire_values\":[102,0]}]},\"alchemy\":{\"wire_values\":[3,2,1,50,1,3,250,4,5]},\"servants\":{\"wire_u8_prefix\":[0,2],\"servants\":{\"count\":1,\"entries\":[{\"wire_u32_1\":55,\"cstring_hex\":\"426f62\",\"wire_values_after_string\":[20,0,4000,0,0,1,0]}]},\"optional_servant\":null},\"achievements\":{\"entries\":[{\"wire_values\":[3,1,0]},{\"wire_values\":[6,1,2]},{\"wire_values\":[18,2,47]}]}}}",
        "buy_slot" to "{\"plan\":{\"slots_after\":4,\"price\":11,\"castle_state_after\":{\"profile\":\"castle_state_v1\",\"day\":\"2030-03-17\",\"collected\":{\"1\":0,\"2\":0,\"4\":0},\"alchemy\":{\"refresh_until\":1900000000,\"anchor\":1900000000},\"servants\":{}},\"evidence_class\":\"native_use_policy\"},\"frames\":[[128,\"0108056d000000\"],[578,\"120233000000\"],[800,\"030303000000000104fa0000000205\"]],\"roles\":{\"0\":4242,\"3\":12,\"6\":50000,\"7\":9000,\"8\":109,\"17\":0,\"27\":2,\"30\":700,\"33\":0},\"state\":{\"gems\":{\"count\":1,\"entries\":[{\"wire_values\":[104,2]}]},\"buildings\":{\"count\":4,\"entries\":[{\"wire_values\":[1,2]},{\"wire_values\":[6,1]},{\"wire_values\":[7,2]},{\"wire_values\":[9,1]}]},\"technologies\":{\"count\":2,\"entries\":[{\"wire_values\":[101,1]},{\"wire_values\":[102,0]}]},\"alchemy\":{\"wire_values\":[3,3,3,0,1,4,250,2,5]},\"servants\":{\"wire_u8_prefix\":[0,2],\"servants\":{\"count\":1,\"entries\":[{\"wire_u32_1\":55,\"cstring_hex\":\"426f62\",\"wire_values_after_string\":[20,0,4000,0,0,1,0]}]},\"optional_servant\":null},\"achievements\":{\"entries\":[{\"wire_values\":[3,1,0]},{\"wire_values\":[6,1,2]},{\"wire_values\":[18,2,51]}]}}}",
        "work" to "{\"plan\":{\"recruit\":55,\"gold\":5600,\"castle_state_after\":{\"profile\":\"castle_state_v1\",\"day\":\"2030-03-17\",\"collected\":{\"1\":0,\"2\":0,\"4\":0},\"alchemy\":null,\"servants\":{\"55\":{\"gold\":5600,\"term_until\":1900004000,\"work_until\":1900000120,\"guard_until\":1900000000}}},\"evidence_class\":\"capture_observed_native_formula\"},\"frames\":[[128,\"01060830d9000000000000\"],[814,\"0e000000000000000000000000000000e0150000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000\"],[808,\"f802000002426f62003536303000\"],[802,\"00010137000000426f62001400000000000000a00f00007800000000010000000000000000\"]],\"roles\":{\"0\":4242,\"3\":12,\"6\":55600,\"7\":9000,\"8\":120,\"17\":0,\"27\":2,\"30\":700,\"33\":0},\"state\":{\"gems\":{\"count\":1,\"entries\":[{\"wire_values\":[104,2]}]},\"buildings\":{\"count\":4,\"entries\":[{\"wire_values\":[1,2]},{\"wire_values\":[6,1]},{\"wire_values\":[7,2]},{\"wire_values\":[9,1]}]},\"technologies\":{\"count\":2,\"entries\":[{\"wire_values\":[101,1]},{\"wire_values\":[102,0]}]},\"alchemy\":{\"wire_values\":[3,3,3,0,1,3,250,2,5]},\"servants\":{\"wire_u8_prefix\":[0,1],\"servants\":{\"count\":1,\"entries\":[{\"wire_u32_1\":55,\"cstring_hex\":\"426f62\",\"wire_values_after_string\":[20,0,4000,120,0,1,0]}]},\"optional_servant\":null},\"achievements\":{\"entries\":[{\"wire_values\":[3,1,0]},{\"wire_values\":[6,1,2]},{\"wire_values\":[18,2,40]}]}}}",
        "release" to "{\"plan\":{\"recruit\":55,\"castle_state_after\":{\"profile\":\"castle_state_v1\",\"day\":\"2030-03-17\",\"collected\":{\"1\":0,\"2\":0,\"4\":0},\"alchemy\":{\"refresh_until\":1900000000,\"anchor\":1900000000},\"servants\":{}},\"evidence_class\":\"native_use_policy\"},\"frames\":[[808,\"f702000002426f62003000\"],[802,\"00020000000000\"],[800,\"030303000000000003000000000205\"]],\"roles\":{\"0\":4242,\"3\":12,\"6\":50000,\"7\":9000,\"8\":120,\"17\":0,\"27\":2,\"30\":700,\"33\":0},\"state\":{\"gems\":{\"count\":1,\"entries\":[{\"wire_values\":[104,2]}]},\"buildings\":{\"count\":4,\"entries\":[{\"wire_values\":[1,2]},{\"wire_values\":[6,1]},{\"wire_values\":[7,2]},{\"wire_values\":[9,1]}]},\"technologies\":{\"count\":2,\"entries\":[{\"wire_values\":[101,1]},{\"wire_values\":[102,0]}]},\"alchemy\":{\"wire_values\":[3,3,3,0,0,3,0,2,5]},\"servants\":{\"wire_u8_prefix\":[0,2],\"servants\":{\"count\":0,\"entries\":[]},\"optional_servant\":null},\"achievements\":{\"entries\":[{\"wire_values\":[3,1,0]},{\"wire_values\":[6,1,2]},{\"wire_values\":[18,2,40]}]}}}",
        "guard" to "{\"plan\":{\"recruit\":55,\"price\":13,\"castle_state_after\":{\"profile\":\"castle_state_v1\",\"day\":\"2030-03-17\",\"collected\":{\"1\":0,\"2\":0,\"4\":0},\"alchemy\":null,\"servants\":{\"55\":{\"gold\":0,\"term_until\":1900004000,\"work_until\":1900000000,\"guard_until\":1900004000}}},\"evidence_class\":\"native_use_policy\"},\"frames\":[[128,\"0108056b000000\"],[578,\"120235000000\"],[802,\"00020137000000426f62001400000000000000a00f0000000000000001a00f000000000000\"]],\"roles\":{\"0\":4242,\"3\":12,\"6\":50000,\"7\":9000,\"8\":107,\"17\":0,\"27\":2,\"30\":700,\"33\":0},\"state\":{\"gems\":{\"count\":1,\"entries\":[{\"wire_values\":[104,2]}]},\"buildings\":{\"count\":4,\"entries\":[{\"wire_values\":[1,2]},{\"wire_values\":[6,1]},{\"wire_values\":[7,2]},{\"wire_values\":[9,1]}]},\"technologies\":{\"count\":2,\"entries\":[{\"wire_values\":[101,1]},{\"wire_values\":[102,0]}]},\"alchemy\":{\"wire_values\":[3,3,3,0,1,3,250,2,5]},\"servants\":{\"wire_u8_prefix\":[0,2],\"servants\":{\"count\":1,\"entries\":[{\"wire_u32_1\":55,\"cstring_hex\":\"426f62\",\"wire_values_after_string\":[20,0,4000,0,0,1,4000]}]},\"optional_servant\":null},\"achievements\":{\"entries\":[{\"wire_values\":[3,1,0]},{\"wire_values\":[6,1,2]},{\"wire_values\":[18,2,53]}]}}}",
        "guard_missing" to "{\"refused\":[\"Target Recruit not found\",21007]}",
        "refresh_counted" to "{\"plan\":{\"shards_after\":[2,3,2],\"castle_state_after\":{\"profile\":\"castle_state_v1\",\"day\":\"2030-03-17\",\"collected\":{\"1\":1,\"2\":0,\"4\":2},\"alchemy\":{\"refresh_until\":1900000900,\"anchor\":1900000000},\"servants\":{},\"refreshes\":5},\"evidence_class\":\"native_use_policy\"},\"frames\":[[800,\"020302840300000103fa0000000205\"]],\"roles\":{\"0\":4242,\"3\":12,\"6\":50000,\"7\":9000,\"8\":120,\"17\":0,\"27\":2,\"30\":700,\"33\":0},\"state\":{\"gems\":{\"count\":1,\"entries\":[{\"wire_values\":[104,2]}]},\"buildings\":{\"count\":4,\"entries\":[{\"wire_values\":[1,2]},{\"wire_values\":[6,1]},{\"wire_values\":[7,2]},{\"wire_values\":[9,1]}]},\"technologies\":{\"count\":2,\"entries\":[{\"wire_values\":[101,1]},{\"wire_values\":[102,0]}]},\"alchemy\":{\"wire_values\":[2,3,2,900,1,3,250,2,5]},\"servants\":{\"wire_u8_prefix\":[0,2],\"servants\":{\"count\":1,\"entries\":[{\"wire_u32_1\":55,\"cstring_hex\":\"426f62\",\"wire_values_after_string\":[20,0,4000,0,0,1,0]}]},\"optional_servant\":null},\"achievements\":{\"entries\":[{\"wire_values\":[3,1,0]},{\"wire_values\":[6,1,2]},{\"wire_values\":[18,2,40]}]}}}",
        "work_after_expiry" to "{\"plan\":{\"recruit\":66,\"gold\":6960,\"castle_state_after\":{\"profile\":\"castle_state_v1\",\"day\":\"2030-03-17\",\"collected\":{\"1\":1,\"2\":0,\"4\":2},\"alchemy\":null,\"servants\":{\"66\":{\"gold\":6960,\"term_until\":1900005000,\"work_until\":1900000120,\"guard_until\":1900000000}}},\"evidence_class\":\"capture_observed_native_formula\"},\"frames\":[[808,\"f602000002426f62003300\"],[128,\"01060880de000000000000\"],[814,\"0e000000000000000000000000000000301b0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000\"],[808,\"f802000002416e6e003639363000\"],[802,\"00010142000000416e6e001e00000000000000881300007800000000010000000000000000\"]],\"roles\":{\"0\":4242,\"3\":12,\"6\":56960,\"7\":9000,\"8\":120,\"17\":0,\"27\":2,\"30\":700,\"33\":0},\"state\":{\"gems\":{\"count\":1,\"entries\":[{\"wire_values\":[104,2]}]},\"buildings\":{\"count\":4,\"entries\":[{\"wire_values\":[1,2]},{\"wire_values\":[6,1]},{\"wire_values\":[7,2]},{\"wire_values\":[9,1]}]},\"technologies\":{\"count\":2,\"entries\":[{\"wire_values\":[101,1]},{\"wire_values\":[102,0]}]},\"alchemy\":{\"wire_values\":[3,3,3,0,1,3,250,2,5]},\"servants\":{\"wire_u8_prefix\":[0,1],\"servants\":{\"count\":1,\"entries\":[{\"wire_u32_1\":66,\"cstring_hex\":\"416e6e\",\"wire_values_after_string\":[30,0,5000,120,0,1,0]}]},\"optional_servant\":null},\"achievements\":{\"entries\":[{\"wire_values\":[3,1,0]},{\"wire_values\":[6,1,2]},{\"wire_values\":[18,2,40]}]}}}",
        "release_after_expiry" to "{\"plan\":{\"recruit\":66,\"castle_state_after\":{\"profile\":\"castle_state_v1\",\"day\":\"2030-03-17\",\"collected\":{\"1\":1,\"2\":0,\"4\":2},\"alchemy\":{\"refresh_until\":1900000000,\"anchor\":1900000000},\"servants\":{}},\"evidence_class\":\"native_use_policy\"},\"frames\":[[808,\"f602000002426f62003300\"],[808,\"f702000002416e6e003000\"],[802,\"00020000000000\"],[800,\"030303000000000003000000000205\"]],\"roles\":{\"0\":4242,\"3\":12,\"6\":50000,\"7\":9000,\"8\":120,\"17\":0,\"27\":2,\"30\":700,\"33\":0},\"state\":{\"gems\":{\"count\":1,\"entries\":[{\"wire_values\":[104,2]}]},\"buildings\":{\"count\":4,\"entries\":[{\"wire_values\":[1,2]},{\"wire_values\":[6,1]},{\"wire_values\":[7,2]},{\"wire_values\":[9,1]}]},\"technologies\":{\"count\":2,\"entries\":[{\"wire_values\":[101,1]},{\"wire_values\":[102,0]}]},\"alchemy\":{\"wire_values\":[3,3,3,0,0,3,0,2,5]},\"servants\":{\"wire_u8_prefix\":[0,2],\"servants\":{\"count\":0,\"entries\":[]},\"optional_servant\":null},\"achievements\":{\"entries\":[{\"wire_values\":[3,1,0]},{\"wire_values\":[6,1,2]},{\"wire_values\":[18,2,40]}]}}}",
        "guard_after_expiry" to "{\"plan\":{\"recruit\":66,\"price\":13,\"castle_state_after\":{\"profile\":\"castle_state_v1\",\"day\":\"2030-03-17\",\"collected\":{\"1\":1,\"2\":0,\"4\":2},\"alchemy\":null,\"servants\":{\"66\":{\"gold\":0,\"term_until\":1900005000,\"work_until\":1900000000,\"guard_until\":1900005000}}},\"evidence_class\":\"native_use_policy\"},\"frames\":[[808,\"f602000002426f62003300\"],[128,\"0108056b000000\"],[578,\"120235000000\"],[802,\"00020142000000416e6e001e00000000000000881300000000000000018813000000000000\"]],\"roles\":{\"0\":4242,\"3\":12,\"6\":50000,\"7\":9000,\"8\":107,\"17\":0,\"27\":2,\"30\":700,\"33\":0},\"state\":{\"gems\":{\"count\":1,\"entries\":[{\"wire_values\":[104,2]}]},\"buildings\":{\"count\":4,\"entries\":[{\"wire_values\":[1,2]},{\"wire_values\":[6,1]},{\"wire_values\":[7,2]},{\"wire_values\":[9,1]}]},\"technologies\":{\"count\":2,\"entries\":[{\"wire_values\":[101,1]},{\"wire_values\":[102,0]}]},\"alchemy\":{\"wire_values\":[3,3,3,0,1,3,250,2,5]},\"servants\":{\"wire_u8_prefix\":[0,2],\"servants\":{\"count\":1,\"entries\":[{\"wire_u32_1\":66,\"cstring_hex\":\"416e6e\",\"wire_values_after_string\":[30,0,5000,0,0,1,5000]}]},\"optional_servant\":null},\"achievements\":{\"entries\":[{\"wire_values\":[3,1,0]},{\"wire_values\":[6,1,2]},{\"wire_values\":[18,2,53]}]}}}",
        "grant_currency" to "{\"plan\":{},\"frames\":[[68,\"010b00000008000000\"],[64,\"010c0000005c1b00000100000000\"],[128,\"0106084ac4000000000000\"]],\"roles\":{\"0\":4242,\"3\":12,\"6\":50250,\"7\":9000,\"8\":120,\"17\":0,\"27\":2,\"30\":700,\"33\":0},\"state\":{\"gems\":{\"count\":1,\"entries\":[{\"wire_values\":[104,2]}]},\"buildings\":{\"count\":4,\"entries\":[{\"wire_values\":[1,2]},{\"wire_values\":[6,1]},{\"wire_values\":[7,2]},{\"wire_values\":[9,1]}]},\"technologies\":{\"count\":2,\"entries\":[{\"wire_values\":[101,1]},{\"wire_values\":[102,0]}]},\"alchemy\":{\"wire_values\":[3,3,3,0,1,3,250,2,5]},\"servants\":{\"wire_u8_prefix\":[0,2],\"servants\":{\"count\":1,\"entries\":[{\"wire_u32_1\":55,\"cstring_hex\":\"426f62\",\"wire_values_after_string\":[20,0,4000,0,0,1,0]}]},\"optional_servant\":null},\"achievements\":{\"entries\":[{\"wire_values\":[3,1,0]},{\"wire_values\":[6,1,2]},{\"wire_values\":[18,2,40]}]}}}",
        "gear_sacrifice" to "{\"plan\":{\"kind\":\"gear\",\"mode\":\"sacrifice\",\"uid\":21,\"template\":4001,\"level\":4,\"grade\":2,\"price\":51,\"returns\":{\"items\":[[0,0],[7001,1],[7002,2],[7003,8],[7004,3],[7005,1]],\"heroes\":[],\"equips\":[4001],\"jewels\":[],\"gold\":0,\"soul_hero\":0,\"soul_equip\":0,\"soul_jewel\":0,\"trace\":[[\"evolve\",7004,3],[\"evolve\",7005,1],[\"evolve\",0,0],[\"upgrade\",7001,1],[\"upgrade\",7002,2],[\"upgrade\",7003,8]],\"evidence_class\":\"native_use_calculation_capture_confirmed\"},\"new_uid\":23,\"evidence_class\":\"native_use_calculation_capture_confirmed\"},\"frames\":[[68,\"010b00000006000000\"],[64,\"040c0000005a1b000002000000000d0000005b1b000008000000000e0000005c1b000003000000000f0000005d1b00000100000000\"],[96,\"0117000000a10f00000100000000000000010000000000\"],[100,\"0117000000\"],[546,\"02a10f0000\"],[578,\"030101000000\"],[128,\"01110501000000\"],[578,\"12025b000000\"],[102,\"0115000000\"],[98,\"0115000000\"],[3722,\"0e0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000060000000000000000591b0000010000005a1b0000020000005b1b0000080000005c1b0000030000005d1b0000010000000001a10f0000000000000000000000000000000000000001000000000000000001000000000000000000000000000000000000000000000000\"],[128,\"01080545000000\"]],\"roles\":{\"0\":4242,\"3\":12,\"6\":50000,\"7\":9000,\"8\":69,\"17\":1,\"27\":2,\"30\":700,\"33\":0},\"state\":{\"gems\":{\"count\":1,\"entries\":[{\"wire_values\":[104,2]}]},\"buildings\":{\"count\":4,\"entries\":[{\"wire_values\":[1,2]},{\"wire_values\":[6,1]},{\"wire_values\":[7,2]},{\"wire_values\":[9,1]}]},\"technologies\":{\"count\":2,\"entries\":[{\"wire_values\":[101,1]},{\"wire_values\":[102,0]}]},\"alchemy\":{\"wire_values\":[3,3,3,0,1,3,250,2,5]},\"servants\":{\"wire_u8_prefix\":[0,2],\"servants\":{\"count\":1,\"entries\":[{\"wire_u32_1\":55,\"cstring_hex\":\"426f62\",\"wire_values_after_string\":[20,0,4000,0,0,1,0]}]},\"optional_servant\":null},\"achievements\":{\"entries\":[{\"wire_values\":[3,1,1]},{\"wire_values\":[6,1,2]},{\"wire_values\":[18,2,91]}]}}}",
        "gear_reforge" to "{\"plan\":{\"kind\":\"gear\",\"mode\":\"reforge\",\"uid\":21,\"template\":4001,\"level\":4,\"grade\":2,\"price\":51,\"returns\":{\"items\":[[0,0],[7001,1],[7002,2],[7003,8],[7004,3],[7005,1]],\"heroes\":[],\"equips\":[],\"jewels\":[],\"gold\":0,\"soul_hero\":0,\"soul_equip\":45,\"soul_jewel\":0,\"trace\":[[\"evolve\",7004,3],[\"evolve\",7005,1],[\"evolve\",0,0],[\"upgrade\",7001,1],[\"upgrade\",7002,2],[\"upgrade\",7003,8]],\"evidence_class\":\"native_use_calculation_capture_confirmed\"},\"new_uid\":null,\"evidence_class\":\"native_use_calculation_capture_confirmed\"},\"frames\":[[68,\"010b00000006000000\"],[64,\"040c0000005a1b000002000000000d0000005b1b000008000000000e0000005c1b000003000000000f0000005d1b00000100000000\"],[128,\"0121052d000000\"],[578,\"12025b000000\"],[102,\"0115000000\"],[98,\"0115000000\"],[3722,\"0e0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000060000000000000000591b0000010000005a1b0000020000005b1b0000080000005c1b0000030000005d1b000001000000000000000000000000000000000000000000000000000000000000000000000000000000002d0000000000000000000000\"],[128,\"01080545000000\"]],\"roles\":{\"0\":4242,\"3\":12,\"6\":50000,\"7\":9000,\"8\":69,\"17\":0,\"27\":2,\"30\":700,\"33\":45},\"state\":{\"gems\":{\"count\":1,\"entries\":[{\"wire_values\":[104,2]}]},\"buildings\":{\"count\":4,\"entries\":[{\"wire_values\":[1,2]},{\"wire_values\":[6,1]},{\"wire_values\":[7,2]},{\"wire_values\":[9,1]}]},\"technologies\":{\"count\":2,\"entries\":[{\"wire_values\":[101,1]},{\"wire_values\":[102,0]}]},\"alchemy\":{\"wire_values\":[3,3,3,0,1,3,250,2,5]},\"servants\":{\"wire_u8_prefix\":[0,2],\"servants\":{\"count\":1,\"entries\":[{\"wire_u32_1\":55,\"cstring_hex\":\"426f62\",\"wire_values_after_string\":[20,0,4000,0,0,1,0]}]},\"optional_servant\":null},\"achievements\":{\"entries\":[{\"wire_values\":[3,1,0]},{\"wire_values\":[6,1,2]},{\"wire_values\":[18,2,91]}]}}}",
        "gear_worn" to "{\"refused\":[\"Equipped gear cannot be reset\",70408]}",
    )

    private val inputs = DailyInputs(GameTables(TextTables(tables)))
    private val now = 1_900_000_000L

    @BeforeEach
    fun clock() {
        DeviceClock.active = DeviceClock(null, timeSource = { 0L }, offsetSource = { 0 })
    }

    @AfterEach
    fun reset() {
        DeviceClock.active = null
    }

    private fun field(id: Long, bits: Long, tag: Int = 5) = jobj("id" to id, "value" to jobj("tag" to tag, "bits" to bits))

    private fun wire(vararg values: Long) = jobj("wire_values" to values.toList())

    private fun state(): JObj = jobj(
        "role_properties" to jarr(field(0, 4242), field(3, 12), field(6, 50000, 8), field(7, 9000), field(8, 120), field(17, 0),
            field(27, 2), field(30, 700), field(33, 0)),
        "items" to jarr(jobj("wire_values" to jarr(11, 7001, 5), "timed_flag" to 0)), "item_capacity_values" to jarr(9, 50, 50),
        "equipment" to jarr(jobj("offset" to null, "wire_values" to jarr(21, 4001, 4, 30, 2, 0, 0)),
            jobj("offset" to null, "wire_values" to jarr(22, 4001, 3, 0, 1, 0, 0))),
        "bag_equipment_uids" to jarr(21), "heroes" to JArr(), "offline_hero_uids" to JArr(), "formation" to JArr(), "servant_messages" to JArr(),
        "subsystems" to jobj(
            "achievements" to jobj("entries" to jarr(wire(3, 1, 0), wire(6, 1, 2), wire(18, 2, 40))),
            "game_activities" to jobj("first_list" to jobj("count" to 0, "entries" to JArr())),
            "equip_collection" to jobj("count" to 0, "entries" to JArr()),
            "gems" to jobj("count" to 1, "entries" to jarr(wire(104, 2))),
            "buildings" to jobj("count" to 4, "entries" to jarr(wire(1, 2), wire(6, 1), wire(7, 2), wire(9, 1))),
            "technologies" to jobj("count" to 2, "entries" to jarr(wire(101, 1), wire(102, 0))),
            "alchemy" to jobj("wire_values" to jarr(3, 3, 3, 0, 1, 3, 250, 2, 5)),
            "servants" to jobj("wire_u8_prefix" to jarr(0, 2), "servants" to jobj("count" to 1, "entries" to jarr(
                jobj("wire_u32_1" to 55, "cstring_hex" to "426f62", "wire_values_after_string" to jarr(20, 0, 4000, 0, 0, 1, 0)))),
                "optional_servant" to null)))

    private fun current(): StateStore.Current =
        StateStore.Current(3, "", "", state(), ByteArray(0), 0, null, JArr(), emptyList(), JArr(), emptyList(), null, null, null, null, null,
            LinkedHashMap<String, JValue?>()).also { it.jewelEntriesView = JArr() }

    private val guild = jobj("profile" to Castle.GUILD_TECH_PROFILE, "in_guild" to true, "techs" to jarr(jarr(102, 4, 10), jarr(103, 0, 5)))

    private fun castleDoc(collected: JObj = jobj("1" to 1, "2" to 0, "4" to 2), alchemy: JValue = JNull) =
        jobj("profile" to Castle.CASTLE_PROFILE, "day" to "2030-03-17", "collected" to collected, "alchemy" to alchemy, "servants" to JObj())

    /** Shards 1, 2, 3 (not all at the top). */
    private fun mixed(owned: Owned) {
        val values = owned.state.obj("subsystems").obj("alchemy").arr("wire_values")
        for ((i, v) in listOf(1L, 2L, 3L).withIndex()) values[i] = JInt(v)
    }

    /** A second recruit (66); the first (55) has a term that ended before the action. */
    private fun second(owned: Owned) {
        val servants = owned.state.obj("subsystems").obj("servants").obj("servants")
        servants.arr("entries").add(jobj("wire_u32_1" to 66, "cstring_hex" to "416e6e", "wire_values_after_string" to jarr(30, 0, 5000, 0, 0, 1, 0)))
        servants["count"] = JInt(2)
    }

    private fun expired() = castleDoc().also {
        it["servants"] = jobj("55" to jobj("gold" to 3, "term_until" to now - 1, "work_until" to now, "guard_until" to now))
    }

    private val cases: List<Pair<String, (Owned, StateStore.Current) -> Plan>> = listOf(
        "collect_gold" to { o, _ -> Castle.planCollect(1, o, inputs, castleDoc(), guild, now, now, "char_m") },
        "collect_all" to { o, _ -> Castle.planCollect(7, o, inputs, castleDoc(), guild, now, now, "char_m") },
        "collect_runes_new_day" to { o, _ -> Castle.planCollect(4, o, inputs, null, JObj(), now, now, "char_m") },
        "collect_limit" to { o, _ -> Castle.planCollect(2, o, inputs, castleDoc(jobj("1" to 0, "2" to 3, "4" to 0)), guild, now, now, "char_m") },
        "collect_kind" to { o, _ -> Castle.planCollect(3, o, inputs, null, guild, now, now, "char_m") },
        "building_castle" to { o, _ -> Castle.planBuilding(1, o, inputs) },
        "building_warehouse" to { o, _ -> Castle.planBuilding(6, o, inputs) },
        "building_house_capped" to { o, _ -> Castle.planBuilding(7, o, inputs) },
        "building_fixed" to { o, _ -> Castle.planBuilding(2, o, inputs) },
        "tech" to { o, _ -> Castle.planTech(101, o, inputs) },
        "tech_locked" to { o, _ -> Castle.planTech(201, o, inputs) },
        "guild_tech" to { o, _ -> Castle.planGuildTech(102, o, inputs, guild) },
        "guild_tech_only" to { o, _ -> Castle.planGuildTech(103, o, inputs, guild) },
        "transmute_triple" to { o, _ -> Castle.planTransmute(o, inputs, null, guild, now, "char_m") },
        "refresh_free" to { o, _ -> mixed(o); Castle.planAlchemyRefresh(o, inputs, null, now, now, "char_m") },
        "refresh_paid" to { o, _ ->
            mixed(o)
            Castle.planAlchemyRefresh(o, inputs, castleDoc(alchemy = jobj("refresh_until" to now + 50, "anchor" to now - 1300)), now, now, "char_m")
        },
        "buy_slot" to { o, _ -> Castle.planBuySlot(o, inputs, null, now, now) },
        "work" to { o, _ -> Castle.planWork(55, o, inputs, null, now) },
        "release" to { o, _ -> Castle.planRelease(55, o, inputs, null, now) },
        "guard" to { o, _ -> Castle.planGuard(55, o, inputs, null, now, now) },
        "guard_missing" to { o, _ -> Castle.planGuard(56, o, inputs, null, now, now) },
        "refresh_counted" to { o, _ ->
            mixed(o)
            Castle.planAlchemyRefresh(o, inputs, castleDoc().also { it["refreshes"] = JInt(4) }, now, now, "char_m")
        },
        "work_after_expiry" to { o, _ -> second(o); Castle.planWork(66, o, inputs, expired(), now) },
        "release_after_expiry" to { o, _ -> second(o); Castle.planRelease(66, o, inputs, expired(), now) },
        "guard_after_expiry" to { o, _ -> second(o); Castle.planGuard(66, o, inputs, expired(), now, now) },
        "grant_currency" to { o, _ -> Plan(JObj(), CardReset.grantAll(o, listOf(20001L to 250L, 20003L to 0L, 7001L to 3L, 7004L to 1L))) },
        "gear_sacrifice" to { o, c -> CardReset.planReset(3725, jobj("uid" to 21, "mode" to 0), o, inputs, c, now) },
        "gear_reforge" to { o, c -> CardReset.planReset(3725, jobj("uid" to 21, "mode" to 1), o, inputs, c, now) },
        "gear_worn" to { o, c -> CardReset.planReset(3725, jobj("uid" to 22, "mode" to 0), o, inputs, c, now) },
    )

    private fun compact(v: JValue): String = Json.dumps(v, itemSeparator = ",", keySeparator = ":")

    private fun outcome(case: (Owned, StateStore.Current) -> Plan): String {
        val cur = current()
        val owned = Owned(cur, inputs)
        return try {
            val plan = case(owned, cur)
            val roles = JObj()
            for (f in owned.state.arr("role_properties")) roles[f.asObj["id"].toString()] = f.asObj.obj("value")["bits"]!!
            val sections = JObj()
            for (k in listOf("gems", "buildings", "technologies", "alchemy", "servants", "achievements")) sections[k] = owned.state.obj("subsystems")[k]!!
            compact(jobj("plan" to plan.data, "frames" to plan.packets.map { jarr(it.first, it.second.toHexString()) }, "roles" to roles,
                "state" to sections))
        } catch (e: Acquisition.Rejected) {
            compact(jobj("refused" to jarr(e.message, e.code)))
        }
    }

    @Test
    fun `Castle actions and Card Sacrifice on made-up tables`() {
        for ((name, case) in cases) assertEquals(expected.getValue(name), outcome(case), name)
    }

    @Test
    fun `decoders and formulas`() {
        assertEquals(7L, Castle.decodeU8(byteArrayOf(7), 161))
        assertEquals(0x04030201L, Castle.decodeU32(byteArrayOf(1, 2, 3, 4), 97))
        assertEquals("C161 is u8", assertThrows(Acquisition.Rejected::class.java) { Castle.decodeU8(byteArrayOf(), 161) }.message)
        assertEquals("C97 is u32", assertThrows(Acquisition.Rejected::class.java) { Castle.decodeU32(byteArrayOf(1), 97) }.message)
        assertEquals("{\"uid\":258,\"mode\":1}", compact(CardReset.decodeReset(byteArrayOf(2, 1, 0, 0, 1), 3725)))
        assertThrows(Acquisition.Rejected::class.java) { CardReset.decodeReset(byteArrayOf(2, 1, 0, 0, 2), 3725) }
        // factor × (L + max(0, L−10) + 2·[L > 17] + max(0, L−20) + max(0, L−30) + 18·[L > 50])
        assertEquals(listOf(0L, 7L, 70L, 7L * (18 + 8 + 2), 7L * (51 + 41 + 2 + 31 + 21 + 18)),
            listOf(0L, 1L, 10L, 18L, 51L).map { Castle.buildingCost(7, it) })
        assertEquals(listOf(3L * 2, 3L * 51, 3L * 52, 3L * 102), listOf(0L, 49L, 50L, 75L).map { Castle.techCost(3, it) })
        assertEquals(BigInteger.valueOf(4), Castle.guildGoldBonus(guild))
        assertEquals(BigInteger.ZERO, Castle.guildGoldBonus(null))
        assertEquals(51L, CardReset.resetDiamonds(inputs, "gear", 3, 4, 2, 0))
    }
}
