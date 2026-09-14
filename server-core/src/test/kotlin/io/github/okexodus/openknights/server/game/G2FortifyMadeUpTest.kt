package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.asArr
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.hexBytes
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.toHexString
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigInteger

/** CI checks of the Fortify-family rules that need no game table, on made-up values. */
class G2FortifyMadeUpTest {
    private val curve = JObj().also { c -> for (level in 1L..20L) c[level.toString()] = JInt(level * 100) }

    @Test
    fun `fortify request and counted UID codecs`() {
        val payload = "0b0000000207000000d2040000".hexBytes()
        val request = HeroFortify.decodeFortifyRequest(payload)
        assertEquals(11L, request.long("target_uid"))
        assertEquals(listOf(7L, 1234L), request.arr("material_uids").map { it.long })
        assertArrayEquals(payload, HeroFortify.encodeFortifyRequest(request))
        assertThrows<PyValues.ValueError> { HeroFortify.decodeFortifyRequest(payload + byteArrayOf(0)) }
        assertThrows<IllegalArgumentException> { HeroFortify.decodeFortifyRequest(payload.copyOf(payload.size - 1)) }
        assertEquals("020700000009000000", HeroFortify.countedUidPayload(listOf(7, 9)).toHexString())
        assertEquals("01000000020000000300000004000000050600000000", HeroFortify.equipmentRecordPayload(listOf(1, 2, 3, 4, 5, 6, 0)).toHexString())
        assertThrows<PyValues.ValueError> { HeroFortify.equipmentRecordPayload(listOf(1, 2, 3, 4, 256, 0, 0)) }
    }

    @Test
    fun `hero settlement levels up below the cap and refuses at the cap`() {
        // scale 10000 keeps the curve as is: 100 to leave level 1, 200 to leave level 2, ...
        val settled = HeroFortify.settleHeroExp(1, 50, 300, 10, curve, 10000)
        assertEquals(3L, settled.long("level"))
        assertEquals(50L, settled.long("residual"))
        assertEquals(2L, settled.long("levels_gained"))
        assertEquals(false, settled.bool("reached_cap"))
        val capped = HeroFortify.settleHeroExp(1, 0, 1_000_000, 4, curve, 10000)
        assertEquals(true, capped.bool("reached_cap"))
        val e = assertThrows<HeroFortify.FortifyRejected> { HeroFortify.settleHeroExp(4, 0, 10, 4, curve, 10000) }
        assertEquals(HeroFortify.ERROR_MAX_LEVEL, e.code)
        val gear = HeroFortify.settleEquipmentExp(1, 0, 1_000_000, 4, curve, 10000)
        assertEquals(0L, gear.long("residual"))
        assertEquals(1_000_000L - 600L, gear.long("discarded_at_cap"))
    }

    @Test
    fun `progression cost and EXP helpers`() {
        assertEquals(10_000L, HeroDictionaryProgression.upgradeInstanceCost(1000, 10, "10000"))
        assertEquals(0L, HeroDictionaryProgression.upgradeInstanceCost(1000, 10, "abc"))
        assertEquals(250L, HeroDictionaryProgression.expForLevel(250, 10000))
        assertEquals(125L, HeroFortify.expForEquipLevel(250, 5000))
        val consumed = HeroDictionaryProgression.consumedHeroExp(250, 0, 3, 3, 0, 10000, listOf(10, 20))
        assertEquals(2L, consumed.long("multiplier"))
        assertEquals(500L, consumed.long("value"))
        assertThrows<PyValues.ValueError> { HeroDictionaryProgression.consumedHeroExp(250, 0, 3, 3, 0, 10000, listOf(10)) }
    }

    private fun staged(itemExp: Long, quantity: Long, owned: Long) =
        jobj("request_template" to 11, "item_id" to 22, "item_exp" to itemExp, "owned_uid" to 33, "owned_count" to owned, "quantity" to quantity)

    @Test
    fun `item consumption stops at the cap and tops up only with the fill policy`() {
        val requirement = { level: Long -> level * 100 }
        // 100 + 200 + 300 = 600 EXP from level 1 to the cap 4; items of 250 EXP: 3 of 5 are taken
        val plain = ItemFortify.planConsumption(listOf(staged(250, 5, 9)), 1, 0, 4, requirement)
        assertEquals(4L, plain.long("new_level"))
        assertEquals(0L, plain.long("new_exp"))
        val consumed = plain.arr("consumed")[0].asObj
        assertEquals(3L, consumed.long("taken"))
        assertEquals(6L, consumed.long("remaining_stack"))
        assertEquals(1250L, plain.long("total_cost_base"))
        // below the cap: every staged item, the residual kept
        val below = ItemFortify.planConsumption(listOf(staged(40, 2, 2)), 1, 0, 4, requirement)
        assertEquals(1L, below.long("new_level"))
        assertEquals(80L, below.long("new_exp"))
        // a staged quantity equal to the client's maximum is topped up from the stack with the fill policy
        val fill = jobj("client_item_maximum" to 3, "max_items_per_action" to 100)
        val topped = ItemFortify.planConsumption(listOf(staged(100, 3, 50)), 1, 0, 4, requirement, fillToCap = fill)
        assertEquals(6L, topped.arr("consumed")[0].asObj.long("taken"))
        assertEquals(3L, topped.arr("consumed")[0].asObj.long("topped_up"))
        assertEquals(600L, topped.long("total_cost_base"))
        val e = assertThrows<ItemFortify.ItemFortifyRejected> { ItemFortify.planConsumption(listOf(staged(100, 1, 1)), 4, 0, 4, requirement) }
        assertEquals(ItemFortify.ERROR_MAX_LEVEL, e.code)
    }

