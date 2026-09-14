package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.toHexString
import io.github.okexodus.openknights.gamedata.GameTables
import io.github.okexodus.openknights.gamedata.TableSource
import io.github.okexodus.openknights.server.DeviceClock
import io.github.okexodus.openknights.server.game.WorldParticipants.Participant
import io.github.okexodus.openknights.server.store.JdbcSqlDriver
import io.github.okexodus.openknights.server.store.StateStore
import io.github.okexodus.openknights.server.store.WorldDirectory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigInteger
import java.nio.file.Path

/**
 * CI checks of the guild slice on made-up tables, made-up saves and a made-up world: a sequence of guild requests
 * (create, apply, approve, donate, techs, positions, emblem, rename, salary, guild mail, war sign-up and result, task
 * board, transfer, kick, quit) through [SocialRoutes.dispatch]; each expected value is the reference's output for the
 * same made-up input. The full proof against the recorded saves and the APK tables is [G7GuildVectorsTest] (local).
 */
class G7GuildMadeUpTest {
    @TempDir
    lateinit var temp: Path

    private class TextTables(private val files: Map<String, String>) : TableSource {
        override fun names() = files.keys.sorted()
        override fun raw(name: String): ByteArray {
            val file = if (name.endsWith(".csv")) name else "$name.csv"
            return (files[file] ?: throw NoSuchElementException("Missing table: $file")).toByteArray()
        }
    }

    private val tables = mapOf(
        "property.csv" to "101,102\n2000,3\n2001,2\n2004,7\n200001,300\n200002,2\n200003,10\n200004,2\n210006,36000\n210007,72000\n",
        "text.csv" to "101,102\n4741,Hi all\n17102,Sign up\n17106,No match\n",
        "juntuan_dengji.csv" to "101,102,104,105,106,107,108,109,111,112,113\n1,1,200,1,300,2,0,0,0,40,3\n2,2,200,1,300,2,0,0,0,90,4\n" +
            "3,3,200,2,300,3,400,4,0,0,5\n",
        "juntuan_junhui.csv" to "101,102,103,106,111,112\n101,1,50,1,102,40\n102,2,80,2,103,60\n103,3,0,3,0,0\n",
        "juntuan_quanxian.csv" to "101,102,103,104,105,106,107,109,111,112,113,114,115,118,119,120\n" +
            "100,1,1,1,0,1,1,1,200,0,1,1,70,9501,2,1\n200,2,1,0,1,0,1,0,300,30,1,1,50,9501,1,0\n" +
            "300,3,1,0,1,0,0,0,400,20,0,0,30,0,0,0\n400,4,0,0,1,0,0,0,600,10,0,0,20,0,0,0\n" +
            "500,5,0,0,1,0,0,0,0,0,0,0,10,0,0,0\n600,6,0,0,1,0,0,0,0,0,0,0,0,0,0,0\n",
        "juntuan_technolegy.csv" to "101,103,105,111,112\n102,0,20,3,3\n103,0,30,3,3\n104,0,40,3,3\n105,1,50,0,0\n",
        "juntuan_boss.csv" to "101,102,104,105,106,107,108,109,110\n1,1,7,11,12,13,100,50,3\n2,2,9,21,22,23,150,50,3\n",
        "quest_juntuan.csv" to "101,102,104,106,107,108,109,110,111,113,114\n" +
            "11,1,1,52,101,2,30,40,500,9502,1\n12,1,1,52,102,1,30,40,500,9502,1\n21,2,2,8,0,5,20,30,300,0,0\n" +
            "31,3,3,10,0,3,50,60,700,9502,2\n41,4,4,35,0,1,10,10,100,0,0\n42,4,4,37,0,1,15,15,150,0,0\n",
        "questjuntuan_star.csv" to "101,102,103\n1,10000,50\n2,15000,30\n3,20000,20\n",
        "item.csv" to "101,102,104,105,106,107,110,203,204,205,206,207,215,305\n9501,1,2,0,0,0,0,0,0,0,0,,0,1\n" +
            "9502,1,2,0,0,0,0,0,0,0,0,,0,1\n7101,1,2,0,0,0,0,0,0,0,0,,101,1\n7102,1,2,0,0,0,0,0,0,0,0,,102,1\n",
        "roleexp.csv" to "101,102\n1,100\n2,200\n3,400\n4,800\n5,1600\n6,3200\n7,6400\n8,12800\n9,25600\n10,51200\n11,90000\n12,0\n",
    )

    private val inputs = DailyInputs(GameTables(TextTables(tables)))
    private val day0 = 1_900_000_000L - 1_900_000_000L % 86_400
    private val a = 4242L
    private val b = 4343L
    private val c = 4444L
    private val d = 4545L
    private val names = mapOf(a to "Ann", b to "Ben", c to "Cid", d to "Dee")

    @AfterEach
    fun reset() {
        DeviceClock.active = null
    }

