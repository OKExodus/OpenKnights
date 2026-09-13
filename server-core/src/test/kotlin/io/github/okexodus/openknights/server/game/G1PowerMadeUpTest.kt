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
import io.github.okexodus.openknights.protocol.WireWriter
import io.github.okexodus.openknights.server.store.StateStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigInteger

/**
 * The group-1 Power / leader / repair port on made-up values (no game data): each expected value is the reference's
 * output for the same inputs. The full proof against real saves and the APK tables is [G1PowerVectorsTest] (local).
 */
class G1PowerMadeUpTest {
    /** Tables from made-up CSV text. */
    private class TextTables(private val files: Map<String, String>) : TableSource {
        override fun names() = files.keys.sorted()
        override fun raw(name: String): ByteArray {
            val file = if (name.endsWith(".csv")) name else "$name.csv"
            return (files[file] ?: throw NoSuchElementException("Missing table: $file")).toByteArray()
        }
    }

    private fun field(id: Int, tag: Int, bits: Long) = jobj("id" to id, "value" to jobj("tag" to tag, "bits" to bits))

    private fun current(state: JObj, documents: Map<String, JObj> = emptyMap(), profile: JObj? = jobj("document" to JObj())) =
        StateStore.Current(1, "", "", state, ByteArray(0), 0, null, JArr(), emptyList(), JArr(), emptyList(), null, null, null,
            profile, null, LinkedHashMap(documents))

    @Test
    fun `progression helpers`() {
        assertEquals(listOf(42L, -7L, 0L, 15L, 0L), listOf("  42abc", "-7", "", "+15", "x9").map { HeroDictionaryProgression.nativeInt(it) })
        val e = assertThrows(IllegalArgumentException::class.java) { HeroDictionaryProgression.nativeInt("2147483648") }
        assertEquals("atoi input outside bounded signed 32-bit domain", e.message)
        assertEquals(HeroDictionaryProgression.HeroId(12345, 78, 6, 0), HeroDictionaryProgression.unpackHeroId(12345678))
        assertThrows(IllegalArgumentException::class.java) { HeroDictionaryProgression.unpackHeroId(1L shl 32) }
    }

    @Test
    fun `hero stat model`() {
        assertEquals(listOf(2234L, 558L, 6L, 173792L), HeroStats.recomputeGrow(listOf(1000, 250, 3, 77777), 12345))
        assertEquals(listOf(197440, 19999805, 624, 8600014592).map { BigInteger.valueOf(it) },
            HeroStats.baseStats(listOf(1234L, 99999L, 3L, 70000L).map { BigInteger.valueOf(it) }, BigInteger.valueOf(150), 7,
                listOf(10000, 12500, 13000, 9000), listOf(0L, 5L, 0L, 1L shl 33).map { BigInteger.valueOf(it) }))
        val template = 55501202L
        val inputs = jobj("raw_511_514" to listOf(500, 120, 80, 60), "current_potential_rate" to 1500, "property_533" to 2500,
            "awaken_slots" to jarr(jobj("slot" to 1, "value" to 9, "type" to 4, "kind" to 1, "permille" to 3000, "kind2" to 8, "permille2" to 500)),
            "template" to template)
        val fields = JArr(mutableListOf(field(0, 6, 3), field(1, 6, template), field(2, 4, 30), field(3, 6, 10), field(4, 6, 31193),
            field(5, 4, 575), field(6, 6, 6037), field(7, 4, 138), field(8, 6, 4193), field(9, 4, 92), field(10, 6, 3018), field(11, 4, 69),
            field(17, 6, 7), field(24, 5, 1)))
        val resolved = HeroStats.resolveProfile(fields, inputs)
        assertEquals("[575,138,92,69]", Json.compact(resolved["full_grow"]!!))
        assertEquals("[15500,12500,13000,12500]", Json.compact(resolved["permille"]!!))
        assertEquals("[\"development\",\"super_class_property_533\",\"awaken_herojuexingskill\"]", Json.compact(resolved["components"]!!))
        fields[4].asObj.obj("value")["bits"] = io.github.okexodus.openknights.exact.JInt(31194)
        val refused = assertThrows(Acquisition.Rejected::class.java) {
            HeroStats.resolveProfile(fields, inputs, HeroStats.Reject { m, c -> Acquisition.Rejected(m, c) })
        }
        assertEquals("Hero stats are not reproduced by the bounded stat model; profile unsupported", refused.message)
        assertEquals(102, refused.code)
    }

