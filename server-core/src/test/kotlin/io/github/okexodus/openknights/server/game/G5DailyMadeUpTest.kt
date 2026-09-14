package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.toHexString
import io.github.okexodus.openknights.gamedata.GameTables
import io.github.okexodus.openknights.gamedata.TableSource
import io.github.okexodus.openknights.protocol.WireWriter
import io.github.okexodus.openknights.server.DeviceClock
import io.github.okexodus.openknights.server.game.WorldParticipants.Participant
import io.github.okexodus.openknights.server.store.StateStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigInteger

/**
 * CI checks of the daily slice on made-up tables and a made-up save: each expected value is the reference's output for
 * the same made-up input. The full proof against the recorded saves and the APK tables is [G5DailyVectorsTest] (local).
 */
class G5DailyMadeUpTest {
    private class TextTables(private val files: Map<String, String>) : TableSource {
        override fun names() = files.keys.sorted()
        override fun raw(name: String): ByteArray {
            val file = if (name.endsWith(".csv")) name else "$name.csv"
            return (files[file] ?: throw NoSuchElementException("Missing table: $file")).toByteArray()
        }
    }

    private val inputs = DailyInputs(GameTables(TextTables(mapOf(
        "item.csv" to "101,102,104,105,106,107,110,203,204,205,206,207,209,210,211,212,305\n" +
            "9201,1,2,0,0,0,0,0,0,0,0,,0,0,0,0,1\n9202,2,2,0,0,0,0,0,0,0,0,,0,0,0,0,1\n9203,3,2,0,0,0,0,0,0,0,0,,0,0,0,0,1\n" +
            "9204,4,2,0,0,0,0,0,0,0,0,,1,25,2,6,1\n9205,5,2,0,0,0,0,0,0,0,0,,1,3,1,0,1\n",
        "timegift.csv" to "101,102,103,104\n1,60,1,9201\n2,120,1,9202\n3,240,1,9201\n4,480,1,9203\n",
        "qiandao.csv" to "101,102,104,201,106,202,108,203,110,204\n1,2,9201,3,20001,700,0,0,0,0\n2,3,20003,4,9202,1,0,0,0,0\n",
        "title.csv" to "101,106,107\n7,12345,6\n8,20000,0\n",
        "dailyactivities.csv" to "101,103,104\n31,5,2\n32,1,10\n33,3,4\n",
        "dailyactivties_gift.csv" to "101,102,103,104,105\n41,10,1,9202,2\n42,20,1,20001,999\n43,30,1,20003,7\n",
        "lv_yijiezhimen.csv" to "101,102,103,104,105,106,107,108,109,110,111,112,113,114\n" +
            "1,100,1,9201,1,1,20003,2,1,9202,5,0,0,0\n2,,1,9201,2,0,0,0,1,9202,9,0,0,0\n",
        "quest_yijiezhimen.csv" to "101,102,103,104,107\n1,2,1,10,9203\n",
        "property.csv" to "101,102\n908,3\n"))))

    private val now = 1_700_050_000L
    private val day = "2023-11-15"

    @BeforeEach
    fun clock() {
        DeviceClock.active = DeviceClock(null, timeSource = { 0L }, offsetSource = { 3600 })
    }

    @AfterEach
    fun reset() {
        DeviceClock.active = null
    }

    private fun field(id: Int, tag: Int, bits: Long) = jobj("id" to id, "value" to jobj("tag" to tag, "bits" to bits))

    private fun item(uid: Long, template: Long, count: Long) = jobj("wire_values" to jarr(uid, template, count), "timed_flag" to 0)

    private fun current(docs: Map<String, JValue> = emptyMap()): StateStore.Current {
        val state = jobj("role_properties" to jarr(field(0, 4, 9001), jobj("id" to 2, "value" to jobj("tag" to 7, "raw_hex" to "4b6e69676874")),
            field(3, 4, 12), field(6, 8, 50000), field(8, 4, 40), field(22, 4, 7), field(26, 4, 1)),
            "items" to jarr(item(11, 9201, 5), item(12, 9204, 4), item(13, 9204, 3)), "item_capacity_values" to jarr(50, 50, 50),
            "heroes" to JArr(), "formation" to JArr(), "subsystems" to JObj(), "title_reward_flag" to 0)
        return StateStore.Current(3, "", "", state, ByteArray(0), 0, null, JArr(), emptyList(), JArr(), emptyList(), null, null, null,
            null, null, LinkedHashMap<String, JValue?>(docs))
    }

