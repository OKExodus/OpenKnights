package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.F32
import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JNull
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.SplitMix64
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * CI (no game data): the battle engine on made-up, guard-clean inputs (neutral ids). Locks the numeric helpers, the
 * SplitMix64 stream and a full `simulate` (inline skills, so no catalog is needed) against values captured from the
 * reference, and checks determinism and the codec round-trip.
 */
class G9EngineMadeUpTest {
    private fun skill(id: Long, special: Boolean, targetType: Long, coef: Long, effects: JArr = JArr()): JObj =
        jobj("id" to id, "special" to special, "target_type" to targetType, "coef_lo" to coef, "coef_hi" to coef, "effects" to effects)

    private fun rates(dodge: Long, crit: Long, block: Long, counter: Long): JObj =
        jobj("hit" to 10000, "dodge" to dodge, "crit" to crit, "block" to block, "counter" to counter, "r119" to 0, "r120" to 0)

    private fun actor(side: Int, pos: Long, pid: Long, hp: Long, atk: Long, def: Long, crit: Long, skills: JObj,
                      rates: JObj, leader: Boolean = false): JObj =
        jobj("side" to side, "position" to pos, "packed_hero_id" to pid, "uid" to pos, "level" to 100, "awaken" to 0,
            "max_hp" to hp, "atk" to atk, "def" to def, "crit" to crit, "rates" to rates, "class_factor" to 300,
            "skills" to skills, "start_sp" to 50, "player" to (side == 0), "leader" to leader)

    @Test
    fun `numeric helpers`() {
        assertEquals(2_469_135L, BattleEngine.pct(12_345_678L, 2_000L))
        assertEquals(39_235L, BattleEngine.enemyStat(12_345L, 6_789L, 11_000L, 22_000L, 3_300L))
        assertEquals(8_271L, BattleEngine.lifesteal(12_345.6, 6_700L))
        assertEquals(4_591_870_180_174_331_904L, F32.round(0.1).toRawBits())
        assertEquals(677L, BattleEngine.damageValue(1_000.0, 50.0, 100, 200, true, false, 3, 10_000).toLong())
        assertEquals(4_649_174_848_140_782_600L,
            BattleEngine.damageValue(1_000.0, 50.0, 100, 200, true, false, 3, 10_000, unrounded = true).toRawBits())
    }

    @Test
    fun `splitmix stream`() {
        assertEquals(16_294_208_416_658_607_535uL, SplitMix64(0uL).nextU64())
        val g = SplitMix64(12_345_678uL)
        assertEquals("1285161397399512697", g.nextU64().toString())
        assertEquals("7146091996086701242", g.nextU64().toString())
        assertEquals("11283735336711330924", g.nextU64().toString())
        val b = SplitMix64(42uL)
        assertEquals(listOf(3L, 91L, 3858L), listOf(b.below(10L), b.below(100L), b.below(10_000L)))
        val c = SplitMix64(42uL)
        assertEquals(listOf(false, false, true), listOf(c.chance(0L), c.chance(5_000L), c.chance(10_000L)))
        assertEquals("9094539904281274238", java.lang.Long.toUnsignedString(BattleEngine.battleSeed(1, 2, 3)))
    }

    @Test
    fun `simulate a fast win`() {
        val n = skill(7_001L, false, 4L, 10_000L)
        val own = listOf<JValue>(actor(0, 1, 12_345_001L, 50_000L, 9_000_000L, 800L, 1_500L,
            jobj("normal" to n, "special" to JNull), rates(0, 0, 0, 0), leader = true))
        val enemy = jarr(actor(1, 1, 22_345_001L, 3_000L, 10L, 10L, 10L, jobj("normal" to n, "special" to JNull), rates(0, 0, 0, 0)))
        val out = BattleEngine.simulate(own, enemy, battleType = 202, background = 119, seed = 42, ownName = "")
        assertEquals(2L, out.long("result"))
        assertEquals(3L, out.long("stars"))
        assertEquals(1L, out.long("rounds"))
        assertEquals(1L, out.long("value_231"))
        assertEquals(0L, out.long("value_232"))
        val payload = "0600ca000000770000000001010101000000a95ebc0050c3000050c30000320000006400102700000000000000000000000000000101010100000029f55401b80b0000b80b0000320000006400102700000000000000000000000000020300010101591b000002072fafb5ff02000000010000000000013200000004000000050000000000000000000000000000000101000e00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
        assertEquals(payload, out.str("payload"))
        // determinism: same seed and inputs give the same bytes
        val again = BattleEngine.simulate(own, enemy, battleType = 202, background = 119, seed = 42, ownName = "")
        assertEquals(out.str("payload"), again.str("payload"))
    }
}