    @Test
    fun `item property values`() {
        assertEquals(21323L, EquipEvolve.equipPropertyValue(41, 0, 3, 1700, 450, 900, 1500))
        assertEquals(24521L, EquipEvolve.equipPropertyValue(41, 1, 3, 1700, 450, 900, 1500))
        assertEquals(348611L, EquipEvolve.jewelPropertyValue(77, 1, 0, 6100, 2700, 12300, 2222))
    }

    private val madeUpTables = """{"title":{"3":[100,20,30]},"technology":{"11":[7,5]},"questmedal":[[1,10,1,50,8,5],[2,100,7,9,6,4]],
        "god_skill":{"901":[1,250]},"property":{"243":0,"4001":12000,"4007":5000},
        "totem":{"21":{"group":5,"stats":[[1,300,12],[7,40,3],[8,20,2],[6,10,1]]}},"totem_adv":[[1,5,1000],[2,5,1500]],
        "zodiac":{"31":[8,100,10]},"photo":[[1,41,[55,56],[[1,60],[6,11]]]],
        "equip":{"501":[7,4,[[1,30],[0,0],[8,5],[0,0],[7,9],[1,1]]]},"jewel":{"601":[1,2,[[6,4],[0,0],[0,0],[0,0],[0,0],[0,0]]]},
        "gem":{"701":[25,40],"702":[7,15]},"secondary_effect":[[6,500],[7,500]],
        "combo":[{"key":1,"enabled":1,"hero":55,"partners":[[1,56],[0,0],[0,0],[0,0]],"bonuses":[[203,1500],[201,1000],[0,0],[0,0],[0,0]]}],
        "hero_class":{"55":2,"56":4,"57":1}}"""

    private inner class FakeInputs : AcquisitionInputs(GameTables(TextTables(emptyMap()))) {
        private val t = Json.loads(madeUpTables).asObj
        override fun battleStatTables(): JObj = t
        override fun propertyValueInputs(kind: String, template: Long, grade: Long): JObj =
            if (kind == "gear") jobj("base_108" to 1700, "growth_109" to 450, "potential_before" to 900, "property_912" to 1500)
            else jobj("base_108" to 6100, "ratio_110" to 2700, "potential_before" to 12300, "property_956" to 2222)
    }

    private fun lineupState(): JObj {
        fun hero(uid: Long, template: Long, hp: Long, atk: Long, df: Long, un: Long, r22: Long = 0, r23: Long = 0) =
            JArr(mutableListOf(field(0, 6, uid), field(1, 6, template), field(2, 4, 10), field(4, 6, hp), field(6, 6, atk),
                field(8, 6, df), field(10, 6, un), field(22, 6, r22), field(23, 6, r23)))
        val jewel = WireWriter().u32(1).u32(601).u32(0).u32(60).raw(byteArrayOf(6, 1, 0, 0)).u32(2).raw(ByteArray(16)).bytes()
        return jobj(
            "heroes" to jarr(hero(1, 55002, 5000, 900, 700, 300, 3, 4), hero(2, 56003, 4000, 800, 600, 200), hero(3, 57001, 3000, 700, 500, 100)),
            "role_properties" to jarr(jobj("id" to 22, "value" to jobj("tag" to 6, "bits" to 3))),
            "equipment" to jarr(jobj("wire_values" to jarr(1, 501, 41, 0, 7, 1, 3)), jobj("wire_values" to jarr(2, 999, 1, 0, 1, 0, 0))),
            "formation" to jarr(
                jobj("slot_id" to 0, "hero_uid" to 1, "flag" to 2, "assignments" to jarr(jarr(0, 1)),
                    "blocks_40" to jarr(jobj("id" to 1, "raw_hex" to jewel.toHexString())), "groups" to jarr(jobj("id" to 0, "values" to jarr(701, 702)))),
                jobj("slot_id" to 1, "hero_uid" to 2, "flag" to 0, "assignments" to JArr(), "blocks_40" to JArr(), "groups" to JArr()),
                jobj("slot_id" to 2, "hero_uid" to 3, "flag" to 1, "assignments" to jarr(jarr(2, 2)), "blocks_40" to JArr(), "groups" to JArr())),
            "subsystems" to jobj(
                "technologies" to jobj("entries" to jarr(jobj("wire_values" to jarr(11, 4)))),
                "hero_collection" to jobj("entries" to jarr(jobj("wire_values" to jarr(55001)), jobj("wire_values" to jarr(56001)))),
                "equip_collection" to jobj("entries" to JArr()), "jewelry_collection" to jobj("entries" to JArr()),
                "xinggong" to jobj("entries" to jobj("entries" to jarr(jobj("wire_values" to jarr(31, 3, 0)))))))
    }

