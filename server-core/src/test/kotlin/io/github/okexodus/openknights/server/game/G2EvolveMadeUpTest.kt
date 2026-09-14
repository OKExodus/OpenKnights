package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.hexBytes
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.toHexString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigInteger

/**
 * CI tests with made-up values (no game tables) for the table-free parts of the evolution and hero-card port. The
 * expected values come from the reference functions run on the same made-up inputs.
 */
class G2EvolveMadeUpTest {
    private val distribution = Json.loads("""{"hp":{"-3":2,"0":5,"4":3},"atk":{"1":1,"2":1},"def":{"0":7},"crit":{"-1":4,"5":1}}""") as JObj

    @Test
    fun `Power Up roll, caps and result payload`() {
        assertEquals(listOf(12L, 14L, 0L, 8L), HeroPowerUp.roll(BigInteger.valueOf(12345), 10, distribution))
        assertEquals(listOf(0L, 2L, 0L, -1L), HeroPowerUp.roll(BigInteger.valueOf(777), 1, distribution))
        assertEquals(listOf(24L, 139L, 0L, 44L), HeroPowerUp.roll(BigInteger.ONE.shiftLeft(64) - BigInteger.valueOf(3), 100, distribution))
        assertEquals(listOf(1250L, 17497L, 30862L, 0L), HeroPowerUp.devCaps(listOf(5000L, 70000L, 123457L, 9L).map { BigInteger.valueOf(it) },
            listOf(0L, 10L, 7L, 9L).map { BigInteger.valueOf(it) }, 2500))
        assertEquals("4d00000003000000feffffff00000000f7ffffff", HeroPowerUp.resultPayload(77, listOf(3, -2, 0, -9)).toHexString())
        assertThrows(IllegalArgumentException::class.java) { HeroPowerUp.resultPayload(77, listOf(1, 2, 3)) }
    }

    @Test
    fun `Power Up policy check`() {
        assertNull(HeroPowerUp.checkPolicy(null))
        val way = jobj("item" to 40002, "stones_per_try" to 3, "per_try_distribution" to distribution)
        val good = jobj("document" to jobj("profile" to HeroPowerUp.POLICY_PROFILE, "class" to HeroPowerUp.POLICY_CLASS, "ways" to jobj("1" to way)))
        assertEquals(good, HeroPowerUp.checkPolicy(good))
        val bad = jobj("document" to jobj("profile" to HeroPowerUp.POLICY_PROFILE, "class" to HeroPowerUp.POLICY_CLASS, "ways" to jobj("7" to way)))
        val e = assertThrows(HeroPowerUp.PowerUpRejected::class.java) { HeroPowerUp.checkPolicy(bad) }
        assertEquals("Power Up policy way outside 1..3", e.message)
    }

    @Test
    fun `request codecs`() {
        assertEquals(jobj("target_uid" to 13, "material_uids" to listOf(14, 15)), HeroAscension.decodeRequest("0d000000020e0000000f000000".hexBytes()))
        assertEquals("0d000000020e0000000f000000", HeroAscension.encodeRequest(jobj("target_uid" to 13, "material_uids" to listOf(14, 15))).toHexString())
        assertEquals(jobj("target_uid" to 2), HeroEvolution.decodeOrdinaryRequest("02000000".hexBytes()))
        assertThrows(IllegalArgumentException::class.java) { HeroEvolution.decodeOrdinaryRequest("0200000000".hexBytes()) }
        assertThrows(IllegalArgumentException::class.java) { HeroEvolution.decodeLeaderRequest("00".hexBytes()) }
        assertEquals(jobj("target_uid" to 1, "way" to 2, "count" to 10), HeroPowerUp.decodeTrainRequest("01000000020a00".hexBytes()))
        assertEquals(jobj("hero_uid" to 1, "skill_id" to 1100, "times" to 1), GodSkills.decodeUpgradeRequest("010000004c04000001000000".hexBytes()))
        assertEquals(18L to 1L, AltTeam.decodeSet("1200000001000000".hexBytes()))
        assertThrows(Acquisition.Rejected::class.java) { AltTeam.decodeSet("12000000ff000000".hexBytes()) }
    }

    @Test
    fun `evolution template and advance result`() {
        assertEquals(321005L, HeroEvolution.evolveTemplate(321004))
        assertEquals(321105L, HeroEvolution.evolveTemplate(321104))
        val e = assertThrows(HeroEvolution.EvolutionRejected::class.java) { HeroEvolution.evolveTemplate(321399) }
        assertEquals(HeroEvolution.ERROR_MAX_TIER, e.code)
        val result = jobj("uid" to 5, "fields" to jarr(jobj("id" to 1, "value" to jobj("tag" to 6, "bits" to 321004))),
            "reward" to HeroEvolution.emptyReward(14))
        assertEquals("05000000010106ece504000e" + "00".repeat(97), HeroEvolution.encodeAdvanceResult(result).toHexString())
    }