    private fun compact(v: JValue): String = Json.dumps(v, itemSeparator = ",", keySeparator = ":")

    private fun frames(packets: List<Frame>) = JArr(packets.mapTo(ArrayList()) { jarr(it.first, it.second.toHexString()) })

    /** The exporter's record of one planned request: the plan and its frames and Owned changes, or the refusal. */
    private fun plan(opcode: Int, payload: ByteArray, docs: Map<String, JValue> = emptyMap()): String {
        val out = try {
            val routed = DailyRoutes.plannerFor(opcode, payload, inputs, null, now, { now }, ownerKey = "char_x")
            val cur = current(docs)
            val owned = Owned(cur, inputs)
            val plan = routed.planner(owned, cur)
            jobj("action" to routed.action, "request" to routed.request, "plan" to plan.data, "frames" to frames(plan.packets),
                "roles" to owned.roleChanges.map { (f, c) -> jarr(f, c.first, c.second) },
                "items" to owned.itemChanges.map { (u, c) -> jarr(u, c) },
                "new" to owned.newItems.map { (u, e) -> jarr(u, e.first, e.second) })
        } catch (e: Acquisition.Rejected) {
            jobj("error" to e.message, "code" to e.code)
        }
        return compact(out)
    }

    private fun sign(row: Long, availableAt: Long, signed: List<Long> = emptyList()) = jobj("profile" to Daily.SIGN_PROFILE, "month" to "2023-11",
        "signed" to signed, "chain_day" to day, "row" to row, "available_at" to availableAt, "seed" to null)

    private fun u32(vararg values: Long): ByteArray = WireWriter().also { w -> values.forEach { w.number('I', it) } }.bytes()

    private val door = jobj("profile" to Daily.DOOR_PROFILE, "day" to day, "daily_claimed" to false, "level_up_claimed" to 0, "tasks" to listOf(2, 5),
        "donated" to jobj("9204" to 1), "gold_donated" to 1)