    @Test
    fun `team power of a made-up lineup`() {
        val world = jobj("album_activated" to jarr(41), "quest_points" to 50, "god_skills" to jobj("1" to jarr(jarr(901, 1))),
            "totems" to jobj("entries" to jarr(jarr(21, 2, 5, 0)), "lineup" to 21), "secondary_team" to jarr(jarr(0, 3)), "leader_uid" to 1)
        val team = BattleStats.teamPower(lineupState(), FakeInputs(), world)
        assertEquals(BigInteger.valueOf(1120019), team.int("power"))
        val slot0 = team.arr("slots")[0].asObj
        assertEquals("""{"wire":[5000,900,700,300],"album":[60,0,0,11],"technology":[0,20,0,0],"title":[100,30,0,20],"quest_medal":[50,0,5,0],""" +
            """"god_skill":[250,0,0,0],"totem":[506,75,39,19],"zodiac":[0,0,130,0],"gear_main":[0,24521,0,0],"gear_advance":[30,0,5,0],""" +
            """"runes":[0,15,0,0],"jewel_main":[272302,0,0,0],"jewel_advance":[0,0,0,4],"secondary_team":[0,41,0,7],"combo":[27829,3840,0,0]}""",
            Json.compact(slot0.obj("terms")))
        assertEquals(listOf(306127L, 29442L, 879L, 361L, 43L, 4L, 1078718L, 51071L),
            listOf("hp", "atk", "def", "crit", "reborn_atk", "reborn_def", "score", "hero_ability_score").map { slot0.long(it) })
        assertEquals("EquipConfig row 999 absent (GetBattleSlotAbility returns false)", team.arr("slots")[2].asObj.str("reason"))
        assertTrue(team.bool("complete"))
        val noItemInputs = object : AcquisitionInputs(GameTables(TextTables(emptyMap()))) {
            override fun battleStatTables(): JObj = FakeInputs().battleStatTables()
            override fun propertyValueInputs(kind: String, template: Long, grade: Long): JObj? = null
        }
        val unresolved = BattleStats.teamPower(lineupState(), noItemInputs, world)
        assertEquals(BigInteger.valueOf(97970), unresolved.int("power"))
        assertEquals("""[{"term":"gear_main","needs":"equip 501 grade 7 property inputs","class":"UNRES","note":"catalog inputs unavailable"},""" +
            """{"term":"jewel_main","needs":"jewelry 601: the client draws rand() over its base/ratio range (GetJewelPropertyValue 0x00b4ec84) or the """ +
            """catalog inputs are unavailable","class":"UNRES","note":"no deterministic value; contributes nothing"}]""", Json.compact(unresolved.arr("unresolved")))
        val partial = BattleStats.teamPower(lineupState(), FakeInputs(), null)
        assertEquals(BigInteger.valueOf(1107445), partial.int("power"))
        assertEquals(listOf("album", "quest_medal", "god_skill", "totem", "zodiac", "secondary_team"), partial.arr("unresolved").map { it.asObj.str("term") })
    }

    @Test
    fun `stat frames and binary32 helpers`() {
        assertEquals(12415L, BattleStats.combat(1000, 200, 300, 50, 7, 9))
        val f = { x: Double -> BattleStats.f32(x) }
        assertEquals(java.lang.Double.parseDouble("0x1.770a3e0000000p+1"), BattleStats.fmadd32(f(1.1), f(3.3), f(-0.7)))
        val totems = WireWriter().u8(2).u32(11).u8(2).u32(30).number('Q', 5).u32(12).u8(1).u32(1).number('Q', 0).u32(12).bytes()
        assertEquals("""{"entries":[[11,2,30,5],[12,1,1,0]],"lineup":12}""", Json.compact(BattleStats.decodeTotemInit(totems)))
        assertThrows(IllegalArgumentException::class.java) { BattleStats.decodeTotemInit(totems + byteArrayOf(0)) }
        val world = BattleStats.worldFromPayloads(linkedMapOf(
            548 to WireWriter().u32(2).u32(7).u32(9).bytes(),
            320 to WireWriter().u8(1).u32(1).u8(2).u32(3).u32(77).bytes(),
            2240 to WireWriter().u32(4).u32(5).bytes(),
            2880 to WireWriter().u8(0).u32(0).bytes(),
            2848 to WireWriter().u32(1).u32(4).u32(1).u32(21).u32(1).bytes()))
        assertEquals("""{"album_activated":[7,9],"quest_points":77,"god_skills":{"4":[[21,1]]},"totems":{"entries":[],"lineup":0},"leader_uid":4}""",
            Json.compact(world))
        assertEquals("03000000c9020000", HeroEvolution.leaderInfoPayload(3, 713).toHexString())
        assertThrows(IllegalArgumentException::class.java) { HeroEvolution.leaderInfoPayload(1L shl 32, 0) }
    }