    private val policy = jobj("profile" to ItemFortify.BONUS_POLICY_PROFILE, "class" to ItemFortify.BONUS_POLICY_CLASS,
        "odds_per_10000" to jobj("x2" to 0, "x4" to 0, "x10" to 10000))

    @Test
    fun `bonus policy check and roller`() {
        assertNull(ItemFortify.checkBonusPolicy(null))
        val loaded = jobj("document" to policy, "path" to "p", "sha256" to "s")
        assertTrue(ItemFortify.checkBonusPolicy(loaded) === loaded)
        assertThrows<ItemFortify.ItemFortifyRejected> { ItemFortify.checkBonusPolicy(jobj("profile" to "other")) }
        assertThrows<ItemFortify.ItemFortifyRejected> {
            ItemFortify.checkBonusPolicy(jobj("profile" to ItemFortify.BONUS_POLICY_PROFILE, "class" to ItemFortify.BONUS_POLICY_CLASS,
                "odds_per_10000" to jobj("x2" to 6000, "x4" to 6000)))
        }
        val roll = ItemFortify.bonusRoller(loaded, BigInteger.valueOf(4321))
        assertEquals(List(20) { 10L }, List(20) { roll() })
        val half = jobj("odds_per_10000" to jobj("x2" to 5000))
        val a = ItemFortify.bonusRoller(half, BigInteger.valueOf(99))
        val b = ItemFortify.bonusRoller(half, BigInteger.valueOf(99))
        assertEquals(List(50) { a() }, List(50) { b() })
    }

    @Test
    fun `result payload and jewelry block rewrite`() {
        val consumed = jarr(jobj("item_id" to 22, "taken" to 3, "awarded" to 300, "tally" to jobj("1" to 1, "2" to 1, "4" to 1, "10" to 0)))
        val payload = ItemFortify.itemFortifyResultPayload(consumed, "jewel_grow", jarr(jarr(5, 300, 2)))
        assertEquals("01000000" + "16000000" + "01000000" + "01000000" + "01000000" + "00000000" + "2c010000" + "00",
            payload.copyOfRange(0, 29).toHexString())
        val block = ByteArray(40) { it.toByte() }
        val rewritten = ItemFortify.jewelryBlockWith(block, exp = 7, level = 9)
        assertArrayEquals(block.copyOfRange(0, 8), rewritten.copyOfRange(0, 8))
        assertEquals("0700000009000000", rewritten.copyOfRange(8, 16).toHexString())
        assertArrayEquals(block.copyOfRange(16, 40), rewritten.copyOfRange(16, 40))
        val decoded = ItemFortify.jewelryBlockDecode(rewritten)
        assertEquals(9L, decoded.long("level"))
        assertEquals(16L, decoded.long("grade"))
    }

    @Test
    fun `class change moves the class series and stashes the Warrior-only one`() {
        val doc = jobj("heroes" to jarr(jobj("uid" to 5, "skills" to jarr(jarr(4702, 1), jarr(4803, 2), jarr(123, 0)))))
        val (moved, skills) = ChangeJob.moveSkills(doc, 5, 1, 2)
        assertEquals("[[123,0],[4903,2]]", io.github.okexodus.openknights.exact.Json.compact(skills!!))
        assertEquals(jarr(4702, 1), moved.obj("class_change_stash")["5"])
        val (back, again) = ChangeJob.moveSkills(moved, 5, 2, 1)
        assertEquals("[[123,0],[4702,1],[4803,2]]", io.github.okexodus.openknights.exact.Json.compact(again!!))
        assertTrue("class_change_stash" !in back)
        assertNull(ChangeJob.moveSkills(doc, 6, 1, 2).second)
        assertThrows<Acquisition.Rejected> { ChangeJob.decodeRequest(byteArrayOf(4)) }
    }

    @Test
    fun `rebirth and reborn requests and the acquisition skeleton`() {
        assertEquals(jobj("target_uid" to 7, "staged" to jarr(jarr(11, 2))), Rebirth.decodeFortifyRequest(("0700000001000000" + "0b00000002000000").hexBytes()))
        assertThrows<Acquisition.Rejected> { Rebirth.decodeFortifyRequest("07000000".hexBytes()) }
        assertThrows<Acquisition.Rejected> { Rebirth.decodeEvolveRequest(byteArrayOf(1)) }
        assertEquals(jobj("uid" to 7, "target_base" to 9), Reborn.decodeRequest("0700000009000000".hexBytes()))
        assertTrue(AcquisitionRoutes.isReadOnly(89, ByteArray(0)))
        assertTrue(AcquisitionRoutes.isReadOnly(2725, byteArrayOf(1, 0, 0, 0, 0)))
        assertTrue(!AcquisitionRoutes.isReadOnly(2725, byteArrayOf(0, 0, 0)))
        assertEquals("rebirth_evolve", AcquisitionRoutes.ACTIONS[95])
        val row = jobj("materials" to jarr(jarr(11, 2), jarr(0, 5), jarr(12, 0), jarr(13, 1)), "stone_type0_210" to 14, "stone_count_211" to 3, "gold_213" to 250)
        assertEquals(listOf(11L to 2L, 13L to 1L, 14L to 3L) to 250L, Reborn.rebornCost(row))
    }

}