    private fun field(ident: Long, bits: Long, tag: Long = 5) = jobj("id" to ident, "value" to jobj("tag" to tag, "bits" to bits))

    private fun state(role: Long, level: Long, vip: Long, diamonds: Long, gold: Long, contribution: Long): JObj = jobj(
        "role_properties" to jarr(field(0, role), field(2, 0), field(3, level), field(4, 0), field(6, gold, 8), field(8, diamonds),
            field(27, vip), field(30, contribution)),
        "items" to jarr(jobj("wire_values" to jarr(11, 7101, 5), "timed_flag" to 0), jobj("wire_values" to jarr(12, 7101, 1), "timed_flag" to 1),
            jobj("wire_values" to jarr(13, 7102, 2), "timed_flag" to 0)),
        "item_capacity_values" to jarr(9, 50, 50), "heroes" to JArr(), "offline_hero_uids" to JArr(), "formation" to JArr(),
        "subsystems" to jobj("achievements" to jobj("entries" to jarr(jobj("wire_values" to jarr(3, 1, 0)), jobj("wire_values" to jarr(18, 2, 40)))),
            "game_activities" to jobj("first_list" to jobj("count" to 0, "entries" to JArr()))))

    private class Roles(val level: Long = 12, val vip: Long = 2, val diamonds: Long = 900, val gold: Long = 50_000, val contribution: Long = 0)

    private fun current(role: Long, roles: Roles, docs: JObj?): StateStore.Current {
        val documents = LinkedHashMap<String, io.github.okexodus.openknights.exact.JValue?>()
        docs?.forEach { (k, v) -> documents[k] = v.deepCopy() }
        return StateStore.Current(3, "", "", state(role, roles.level, roles.vip, roles.diamonds, roles.gold, roles.contribution), ByteArray(0), 0, null,
            JArr(), emptyList(), JArr(), emptyList(), null, null, null, null, null, documents)
    }

    private val people: List<Participant> = listOf(a, b, c, d).mapIndexed { k, r ->
        Participant(r, "character", names.getValue(r).toByteArray(), 12L + k, k.toLong(), 0, BigInteger.valueOf(1000L * k), 0, emptyList(),
            "char_$r", "2030-03-01T00:00:00+00:00")
    }

    private fun hour(h: Long, m: Long = 0) = day0 + h * 3600 + m * 60

    private fun board(tasks: List<List<Long>>, star: Long = 1, refreshes: Long = 0): JObj =
        jobj("guild_task_state" to jobj("profile" to "guild_task_state_v1", "day" to "2030-03-17",
            "tasks" to JArr(tasks.mapTo(ArrayList()) { t -> jarr(*t.toTypedArray()) }), "star" to star, "refreshes" to refreshes))

    private fun u(vararg v: Long): ByteArray = io.github.okexodus.openknights.protocol.WireWriter().also { w -> v.forEach { w.u32(it) } }.bytes()
    private fun s(text: String): ByteArray = text.toByteArray() + byteArrayOf(0)

    private class Step(val label: String, val role: Long, val opcode: Int, val payload: ByteArray, val roles: Roles, val docs: JObj?, val now: Long)