    private val expected = mapOf(
        "check_in" to """{"action":"daily_check_in","request":{},"plan":{"check_ins":2,"milestone":1,"sign_in_state_after":{"profile":"sign_in_state_v1","month":"2023-11","signed":[1,15],"chain_day":"2023-11-15","row":1,"available_at":1700049999,"seed":null},"evidence_class":"capture_observed_csv_calculation","now_epoch":1700050000},"frames":[[68,"010b00000008000000"],[128,"0106080cc6000000000000"],[1156,"0e000000000000000000000000000000bc0200000000000000000000000000000000000000000000000000000000000001f12300000300000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"],[1152,"0a1e030f02010f"]],"roles":[[6,50000,50700]],"items":[[11,8]],"new":[]}""",
        "check_in_again" to """{"error":"Already checked in today","code":23000}""",
        "time_gift" to """{"action":"daily_time_gift","request":{},"plan":{"row":2,"item":9202,"sign_in_state_after":{"profile":"sign_in_state_v1","month":"2023-11","signed":[],"chain_day":"2023-11-15","row":3,"available_at":1700050240,"seed":null},"evidence_class":"capture_observed_count_policy","now_epoch":1700050000},"frames":[[64,"010e000000f22300000100000000"],[1156,"0e000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001f22300000100000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"],[1154,"03f0000000"]],"roles":[],"items":[],"new":[[14,9202,1]]}""",
        "time_gift_last" to """{"action":"daily_time_gift","request":{},"plan":{"row":4,"item":9203,"sign_in_state_after":{"profile":"sign_in_state_v1","month":"2023-11","signed":[],"chain_day":"2023-11-15","row":5,"available_at":0,"seed":null},"evidence_class":"capture_observed_count_policy","now_epoch":1700050000},"frames":[[64,"010e000000f32300000100000000"],[1156,"0e000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001f32300000100000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"],[1154,"05ffffffff"]],"roles":[],"items":[],"new":[[14,9203,1]]}""",
        "time_gift_wait" to """{"error":"The timed gift is not ready","code":23002}""",
        "salary" to """{"action":"daily_salary","request":{},"plan":{"title":7,"gold":12345,"diamond":6,"salary_state_after":{"profile":"salary_state_v1","claim_day":"2023-11-15"},"evidence_class":"capture_observed_csv_calculation","now_epoch":1700050000},"frames":[[512,"0e00000000000000000000000000000039300000000000000600000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"],[128,"02060889f300000000000008042e00"]],"roles":[[6,50000,62345],[8,40,46]],"items":[],"new":[]}""",
        "salary_again" to """{"error":"Today's salary was already claimed","code":16000}""",
        "mission_points" to """28""",
        "mission_43" to """{"error":"This Daily Mission gift is not claimable","code":30000}""",
        "mission_42" to """{"action":"daily_mission_gift","request":{"gift":42},"plan":{"gift":42,"daily_mission_state_after":{"profile":"daily_mission_state_v1","day":"2023-11-15","counts":{"31":9,"32":1,"33":2},"claimed":[41,42]},"evidence_class":"native_use_order_policy","now_epoch":1700050000},"frames":[[128,"01060837c7000000000000"],[2914,"2a000000"]],"roles":[[6,50000,50999]],"items":[],"new":[]}""",
        "mission_41" to """{"error":"This Daily Mission gift is not claimable","code":30000}""",
        "door_daily" to """{"action":"royal_door_daily","request":{"kind":"daily"},"plan":{"kind":"daily","door_level":1,"royal_door_state_after":{"profile":"royal_door_state_v1","day":"2023-11-15","daily_claimed":true,"level_up_claimed":0,"tasks":[2,5],"donated":{"9204":1},"gold_donated":1},"evidence_class":"capture_observed_csv_calculation","now_epoch":1700050000},"frames":[[68,"010b00000006000000"],[128,"0108042a00"],[2724,"0e000000000000000000000000000000000000000000000002000000000000000000000000000000000000000000000001f12300000100000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"],[2720,"0000000000000000010000000001020205"]],"roles":[[8,40,42]],"items":[[11,6]],"new":[]}""",
        "door_level_up" to """{"action":"royal_door_level_up","request":{"kind":"level_up"},"plan":{"kind":"level_up","door_level":1,"royal_door_state_after":{"profile":"royal_door_state_v1","day":"2023-11-15","daily_claimed":false,"level_up_claimed":1,"tasks":[2,5],"donated":{"9204":1},"gold_donated":1},"evidence_class":"capture_observed_csv_calculation","now_epoch":1700050000},"frames":[[64,"010e000000f22300000500000000"],[2726,"0e000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001f22300000500000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"],[2720,"0000000000000000010000000100020205"]],"roles":[],"items":[],"new":[[14,9202,5]]}""",
        "donate_gold" to """{"action":"royal_door_donate","request":{"form":"gold","units":2},"plan":{"form":"gold","essence":2,"door_exp":20,"door_level_after":1,"royal_door_state_after":{"profile":"royal_door_state_v1","day":"2023-11-15","daily_claimed":false,"level_up_claimed":0,"tasks":[2,5],"donated":{"9204":1},"gold_donated":3},"evidence_class":"native_use_csv_policy_order","now_epoch":1700050000},"frames":[[128,"0106083075000000000000"],[64,"010e000000f32300000200000000"],[2728,"0200000014000000"],[2722,"01000000f42300000100000003000000"],[2720,"1400000000000000010000000101020205"]],"roles":[[6,50000,30000]],"items":[],"new":[[14,9203,2]]}""",
        "donate_gold_limit" to """{"error":"Today's Gold donation limit is reached","code":57005}""",
        "donate_items" to """{"error":"Today's donation limit of this item is reached","code":57004}""",
        "donate_items_ok" to """{"action":"royal_door_donate","request":{"form":"items","items":[[9204,5]]},"plan":{"form":"items","essence":10,"door_exp":125,"door_level_after":2,"royal_door_state_after":{"profile":"royal_door_state_v1","day":"2023-11-15","daily_claimed":false,"level_up_claimed":0,"tasks":[2,5],"donated":{"9204":6},"gold_donated":1},"evidence_class":"native_use_csv_policy_order","now_epoch":1700050000},"frames":[[66,"010c000000"],[68,"010d00000002000000"],[64,"010e000000f32300000a00000000"],[2728,"0a0000007d000000"],[2722,"01000000f42300000600000001000000"],[2720,"1900000000000000020000000101020205"]],"roles":[],"items":[[12,0],[13,2]],"new":[[14,9203,10]]}""",
        "donate_not_today" to """{"error":"The Donate task is not one of today's Royal Door tasks","code":57008}""",
        "door_tasks_unborn" to """[1,2,3,4]""",
        "arena" to """{"ranks":[9002,9502,9500,9501,9001],"changed":true,"rows":[[1,9002],[2,9502],[3,9500],[4,9501]],"settle":1699995600,"view":{"profile":"arena_state_v1","joined_at":0,"settled_at":1699995600,"reward_rank":5,"claimed":0,"history":[{"name_hex":"5a6564","attacker":1,"result":0,"rank":2,"trend":-1}]},"info":"050000000a0000000a00000000000000000005000000015a656400010002000000ff042a2300005a6564001e000000010000000000000000000000000000001e2500004576650008000000020000000900000000000000400000001c250000426f740005000000030000000000000000010000420000001d250000426f62000c00000004000000050000000000000041000000","top":"052a2300005a6564001e0000000000000000000000001e25000045766500080000000900000000000000001c250000426f7400050000000000000000010000001d250000426f62000c000000050000000000000000292300004b6e69676874000c000000bc0200000000000001","catch":"0505031d250000426f62000c00000041000000001e250000457665000800000040000000001c250000426f7400050000004200000000012a2300005a6564001e000000000000000000","joined":1700006400}""",
        "query_417" to """[[448,"010000000a0000000a000000000000000000010000000000"]]""",
        "query_421" to """[[450,"01292300004b6e69676874000c000000e11000000000000001"]]""",
        "query_753" to """[[804,"05050000"]]""",
        "fixes" to """{"door_row":["No lv_yijiezhimen row for this Door level",57001],"ladder_twice":[[9002,9001,9502,9500,9501],true],"catch_twin":"0505031d250000426f62000c00000041000000001e250000457665000800000040000000001c250000426f7400050000004200000000012a2300005a6564001e000000000000000000","settle_switch":1699995600,"zone_day":["2023-11-15","2023-11","0a1e030f0103"],"zone_sign":["Already checked in today",23000]}""",
    )

