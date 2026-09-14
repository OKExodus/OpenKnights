package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.toHexString
import io.github.okexodus.openknights.protocol.WireWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigInteger

/**
 * CI (no game data): the campaign primitives that need no catalog — the drops SplitMix64, `battle_seed`, the request
 * decoders and their refusals, and the S162 first-kill payload — on made-up guard-clean values. The expected numbers
 * come from the reference on the same inputs.
 */
class G9CampaignMadeUpTest {
    private fun ubits(d: Double): Long = java.lang.Double.doubleToRawLongBits(d)

    @Test
    fun `splitmix reproduces the reference stream`() {
        val n = Campaign.SplitMix64(12_345_678)
        assertEquals(listOf("1285161397399512697", "7146091996086701242", "11283735336711330924", "1694233733035357502"),
            (0 until 4).map { n.next().toString() })  // unsigned u64 decimal
        val r = Campaign.SplitMix64(12_345_678)
        assertEquals(listOf(4589684582371756768L, 4600650225382834338L, 4603684855940154995L), (0 until 3).map { ubits(r.random()) })
        val ri = Campaign.SplitMix64(12_345_678)
        assertEquals(listOf(2L, 5L, 1L, 3L), (0 until 4).map { ri.randint(1, 6) })
    }

    @Test
    fun `battle seed is the unsigned little-endian sha prefix`() {
        assertEquals(BigInteger.valueOf(351692730435001100L),
            Campaign.battleSeed("char_12345678", 7, 12_345_678, 1_789_000_000, 0, 6))
    }

    @Test
    fun `decode battle and auto`() {
        val battle = WireWriter().number('I', 12_345_678L).number('I', 0L).number('B', 6L).bytes()
        assertEquals(12_345_678L, Campaign.decodeBattle(battle).long("stage"))
        assertEquals(6L, Campaign.decodeBattle(battle).long("slot"))
        val auto = WireWriter().number('I', 12_345_678L).number('I', 0L).number('B', 6L).number('B', 0L).number('I', 3L).bytes()
        assertEquals(3L, Campaign.decodeAuto(auto).long("count"))
        assertEquals(12_345_678L, Campaign.decodeStage(WireWriter().number('I', 12_345_678L).bytes(), 133).long("stage"))
    }

    @Test
    fun `decode refusals carry the reference codes`() {
        assertEquals(Campaign.ERR_INVALID, (assertThrows(Acquisition.Rejected::class.java) { Campaign.decodeBattle(ByteArray(3)) }).code)
        // a helper with the "no helper" slot 6 is a support error
        val bad = WireWriter().number('I', 12_345_678L).number('I', 99L).number('B', 6L).bytes()
        assertEquals(Campaign.ERR_SUPPORT, (assertThrows(Acquisition.Rejected::class.java) { Campaign.decodeBattle(bad) }).code)
        val badCount = WireWriter().number('I', 12_345_678L).number('I', 0L).number('B', 6L).number('B', 0L).number('I', 0L).bytes()
        assertEquals(Campaign.ERR_AUTO, (assertThrows(Acquisition.Rejected::class.java) { Campaign.decodeAuto(badCount) }).code)
    }

    @Test
    fun `first kill payload with and without a winner`() {
        val entry = io.github.okexodus.openknights.exact.jobj("name_hex" to "4162", "at" to 1_789_000_000)
        assertEquals("4e61bc0041620045f9a16a", Campaign.firstKillPayload(12_345_678, entry, 5).toHexString())
        assertEquals("4e61bc000000000000", Campaign.firstKillPayload(12_345_678, null).toHexString())
    }
}