    private fun steps(): List<Step> {
        val rich = Roles()
        val open = listOf(listOf(11L, 0, 0, 0), listOf(21L, 0, 0, 0))
        return listOf(
            Step("create_low_level", a, 2153, s("Alpha") + s(""), Roles(level = 10), null, hour(9)),
            Step("create", a, 2153, s("Alpha") + s(""), rich, null, hour(9)),
            Step("create_taken", b, 2153, s("ALPHA") + s("x"), rich, null, hour(9)),
            Step("list", b, 2149, ByteArray(0), Roles(), null, hour(9)),
            Step("apply", b, 2155, u(1), Roles(), null, hour(9)),
            Step("apply_again", b, 2155, u(1), Roles(), null, hour(9)),
            Step("apply_c", c, 2155, u(1), Roles(), null, hour(9)),
            Step("applicants", a, 2151, u(1), Roles(), null, hour(9)),
            Step("approve", a, 2159, u(b) + byteArrayOf(1), Roles(), null, hour(9)),
            Step("approve_c", a, 2159, u(c) + byteArrayOf(1), Roles(), null, hour(9)),
            Step("members", b, 2147, u(1, 1), Roles(), null, hour(9)),
            Step("other", d, 2203, u(1), Roles(), null, hour(9)),
            Step("donate", b, 2157, u(30_000, 40), Roles(diamonds = 100), null, hour(9)),
            Step("donate_cap", b, 2157, u(250_000, 0), Roles(), null, hour(9)),
            Step("donate_a", a, 2157, u(0, 60), Roles(), null, hour(9)),
            Step("level_two", a, 2145, ByteArray(0), Roles(), null, hour(9)),
            Step("tech_up", a, 2177, u(102), Roles(), null, hour(9)),
            Step("tech_list", c, 2175, ByteArray(0), Roles(), null, hour(9)),
            Step("position_elite", b, 2165, u(400), Roles(), null, hour(9)),
            Step("position_captain", b, 2165, u(300), Roles(), null, hour(9)),
            Step("position_leader", c, 2165, u(100), Roles(), null, hour(9)),
            Step("positions", a, 2171, ByteArray(0), Roles(), null, hour(9)),
            Step("emblem", a, 2163, ByteArray(0), Roles(), null, hour(9)),
            Step("rename", a, 2205, s("Beta"), Roles(), null, hour(9)),
            Step("rename_vice", b, 2205, s("Gamma"), Roles(), null, hour(9)),
            Step("notice", b, 2181, s("Be kind"), Roles(), null, hour(9)),
            Step("wage", a, 2201, ByteArray(0), Roles(), null, hour(9)),
            Step("wage_again", a, 2201, ByteArray(0), Roles(), null, hour(9)),
            Step("mail", a, 2169, s("Meet") + s("At nine"), Roles(), null, hour(9)),
            Step("mail_member", c, 2169, s("Hi") + s("There"), Roles(), null, hour(9)),
            Step("war_sign", a, 2185, ByteArray(0), Roles(), null, hour(9)),
            Step("war_sign_again", a, 2185, ByteArray(0), Roles(), null, hour(9)),
            Step("boss", c, 2193, ByteArray(0), Roles(), null, hour(16)),
            Step("activity_result", a, 2183, ByteArray(0), Roles(), null, hour(19, 5)),
            Step("tasks", c, 2441, ByteArray(0), Roles(), null, hour(9)),
            Step("task_query", c, 2437, byteArrayOf(1), Roles(), null, hour(9)),
            Step("task_refresh", c, 2437, byteArrayOf(2), Roles(), board(open), hour(9)),
            Step("task_refresh_paid", c, 2437, byteArrayOf(2), Roles(), board(open, refreshes = 2), hour(9)),
            Step("task_accept", c, 2439, u(21), Roles(), board(open), hour(9)),
            Step("task_donate", c, 2435, byteArrayOf(1) + u(11) + byteArrayOf(1) + u(7101, 6), Roles(), board(open), hour(9)),
            Step("task_donate_wrong", c, 2435, byteArrayOf(1) + u(11) + byteArrayOf(1) + u(7102, 2), Roles(), board(open), hour(9)),
            Step("task_claim", c, 2443, u(31), Roles(level = 11), board(listOf(listOf(31L, 1, 3, 2), listOf(21L, 0, 0, 0)), star = 3), hour(9)),
            Step("transfer", a, 2167, u(b), Roles(), null, hour(9)),
            Step("kick", b, 2161, u(c), Roles(), null, hour(9)),
            Step("quit_leader", b, 2173, ByteArray(0), Roles(), null, hour(9)),
            Step("quit", a, 2173, ByteArray(0), Roles(), null, hour(9)),
            Step("my_guild_after_quit", a, 2145, ByteArray(0), Roles(), null, hour(9)),
            Step("create_notice_long", a, 2153, s("Delta") + s("n".repeat(121)), rich, null, hour(9)),
            Step("donate_remainder", b, 2157, u(25_000, 0), Roles(), null, hour(9)),
            Step("donate_below_unit", b, 2157, u(9_999, 0), Roles(), null, hour(9)),
        )
    }

    private fun framesJson(frames: List<Frame>): JArr = JArr(frames.mapTo(ArrayList()) { jarr(it.first, it.second.toHexString()) })

    @Test
    fun `a guild's life on made-up tables`() {
        DeviceClock.active = DeviceClock(null, timeSource = { 0L }, offsetSource = { 0 })
        val world = WorldDirectory.initialize(temp.resolve("world.sqlite3"), JdbcSqlDriver(), "madeup")
        for ((name, born) in WorldDirectory.WORLD_DOCUMENTS) world.putDocument(name, born, 1, "test", "test")
        val steps = steps()
        assertEquals(expected.map { it.first }, steps.map { it.label })
        for ((k, step) in steps.withIndex()) {
            val sequence = world.connect(readOnly = true).use { it.queryOne("SELECT MAX(sequence) AS m FROM world_history")!!.long("m") }
            val pushes = JArr()
            val ctx = SocialRoutes.SocialContext(world, null, inputs, pushFn = { role, builder -> pushes.add(jarr(role, framesJson(builder(0)))) },
                people = LinkedHashMap<Long, Participant>().also { m -> people.forEach { m[it.participantId] = it } })
            val commits = JArr()
            val commit = SocialRoutes.Commit { action, planner ->
                val cur = current(step.role, step.roles, step.docs)
                val plan = planner(Owned(cur, inputs), cur)
                commits.add(jarr(action, plan.data.deepCopy()))
                plan
            }
            val result = try {
                jobj("frames" to framesJson(SocialRoutes.dispatch(step.opcode, step.payload, current(step.role, step.roles, step.docs), ctx, commit,
                    step.now, step.now, "char_${step.role}")))
            } catch (e: IllegalArgumentException) {
                jobj("refused" to jarr(e.message, (e as? Acquisition.Rejected)?.code ?: 102))
            }
            result["pushes"] = pushes
            result["commits"] = commits
            result["log"] = JArr(world.connect(readOnly = true).use { db ->
                db.query("SELECT action, detail_json FROM world_history WHERE sequence > ? ORDER BY sequence", sequence).mapTo(ArrayList()) { row ->
                    val detail = Json.loads(row.string("detail_json")).asObj
                    val name = detail.str("name")
                    detail.remove("name")
                    detail.remove("revision")
                    jarr(name, row.string("action"), Json.canonical(detail))
                }
            })
            assertEquals(expected[k].second, Json.dumps(result, itemSeparator = ",", keySeparator = ":"), step.label)
        }
        for ((name, text) in finalDocuments) assertEquals(text, Json.canonical(world.document(name)!!.second), name)
    }