    @Test
    fun `evolution materials`() {
        val items = linkedMapOf(8L to jobj("wire_values" to jarr(8, 40001, 5)), 9L to jobj("wire_values" to jarr(9, 40002, 2)))
        val changes = HeroEvolution.consumeItems(listOf(40001L to 5L, 40002L to 1L, 40003L to 0L), items)
        assertEquals(listOf(66, 68), changes.map { it.packet.first })
        assertEquals("0108000000", changes[0].packet.second.toHexString())
        assertEquals("010900000001000000", changes[1].packet.second.toHexString())
        val e = assertThrows(HeroEvolution.EvolutionRejected::class.java) { HeroEvolution.consumeItems(listOf(40002L to 3L), items) }
        assertEquals(HeroEvolution.ERROR_INSUFFICIENT, e.code)
    }

    @Test
    fun `Astral Power press`() {
        val rows = jobj(
            "500" to jobj("skill" to 500, "series" to 5, "level" to 1, "need_108" to 3, "item_109" to 40001, "per_press_110" to 2, "buff_kind_111" to 1, "buff_112" to 10),
            "501" to jobj("skill" to 501, "series" to 5, "level" to 2, "need_108" to 4, "item_109" to 40001, "per_press_110" to 2, "buff_kind_111" to 1, "buff_112" to 20))
        val document = jobj("profile" to GodSkills.PROFILE, "heroes" to jarr(jobj("uid" to 3, "skills" to jarr(jarr(500, 1), jarr(900, 0)))))
        val request = jobj("hero_uid" to 3, "skill_id" to 500, "times" to 10)
        val plan = GodSkills.planUpgrade(request, document, listOf(3L), linkedMapOf(8L to jarr(8, 40001, 11)), rows)
        assertEquals(listOf(501L, 0L, 2L), listOf(plan.data.long("skill_after"), plan.data.long("progress_after"), plan.data.long("presses")))
        assertEquals("level_up", plan.data.str("stopped"))
        assertEquals(jarr(jarr(501, 0), jarr(900, 0)), plan.data["skills_after"])
        val frames = GodSkills.upgradePackets(plan)
        assertEquals(68, frames[0].first)
        assertEquals("010800000007000000", frames[0].second.toHexString())
        assertEquals("0300000002000000f5010000000000008403000000000000", frames[1].second.toHexString())
        val short = GodSkills.planUpgrade(request, document, listOf(3L), linkedMapOf(8L to jarr(8, 40001, 3)), rows)
        assertEquals("stones_exhausted_policy", short.data.str("stopped"))
        assertEquals("preservation_policy_local", short.data.str("evidence_class"))
        assertEquals(listOf(500L, 2L, 1L, 1L), listOf(short.data.long("skill_after"), short.data.long("progress_after"), short.data.long("presses"),
            short.data.obj("item_change").long("remaining")))
        val e = assertThrows(GodSkills.GodSkillRejected::class.java) { GodSkills.planUpgrade(request, document, listOf(4L), linkedMapOf(), rows) }
        assertEquals(GodSkills.ERROR_NO_SKILL, e.code)
    }

    private fun hero(uid: Long, template: Long): JArr = jarr(jobj("id" to 0, "value" to jobj("tag" to 6, "bits" to uid)),
        jobj("id" to 1, "value" to jobj("tag" to 6, "bits" to template)))

    @Test
    fun `lineup rules`() {
        val rules = SecondaryTeam.NativeLineupRules(mapOf(210L to 1L, 220L to 1L, 230L to 0L, 240L to 1L), listOf(Triple(210L, 220L, 0L)))
        val state = jobj("heroes" to jarr(hero(1, 210001), hero(2, 220001), hero(3, 230001), hero(4, 240001)),
            "formation" to jarr(jobj("hero_uid" to 2), jobj("hero_uid" to 0)))
        val primary = assertThrows(SecondaryTeam.Rejected::class.java) { rules.check(state, emptyList(), hero(1, 210001), 0) }
        assertEquals(70600, primary.clientCheck)
        rules.check(state, emptyList(), hero(3, 230001), 0)          // field 500 = 0: no scan
        rules.check(state, emptyList(), hero(4, 240001), 0)
        val alone = jobj("heroes" to state["heroes"], "formation" to JArr())
        val alternate = assertThrows(SecondaryTeam.Rejected::class.java) {
            rules.check(alone, listOf(jobj("position" to 1, "hero_uid" to 1)), hero(2, 220001), 0)
        }
        assertEquals(70601, alternate.clientCheck)
        rules.check(alone, listOf(jobj("position" to 0, "hero_uid" to 1)), hero(2, 220001), 0)   // replacing its own slot
        assertEquals(100, assertThrows(SecondaryTeam.Rejected::class.java) { rules.check(alone, emptyList(), hero(9, 999001), 0) }.clientCheck)
    }
}