    @Test
    fun `sweep login frames`() {
        val placed = SweepFeatures.placeStartupFrames(listOf(18 to byteArrayOf(1), 2976 to ByteArray(0), 64 to byteArrayOf(2)), byteArrayOf(0xaa.toByte()), byteArrayOf(0xbb.toByte()))
        assertEquals(listOf(18 to "01", 2880 to "aa", 548 to "bb", 2976 to "", 64 to "02"), placed.map { it.first to it.second.toHexString() })
        assertEquals("0100460500", SweepFeatures.tmpVipPayload(1, 345600).toHexString())
        assertEquals(1 to 600L, SweepFeatures.tmpVipView(jobj("claimed" to true, "expires_at" to 1000), 400))
        assertEquals(2 to 0L, SweepFeatures.tmpVipView(jobj("claimed" to true, "expires_at" to 1000), 400, 4))
        assertEquals(listOf(7L, 9L), SweepFeatures.decodeAlbum(WireWriter().u32(2).u32(7).u32(9).bytes()))
        assertThrows(IllegalArgumentException::class.java) { SweepFeatures.decodeAlbum(byteArrayOf(1, 0, 0, 0)) }
        val seeds = SystemSeeds.SeedFrames(mapOf(548 to listOf(WireWriter().u32(1).u32(5).bytes()), 2880 to listOf(byteArrayOf(0, 0, 0, 0, 0))), "made_up")
        val state = jobj("heroes" to JArr(), "role_properties" to JArr())
        val frames = SweepFeatures.startupFrames(current(state), seeds)
        assertEquals("0100000005000000", frames.album.toHexString())
        assertEquals("0000000000", frames.totems.toHexString())
        val stored = SweepFeatures.startupFrames(current(state, mapOf("album_state" to jobj("families" to jarr(3, 4)))), seeds)
        assertEquals("020000000300000004000000", stored.album.toHexString())
    }

    @Test
    fun `warehouse capacity login repair`() {
        val inputs = DailyInputs(GameTables(TextTables(mapOf(
            "building.csv" to "101,103,106,109,110\n6,1,0,0,120\n1,1,0,0,50\n",
            "property.csv" to "101,102\n3,40\n"))))
        val state = jobj("item_capacity_values" to jarr(60, 50, 100),
            "subsystems" to jobj("buildings" to jobj("count" to 2, "entries" to jarr(jobj("wire_values" to jarr(1, 5)), jobj("wire_values" to jarr(6, 12))))))
        assertTrue(SweepFeatures.capacityRepairNeeded(state, inputs))
        val owned = Owned(current(state.deepCopy().also { it["items"] = JArr() }), inputs)
        val plan = SweepFeatures.planCapacityRepair(owned, inputs)
        assertEquals("""{"warehouse_level":12,"warehouse_level_after":120,"item_capacity_before":[60,50,100],"item_capacity_after":[160,100,100],""" +
            """"evidence_class":"native_use_formula_login_repair_policy"}""", Json.compact(plan.data))
        assertTrue(plan.packets.isEmpty())
        assertEquals("[160,100,100]", Json.compact(owned.state["item_capacity_values"]!!))
        assertFalse(SweepFeatures.capacityRepairNeeded(owned.state, inputs))
        val unchanged = assertThrows(SweepFeatures.Unchanged::class.java) { SweepFeatures.planCapacityRepair(Owned(current(owned.state), inputs), inputs) }
        assertEquals(listOf(72 to "a00064006400"), unchanged.packets.map { it.first to it.second.toHexString() })
    }

    @Test
    fun `leader super steps from the history`() {
        assertEquals(setOf(55501105L), LeaderRepair.superReachedTemplates(listOf(55501005L to 55501105L, 55501105L to 55501005L, 0L to 1L,
            55501005L to 55502105L, null to 55501105L)))
        assertNull(HeroEvolution.checkTestPolicy(null))
        assertThrows(HeroEvolution.EvolutionRejected::class.java) { HeroEvolution.checkTestPolicy(jobj("document" to jobj("profile" to "x"))) }
    }
}