    @Test
    fun `request codecs and names`() {
        assertEquals(listOf("61", ""), Guild.decodeCstrings(byteArrayOf(0x61, 0, 0), 2, 2153).map { it.toHexString() })
        assertThrows(Acquisition.Rejected::class.java) { Guild.decodeCstrings(byteArrayOf(0x61, 0, 0x62), 2, 2153) }
        assertEquals("C2203 is u32", assertThrows(Acquisition.Rejected::class.java) { Guild.decodeU32(ByteArray(3), 2203) }.message)
        assertEquals("{\"task\":7,\"items\":[[9,2]]}", Json.dumps(Guild.decodeTaskDonate(byteArrayOf(1) + u(7) + byteArrayOf(1) + u(9, 2)),
            itemSeparator = ",", keySeparator = ":"))
        assertEquals("C2435 length", assertThrows(Acquisition.Rejected::class.java) { Guild.decodeTaskDonate(byteArrayOf(1) + u(7) + byteArrayOf(2)) }.message)
        val doc = jobj("guilds" to jobj("1" to jobj("name_hex" to "4b6e69676874")))
        assertEquals(52042, assertThrows(Acquisition.Rejected::class.java) { Guild.validateName(" KNIGHT ".toByteArray(), doc, inputs) }.code)
        assertEquals("4b6e69676874", Guild.validateName(" Knight ".toByteArray(), doc, inputs, exclude = "1").toHexString())
        assertEquals(102, assertThrows(Acquisition.Rejected::class.java) { Guild.validateName("  ".toByteArray(), doc, inputs) }.code)
        assertEquals(listOf(0, 0, 1, 1, 2, 3, 3), listOf(0, 5, 6, 18, 19, 20, 23).map { Guild.warState(it) })
    }