    @Test
    fun `check-in milestones and the timed gift chain`() {
        val first = plan(Daily.C_SIGN_MONTH, ByteArray(0), mapOf("sign_in_state" to sign(1, now - 1, listOf(1))))
        assertEquals(expected["check_in"], first)
        val after = Json.loads(first) as JObj
        assertEquals(expected["check_in_again"], plan(Daily.C_SIGN_MONTH, ByteArray(0), mapOf("sign_in_state" to after.obj("plan").obj("sign_in_state_after"))))
        assertEquals(expected["time_gift"], plan(Daily.C_SIGN_GIFT, ByteArray(0), mapOf("sign_in_state" to sign(2, now))))
        assertEquals(expected["time_gift_last"], plan(Daily.C_SIGN_GIFT, ByteArray(0), mapOf("sign_in_state" to sign(4, now - 9))))
        assertEquals(expected["time_gift_wait"], plan(Daily.C_SIGN_GIFT, ByteArray(0), mapOf("sign_in_state" to sign(3, now + 9))))
    }

    @Test
    fun `salary and the Daily Mission gifts`() {
        assertEquals(expected["salary"], plan(Daily.C_SALARY, ByteArray(0)))
        assertEquals(expected["salary_again"], plan(Daily.C_SALARY, ByteArray(0), mapOf("salary_state" to jobj("profile" to Daily.SALARY_PROFILE, "claim_day" to day))))
        val mission = jobj("profile" to Daily.MISSION_PROFILE, "day" to day, "counts" to jobj("31" to 9, "32" to 1, "33" to 2), "claimed" to listOf(41))
        assertEquals(expected["mission_points"], Daily.missionPoints(mission, inputs).toString())
        for (gift in listOf(43L, 42L, 41L)) {
            assertEquals(expected["mission_$gift"], plan(Daily.C_MISSION_GIFT, u32(gift), mapOf("daily_mission_state" to mission)))
        }
    }