    private val expected: List<Pair<String, String>> = listOf(
        "create_low_level" to "{\"refused\":[\"Player level must be above 50\",102],\"pushes\":[],\"commits\":[],\"log\":[]}",
        "create" to "{\"frames\":[[578,\"120254010000\"],[128,\"01080558020000\"],[2310,\"0001000000\"],[2306,\"0100000065000000416c706861000100416e6e00000000000000000000000000486920616c6c000000000060d8030064000000010004000000000000\"],[2318,\"0466000000010067000000010068000000010069000000010000000000\"],[2330,\"0103660000000100010067000000010001006800000001000100\"]],\"pushes\":[],\"commits\":[[\"guild_create\",{\"price\":300,\"evidence_class\":\"native_use_policy\"}]],\"log\":[[\"guilds\",\"guild_create\",\"{\\\"guild\\\":1,\\\"role\\\":4242}\"]]}",
        "create_taken" to "{\"refused\":[\"That guild name is taken\",52042],\"pushes\":[],\"commits\":[],\"log\":[]}",
        "list" to "{\"frames\":[[2304,\"010001000101000000416c70686100650000000100416e6e00486920616c6c000100040000\"]],\"pushes\":[],\"commits\":[],\"log\":[]}",
        "apply" to "{\"frames\":[[2312,\"00\"]],\"pushes\":[],\"commits\":[],\"log\":[[\"guilds\",\"guild_apply\",\"{\\\"guild\\\":1,\\\"role\\\":4343}\"]]}",
        "apply_again" to "{\"refused\":[\"Applied already\",52005],\"pushes\":[],\"commits\":[],\"log\":[]}",
        "apply_c" to "{\"frames\":[[2312,\"00\"]],\"pushes\":[],\"commits\":[],\"log\":[[\"guilds\",\"guild_apply\",\"{\\\"guild\\\":1,\\\"role\\\":4444}\"]]}",
        "applicants" to "{\"frames\":[[2320,\"0100010002f710000042656e000d00000000a129710000000000000000e8030000000000005c110000436964000e00000000a129710000000000000000d007000000000000\"]],\"pushes\":[],\"commits\":[],\"log\":[]}",
        "approve" to "{\"frames\":[[2322,\"f710000000\"]],\"pushes\":[[4343,[[2306,\"0100000065000000416c706861000100416e6e00000000000000000000000000486920616c6c0000000000682a040058020000020004000000000000\"]]]],\"commits\":[],\"log\":[[\"guilds\",\"guild_decide\",\"{\\\"accept\\\":true,\\\"applicant\\\":4343,\\\"role\\\":4242}\"]]}",
        "approve_c" to "{\"frames\":[[2322,\"5c11000000\"]],\"pushes\":[[4444,[[2306,\"0100000065000000416c706861000100416e6e00000000000000000000000000486920616c6c0000000000707c040058020000030004000000000000\"]]]],\"commits\":[],\"log\":[[\"guilds\",\"guild_decide\",\"{\\\"accept\\\":true,\\\"applicant\\\":4444,\\\"role\\\":4242}\"]]}",
        "members" to "{\"frames\":[[2308,\"01000000010001000392100000416e6e000c006400000000000000f710000042656e000d0058020000000000005c110000436964000e005802000000000000\"]],\"pushes\":[],\"commits\":[],\"log\":[]}",
        "other" to "{\"frames\":[[2342,\"0100000065000000416c706861000100416e6e0000000000486920616c6c0003000400\"]],\"pushes\":[],\"commits\":[],\"log\":[]}",
        "donate" to "{\"frames\":[[578,\"120250000000\"],[128,\"030608204e00000000000008053c0000001e052b000000\"],[2314,\"000e000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000002b0000000000000000000000000000000000000000000000000000\"],[2306,\"0100000065000000416c706861000200416e6e002b0000002b00000000000000486920616c6c0030750000682a040058020000030005000028000000\"]],\"pushes\":[],\"commits\":[[\"guild_donate\",{\"gold\":30000,\"diamonds\":40,\"points\":43,\"evidence_class\":\"native_use_policy\"}]],\"log\":[[\"guilds\",\"guild_donate\",\"{\\\"diamonds\\\":40,\\\"gold\\\":30000,\\\"points\\\":43,\\\"role\\\":4343}\"]]}",
        "donate_cap" to "{\"refused\":[\"Donation exceeds max\",52025],\"pushes\":[],\"commits\":[],\"log\":[]}",
        "donate_a" to "{\"frames\":[[578,\"120264000000\"],[128,\"020805480300001e053c000000\"],[2314,\"000e000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000003c0000000000000000000000000000000000000000000000000000\"],[2306,\"0100000065000000416c706861000300416e6e00670000006700000000000000486920616c6c000000000060d8030064000000030006000064000000\"]],\"pushes\":[],\"commits\":[[\"guild_donate\",{\"gold\":0,\"diamonds\":60,\"points\":60,\"evidence_class\":\"native_use_policy\"}]],\"log\":[[\"guilds\",\"guild_donate\",\"{\\\"diamonds\\\":60,\\\"gold\\\":0,\\\"points\\\":60,\\\"role\\\":4242}\"]]}",
        "level_two" to "{\"frames\":[[2306,\"0100000065000000416c706861000300416e6e00670000006700000000000000486920616c6c000000000060d8030064000000030006000064000000\"]],\"pushes\":[],\"commits\":[],\"log\":[]}",
        "tech_up" to "{\"frames\":[[2306,\"0100000065000000416c706861000300416e6e00530000006700000000000000486920616c6c000000000060d8030064000000030006000064000000\"],[2318,\"0466000000020067000000010068000000010069000000010053000000\"],[2330,\"0103660000000100020067000000010001006800000001000100\"]],\"pushes\":[[4343,[[2318,\"0466000000020067000000010068000000010069000000010053000000\"]]],[4444,[[2318,\"0466000000020067000000010068000000010069000000010053000000\"]]]],\"commits\":[],\"log\":[[\"guilds\",\"guild_tech\",\"{\\\"cost\\\":20,\\\"role\\\":4242,\\\"tech\\\":102}\"]]}",
        "tech_list" to "{\"frames\":[[2318,\"0466000000020067000000010068000000010069000000010053000000\"]],\"pushes\":[],\"commits\":[],\"log\":[]}",
        "position_elite" to "{\"frames\":[[2306,\"0100000065000000416c706861000300416e6e00530000006700000000000000486920616c6c0030750000682a040090010000030006000064000000\"],[2326,\"056400000001416e6e003c000000c8000000002c01000000900100000142656e002b000000f401000000\"]],\"pushes\":[],\"commits\":[],\"log\":[[\"guilds\",\"guild_position\",\"{\\\"position\\\":400,\\\"role\\\":4343}\"]]}",
        "position_captain" to "{\"frames\":[[2306,\"0100000065000000416c706861000300416e6e00530000006700000000000000486920616c6c0030750000682a04002c010000030006000064000000\"],[2326,\"056400000001416e6e003c000000c8000000002c0100000142656e002b0000009001000000f401000000\"]],\"pushes\":[],\"commits\":[],\"log\":[[\"guilds\",\"guild_position\",\"{\\\"position\\\":300,\\\"role\\\":4343}\"]]}",
        "position_leader" to "{\"refused\":[\"Cannot apply this position\",52012],\"pushes\":[],\"commits\":[],\"log\":[]}",
        "positions" to "{\"frames\":[[2326,\"056400000001416e6e003c000000c8000000002c0100000142656e002b0000009001000000f401000000\"]],\"pushes\":[],\"commits\":[],\"log\":[]}",
        "emblem" to "{\"frames\":[[578,\"12025a000000\"],[128,\"020805520300001e0528000000\"],[2306,\"0100000066000000416c706861000300416e6e00530000006700000000000000486920616c6c000000000060d8030064000000030007000064000000\"]],\"pushes\":[[4343,[[2306,\"0100000066000000416c706861000300416e6e00530000006700000000000000486920616c6c0030750000682a04002c010000030007000064000000\"]]],[4444,[[2306,\"0100000066000000416c706861000300416e6e00530000006700000000000000486920616c6c0000000000707c040058020000030007000064000000\"]]]],\"commits\":[[\"guild_emblem\",{\"price\":50,\"metals\":40,\"evidence_class\":\"native_use_policy\"}]],\"log\":[[\"guilds\",\"guild_emblem\",\"{\\\"price\\\":50,\\\"role\\\":4242}\"]]}",
        "rename" to "{\"frames\":[[578,\"12021c020000\"],[128,\"01080590010000\"],[2306,\"010000006600000042657461000300416e6e00530000006700000000000000486920616c6c000000000060d8030064000000030007000064000000\"]],\"pushes\":[[4343,[[2306,\"010000006600000042657461000300416e6e00530000006700000000000000486920616c6c0030750000682a04002c010000030007000064000000\"]]],[4444,[[2306,\"010000006600000042657461000300416e6e00530000006700000000000000486920616c6c0000000000707c040058020000030007000064000000\"]]]],\"commits\":[[\"guild_rename\",{\"price\":500,\"metals\":0,\"evidence_class\":\"native_use_policy\"}]],\"log\":[[\"guilds\",\"guild_rename\",\"{\\\"price\\\":500,\\\"role\\\":4242}\"]]}",
        "rename_vice" to "{\"refused\":[\"No access\",52009],\"pushes\":[],\"commits\":[],\"log\":[]}",
        "notice" to "{\"refused\":[\"No access\",52009],\"pushes\":[],\"commits\":[],\"log\":[]}",
        "wage" to "{\"frames\":[[128,\"01060822c4000000000000\"],[64,\"010e0000001d2500000200000000\"],[2340,\"0e000000000000000000000000000000d200000000000000000000000000000000000000000000000000000000000000011d2500000200000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000\"],[2306,\"010000006600000042657461000300416e6e00530000006700000000000000486920616c6c000000000060d8030064000000030007000164000000\"]],\"pushes\":[],\"commits\":[[\"guild_wage\",{\"evidence_class\":\"native_use_candidate\"}]],\"log\":[[\"guilds\",\"guild_wage\",\"{\\\"role\\\":4242}\"]]}",
        "wage_again" to "{\"refused\":[\"Claimed today\",52035],\"pushes\":[],\"commits\":[],\"log\":[]}",
        "mail" to "{\"frames\":[[2316,\"00\"]],\"pushes\":[[4343,[[258,\"01000000069210000090373f7100416e6e004d65657400\"]]],[4444,[[258,\"02000000069210000090373f7100416e6e004d65657400\"]]]],\"commits\":[],\"log\":[[\"mail\",\"mail_guild\",\"{\\\"guild\\\":1,\\\"role\\\":4242,\\\"sent\\\":2}\"]]}",
        "mail_member" to "{\"frames\":[[2316,\"01\"]],\"pushes\":[],\"commits\":[],\"log\":[]}",
        "war_sign" to "{\"frames\":[[2332,\"010101\"],[2332,\"0200\"],[2332,\"0300\"],[2332,\"0401\"],[258,\"03000000060000000090373f710053797374656d005369676e20757000\"]],\"pushes\":[],\"commits\":[],\"log\":[[\"guilds\",\"guild_war_sign\",\"{\\\"role\\\":4242}\"],[\"mail\",\"mail_guild_war\",\"{\\\"mail\\\":3,\\\"role\\\":4242}\"]]}",
        "war_sign_again" to "{\"refused\":[\"Signed up already\",52021],\"pushes\":[],\"commits\":[],\"log\":[]}",
        "boss" to "{\"frames\":[[2338,\"010000000000007c1b00007c1b0000\"]],\"pushes\":[],\"commits\":[],\"log\":[]}",
        "activity_result" to "{\"frames\":[[2332,\"010200\"],[2332,\"0201\"],[2332,\"0301\"],[2332,\"0401\"],[258,\"04000000060000000030c43f710053797374656d004e6f206d6174636800\"]],\"pushes\":[[4343,[[258,\"05000000060000000030c43f710053797374656d004e6f206d6174636800\"]]],[4444,[[258,\"06000000060000000030c43f710053797374656d004e6f206d6174636800\"]]]],\"commits\":[],\"log\":[[\"guilds\",\"guild_war_result\",\"{\\\"guild\\\":1,\\\"result\\\":\\\"no_match\\\"}\"],[\"mail\",\"mail_guild_war_result\",\"{\\\"mail\\\":\\\"guild_war_no_match\\\",\\\"sent\\\":3}\"]]}",
        "tasks" to "{\"frames\":[[2344,\"040b000000000000000000000000150000000000000000000000001f00000000000000000000000029000000000000000000000000010000f0d20000\"]],\"pushes\":[],\"commits\":[],\"log\":[]}",
        "task_query" to "{\"frames\":[[2344,\"040b000000000000000000000000150000000000000000000000001f00000000000000000000000029000000000000000000000000010000f0d20000\"]],\"pushes\":[],\"commits\":[],\"log\":[]}",
        "task_refresh" to "{\"frames\":[[2344,\"020b00000000000000000000000015000000000000000000000000010100f0d20000\"]],\"pushes\":[],\"commits\":[[\"guild_task\",{\"guild_task_state_after\":{\"profile\":\"guild_task_state_v1\",\"day\":\"2030-03-17\",\"tasks\":[[11,0,0,0],[21,0,0,0]],\"star\":1,\"refreshes\":1},\"star\":1,\"evidence_class\":\"native_use_policy\"}]],\"log\":[]}",
        "task_refresh_paid" to "{\"frames\":[[578,\"12022f000000\"],[128,\"0108057d030000\"],[2344,\"020b00000000000000000000000015000000000000000000000000010300f0d20000\"]],\"pushes\":[],\"commits\":[[\"guild_task\",{\"guild_task_state_after\":{\"profile\":\"guild_task_state_v1\",\"day\":\"2030-03-17\",\"tasks\":[[11,0,0,0],[21,0,0,0]],\"star\":1,\"refreshes\":3},\"star\":1,\"evidence_class\":\"native_use_policy\"}]],\"log\":[]}",
        "task_accept" to "{\"frames\":[[2344,\"020b00000000000000000000000015000000000000000000000001010000f0d20000\"]],\"pushes\":[],\"commits\":[[\"guild_task\",{\"guild_task_state_after\":{\"profile\":\"guild_task_state_v1\",\"day\":\"2030-03-17\",\"tasks\":[[11,0,0,0],[21,0,0,1]],\"star\":1,\"refreshes\":0},\"evidence_class\":\"native_use_candidate\"}]],\"log\":[]}",
        "task_donate" to "{\"frames\":[[66,\"010b000000\"],[66,\"010c000000\"]],\"pushes\":[],\"commits\":[[\"guild_task\",{\"guild_task_state_after\":{\"profile\":\"guild_task_state_v1\",\"day\":\"2030-03-17\",\"tasks\":[[11,1,1,2],[21,0,0,0]],\"star\":1,\"refreshes\":0},\"evidence_class\":\"capture_observed\"}]],\"log\":[]}",
        "task_donate_wrong" to "{\"refused\":[\"Wrong donation item\",102],\"pushes\":[],\"commits\":[],\"log\":[]}",
        "task_claim" to "{\"frames\":[[128,\"0104054c040000\"],[128,\"01060878c8000000000000\"],[64,\"010e0000001e2500000600000000\"],[2346,\"0e0000004c04000000000000000000002805000000000000000000000000000000000000000000000000000000000000011e2500000600000000000000000000000000000000000000000000000000780500000000000000000000000000000000000000000000000000\"],[128,\"011e0578050000\"],[2344,\"021f00000001000000000000000015000000000000000000000000030000f0d20000\"]],\"pushes\":[],\"commits\":[[\"guild_task_claim\",{\"guild_task_state_after\":{\"profile\":\"guild_task_state_v1\",\"day\":\"2030-03-17\",\"tasks\":[[31,1,0,0],[21,0,0,0]],\"star\":3,\"refreshes\":0},\"exp\":1100,\"gold\":1320,\"contribution\":1400,\"item\":[9502,6],\"evidence_class\":\"capture_observed_calculation\"}]],\"log\":[[\"guilds\",\"guild_task_contribution\",\"{\\\"points\\\":1400,\\\"role\\\":4444}\"]]}",
        "transfer" to "{\"frames\":[[2306,\"01000000660000004265746100030042656e00cb050000df05000000000000486920616c6c000000000060d8030058020000030007000164000000\"]],\"pushes\":[[4343,[[2306,\"01000000660000004265746100030042656e00cb050000df05000000000000486920616c6c0030750000682a040064000000030007000064000000\"]]]],\"commits\":[],\"log\":[[\"guilds\",\"guild_transfer\",\"{\\\"role\\\":4242,\\\"target\\\":4343}\"]]}",
        "kick" to "{\"frames\":[[2324,\"00\"]],\"pushes\":[[4444,[[2306,\"0000000000000000\"],[2330,\"00\"]]]],\"commits\":[],\"log\":[[\"guilds\",\"guild_kick\",\"{\\\"role\\\":4343,\\\"target\\\":4444}\"]]}",
        "quit_leader" to "{\"refused\":[\"Leader cannot quit\",52015],\"pushes\":[],\"commits\":[],\"log\":[]}",
        "quit" to "{\"frames\":[[2328,\"00\"],[2306,\"0000000000000000\"],[2330,\"00\"]],\"pushes\":[],\"commits\":[],\"log\":[[\"guilds\",\"guild_quit\",\"{\\\"role\\\":4242}\"]]}",
        "my_guild_after_quit" to "{\"frames\":[[2306,\"0000000000000000\"]],\"pushes\":[],\"commits\":[],\"log\":[]}",
        "create_notice_long" to "{\"refused\":[\"Inappropriate words\",52042],\"pushes\":[],\"commits\":[],\"log\":[]}",
        "donate_remainder" to "{\"frames\":[[128,\"02060830750000000000001e0502000000\"],[2314,\"000e00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000020000000000000000000000000000000000000000000000000000\"],[2306,\"01000000660000004265746100030042656e00cd050000e105000000000000486920616c6c0050c30000682a040064000000010007000064000000\"]],\"pushes\":[],\"commits\":[[\"guild_donate\",{\"gold\":20000,\"diamonds\":0,\"points\":2,\"evidence_class\":\"native_use_policy\"}]],\"log\":[[\"guilds\",\"guild_donate\",\"{\\\"diamonds\\\":0,\\\"gold\\\":20000,\\\"points\\\":2,\\\"role\\\":4343}\"]]}",
        "donate_below_unit" to "{\"refused\":[\"Nothing to donate\",102],\"pushes\":[],\"commits\":[],\"log\":[]}",
    )

    private val finalDocuments: Map<String, String> = mapOf(
        "guilds" to "{\"guilds\":{\"1\":{\"applications\":[],\"badge\":102,\"boss\":{\"day\":null,\"resets\":0},\"created_at\":1899968400,\"diamonds\":100,\"id\":1,\"members\":[{\"contribution\":45,\"gold\":50000,\"gold_day\":\"2030-03-17\",\"joined_at\":1899968400,\"position\":100,\"role\":4343,\"wage_day\":null}],\"name_hex\":\"42657461\",\"notice_hex\":\"486920616c6c\",\"popularity\":1505,\"resources\":1485,\"techs\":{\"102\":2,\"103\":1,\"104\":1,\"105\":1},\"unknown\":{\"f70\":0},\"war\":{\"notified_day\":\"2030-03-17\",\"signed_day\":\"2030-03-17\"}}},\"next_id\":2,\"profile\":\"guilds_world_v1\",\"rejoin\":{\"4242\":1899968400,\"4444\":1899968400}}",
        "mail" to "{\"blacklist\":{},\"boxes\":{\"4242\":[{\"at\":1899968400,\"body_hex\":\"5369676e207570\",\"id\":3,\"reward\":null,\"sender\":0,\"sender_name_hex\":\"53797374656d\",\"state\":0,\"title_hex\":\"5369676e207570\",\"type\":6},{\"at\":1900004400,\"body_hex\":\"4e6f206d61746368\",\"id\":4,\"reward\":null,\"sender\":0,\"sender_name_hex\":\"53797374656d\",\"state\":0,\"title_hex\":\"4e6f206d61746368\",\"type\":6}],\"4343\":[{\"at\":1899968400,\"body_hex\":\"4174206e696e65\",\"id\":1,\"reward\":null,\"sender\":4242,\"sender_name_hex\":\"416e6e\",\"state\":0,\"title_hex\":\"4d656574\",\"type\":6},{\"at\":1900004400,\"body_hex\":\"4e6f206d61746368\",\"id\":5,\"reward\":null,\"sender\":0,\"sender_name_hex\":\"53797374656d\",\"state\":0,\"title_hex\":\"4e6f206d61746368\",\"type\":6}],\"4444\":[{\"at\":1899968400,\"body_hex\":\"4174206e696e65\",\"id\":2,\"reward\":null,\"sender\":4242,\"sender_name_hex\":\"416e6e\",\"state\":0,\"title_hex\":\"4d656574\",\"type\":6},{\"at\":1900004400,\"body_hex\":\"4e6f206d61746368\",\"id\":6,\"reward\":null,\"sender\":0,\"sender_name_hex\":\"53797374656d\",\"state\":0,\"title_hex\":\"4e6f206d61746368\",\"type\":6}]},\"next_id\":7,\"profile\":\"mail_world_v1\"}",
    )
}