    @Test
    fun `Royal Door claims and donations`() {
        val docs = mapOf("royal_door_state" to door)
        assertEquals(expected["door_daily"], plan(Daily.C_DOOR_DAILY, ByteArray(0), docs))
        assertEquals(expected["door_level_up"], plan(Daily.C_DOOR_LEVEL_UP, ByteArray(0), docs))
        assertEquals(expected["donate_gold"], plan(Daily.C_DOOR_DONATE, byteArrayOf(1) + u32(2), docs))
        assertEquals(expected["donate_gold_limit"], plan(Daily.C_DOOR_DONATE, byteArrayOf(1) + u32(3), docs))
        assertEquals(expected["donate_items"], plan(Daily.C_DOOR_DONATE, byteArrayOf(2) + u32(2, 9204, 5, 9205, 0), docs))
        assertEquals(expected["donate_items_ok"], plan(Daily.C_DOOR_DONATE, byteArrayOf(2) + u32(1, 9204, 5), docs))
        assertEquals(expected["donate_not_today"], plan(Daily.C_DOOR_DONATE, byteArrayOf(1) + u32(1),
            mapOf("royal_door_state" to PyDocs.shallow(door).also { it["tasks"] = jarr(1, 5) })))
        assertEquals(expected["door_tasks_unborn"], compact(JArr(Daily.doorTasks("unborn", day).mapTo(ArrayList()) { JInt(it) })))
    }

    @Test
    fun `arena ladder, panel, top list and the catch list`() {
        val people = listOf(Participant(9001, "character", "Knight".toByteArray(), 12, power = BigInteger.valueOf(700), leaderTemplate = 77, gender = 1),
            Participant(9002, "character", "Zed".toByteArray(), 30, power = null, leaderTemplate = 0),
            Participant(9500, "bot", "Bot".toByteArray(), 5, power = BigInteger.ONE.shiftLeft(40), leaderTemplate = 66, extra = jobj("arena_seed" to 2)),
            Participant(9501, "bot", "Bob".toByteArray(), 12, power = BigInteger.valueOf(5), leaderTemplate = 65),
            Participant(9502, "bot", "Eve".toByteArray(), 8, power = BigInteger.valueOf(9), leaderTemplate = 64, extra = jobj("arena_seed" to 1)))
        val (ranks, changed) = Arena.ladderRanks(jobj("profile" to "arena_ladder_v1", "ranks" to listOf(9002, 1234)), people)
        val byId = people.associateBy { it.participantId }
        val rows = Arena.opponents(ranks, byId, 9001)
        val history = jarr(jobj("name_hex" to "5a6564", "attacker" to 1, "result" to 0, "rank" to 2, "trend" to -1))
        val doc = jobj("profile" to Arena.ARENA_PROFILE, "joined_at" to 0, "settled_at" to null, "reward_rank" to 0, "claimed" to 0, "history" to history)
        val rank = ranks.indexOf(9001L) + 1L
        val view = Arena.arenaView(doc, rank, now, 0)
        val profile = jobj("document" to jobj("created_at_utc" to "2023-11-15T00:00:00.000+00:00"))
        val withProfile = StateStore.Current(1, "", "", JObj(), ByteArray(0), 0, null, JArr(), emptyList(), JArr(), emptyList(), null, null, null,
            profile, null, LinkedHashMap())
        val out = jobj("ranks" to ranks, "changed" to changed, "rows" to rows.map { jarr(it.first, it.second.participantId) },
            "settle" to Arena.lastSettlement(now), "view" to view, "info" to Arena.infoPayload(view, rank, rows).toHexString(),
            "top" to Arena.topPayload(ranks, byId).toHexString(), "catch" to Castle.catchListPayload(people, JInt(9001), 12).toHexString(),
            "joined" to Arena.joinedAtOf(withProfile))
        assertEquals(expected["arena"], compact(out))
    }

    @Test
    fun `arena and catch list replies of a world-less session`() {
        val ctx = DailyRoutes.WorldContext(null, null, { _: StateStore.Current -> BigInteger.valueOf(4321) }, emptyList()) { c, cur ->
            if (cur == null) emptyList() else listOf(WorldParticipants.characterParticipant(jobj("character_id" to null, "created_at_utc" to ""), cur, c.powerOf))
        }
        for (opcode in listOf(417, 421, 753)) {
            val reply = DailyRoutes.queryReply(opcode, ByteArray(0), current(), null, inputs, now, ctx, "char_x")
            assertEquals(expected["query_$opcode"], compact(frames(reply)))
        }
    }

    @Test
    fun `the approved fixes - Door row code, ladder duplicates, catch list ids, settlement offset, protected check-in day`() {
        val people = listOf(Participant(9001, "character", "Knight".toByteArray(), 12, power = BigInteger.valueOf(700), leaderTemplate = 77, gender = 1),
            Participant(9002, "character", "Zed".toByteArray(), 30, power = null, leaderTemplate = 0),
            Participant(9500, "bot", "Bot".toByteArray(), 5, power = BigInteger.ONE.shiftLeft(40), leaderTemplate = 66, extra = jobj("arena_seed" to 2)),
            Participant(9501, "bot", "Bob".toByteArray(), 12, power = BigInteger.valueOf(5), leaderTemplate = 65),
            Participant(9502, "bot", "Eve".toByteArray(), 8, power = BigInteger.valueOf(9), leaderTemplate = 64, extra = jobj("arena_seed" to 1)))
        val out = JObj()
        try {
            Daily.planDoorClaim("level_up", Owned(current(), inputs), inputs, null, jobj("level" to 3, "exp" to 0, "born_at_utc" to "u"), now, JStr("u"))
        } catch (e: Acquisition.Rejected) {
            out["door_row"] = jarr(e.message, e.code)
        }
        val (ranks, changed) = Arena.ladderRanks(jobj("ranks" to listOf(9002, 9001, 9002)), people)
        out["ladder_twice"] = jarr(ranks, changed)
        val twin = people + Participant(9502, "bot", "Twin".toByteArray(), 99, power = BigInteger.ONE, leaderTemplate = 63)
        out["catch_twin"] = JStr(Castle.catchListPayload(twin, JInt(9001), 12).toHexString())
        val switch = now - 30000
        DeviceClock.active = DeviceClock(null, timeSource = { 0L }, offsetSource = { e -> if (e < switch) 3600 else 7200 })
        out["settle_switch"] = JInt(Arena.lastSettlement(now))
        val seen = 1_700_000_000L - (1_700_000_000L + 7200) % 86400 + 1800
        val clock = DeviceClock(null, timeSource = { seen }, offsetSource = { e -> if (e < seen + 60) 7200 else -18000 })
        clock.now()
        DeviceClock.active = clock
        val later = seen + 120
        out["zone_day"] = jarr(Shops.dayOf(later), Daily.monthKey(later), Daily.monthPayload(jobj("signed" to listOf(3)), later).toHexString())
        try {
            Daily.planMonthSign(Owned(current(), inputs), inputs, jobj("profile" to Daily.SIGN_PROFILE, "month" to Daily.monthKey(later),
                "signed" to listOf(Shops.dayOf(later).substring(8).toInt()), "chain_day" to Shops.dayOf(later), "row" to 1, "available_at" to later,
                "seed" to null), later)
        } catch (e: Acquisition.Rejected) {
            out["zone_sign"] = jarr(e.message, e.code)
        }
        assertEquals(expected["fixes"], compact(out))
    }
}
