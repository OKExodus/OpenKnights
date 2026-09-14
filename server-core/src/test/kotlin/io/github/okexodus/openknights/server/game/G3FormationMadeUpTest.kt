package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JNull
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.hexBytes
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.toHexString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** CI checks of the formation planners and codecs with made-up values (no game tables). */
class G3FormationMadeUpTest {
    private val gemTypes = mapOf(4101L to 1L, 4102L to 1L, 4201L to 2L, 4601L to 25L, 4701L to 26L)
    private val gemRows = mapOf(4101L to FormationInputs.GemRow(1, 4102, 3), 4102L to FormationInputs.GemRow(1, 0, 3),
        4201L to FormationInputs.GemRow(2, 4299, 3))

    private fun slot(id: Int, hero: Long = 0, flag: Int = 0, assignments: JArr = JArr(), blocks: JArr = JArr(), groups: JArr = JArr()) =
        jobj("slot_id" to id, "hero_uid" to hero, "assignments" to assignments, "blocks_40" to blocks, "groups" to groups, "flag" to flag)

    private fun view(formation: List<JObj>, bench: List<Long>, bag: List<Long> = emptyList(), gems: List<Pair<Long, Long>> = emptyList(),
                     jewels: JArr = JArr(), heroes: Map<Long, Map<Long, JValue?>>? = null): EquipFormation.View {
        val owned = heroes ?: (bench + formation.map { it.long("hero_uid") }.filter { it != 0L }).associateWith { mapOf<Long, JValue?>(19L to JInt(0)) }
        return EquipFormation.View(JArr(formation.toMutableList<JValue>()), JInt(0), JArr(bench.mapTo(ArrayList<JValue>()) { JInt(it) }),
            JArr(bag.mapTo(ArrayList<JValue>()) { JInt(it) }), mapOf(21L to jarr(21, 8801, 1, 0, 1, 0, 0), 22L to jarr(22, 8802, 1, 0, 1, 0, 0)),
            owned, jobj("count" to gems.size, "entries" to gems.map { jobj("wire_values" to jarr(it.first, it.second)) }), gemTypes, jewels)
    }

    private fun twelve(vararg filled: JObj): List<JObj> = (0 until 12).map { id -> filled.firstOrNull { it.long("slot_id") == id.toLong() } ?: slot(id) }

    private fun frames(plan: EquipFormation.FormationPlan) = plan.packets.map { "${it.first}:${it.second.toHexString()}" }

    private fun req(vararg pairs: Pair<String, Any?>) = jobj(*pairs)

    @Test
    fun `request codec`() {
        assertEquals(Json.compact(jobj("slot" to 2, "hero_uid" to 77)), Json.compact(EquipFormation.decodeRequest(67, "024d000000".hexBytes())))
        assertEquals("024d000000", EquipFormation.encodeRequest(67, jobj("slot" to 2, "hero_uid" to 77)).toHexString())
        val e = assertThrows(EquipFormation.FormationRejected::class.java) { EquipFormation.decodeRequest(35, "00".hexBytes()) }
        assertEquals("Opcode 35 request has the wrong length", e.message)
        assertEquals(102, e.code)
    }

    @Test
    fun `lineup into an empty slot takes the next free position`() {
        val v = view(twelve(slot(0, 11, flag = 1)), listOf(12, 13))
        val plan = EquipFormation.planLineup(req("slot" to 1, "hero_uid" to 13), v)
        assertEquals(listOf("40:010d000000", "42:010d000000", "12:0102"), frames(plan))
        assertEquals(listOf(12L), plan.view.offlineHeroUids.map { it.long })
        assertEquals(2L, plan["position_assigned"]!!.long)
        assertEquals(listOf(13L), v.offlineHeroUids.drop(1).map { it.long }, "the input view is not changed")
    }

    @Test
    fun `substitution strips bonus runes type 25 first and returns the old hero sorted`() {
        val groups = JArr(mutableListOf<JValue>(jobj("id" to 0, "values" to jarr(4701, 4101, 4601))))
        val v = view(twelve(slot(0, 11, flag = 1, groups = groups)), listOf(12, 14), gems = listOf(4101L to 2L, 4601L to 0L))
        val plan = EquipFormation.planLineup(req("slot" to 0, "hero_uid" to 12), v)
        assertEquals(listOf("40:010c000000", "1538:0000025d12000005100000", "1536:f911000001000000",
            "1538:00000105100000", "1536:5d12000001000000", "42:000c000000", "38:010b000000"), frames(plan))
        assertEquals(listOf(11L, 14L), plan.view.offlineHeroUids.map { it.long })
        assertEquals("""{"count":3,"entries":[{"wire_values":[4101,2]},{"wire_values":[4601,1]},{"wire_values":[4701,1]}]}""", Json.compact(plan.view.gems))
        assertEquals(JNull, plan["position_assigned"])
    }

    @Test
    fun `lineup refusals carry their codes`() {
        val v = view(twelve(slot(0, 11, flag = 1)), listOf(12))
        assertEquals(1000, assertThrows(EquipFormation.FormationRejected::class.java) { EquipFormation.planLineup(req("slot" to 1, "hero_uid" to 99), v) }.code)
        assertEquals(1000, assertThrows(EquipFormation.FormationRejected::class.java) { EquipFormation.planLineup(req("slot" to 1, "hero_uid" to 11), v) }.code)
        assertEquals(102, assertThrows(EquipFormation.FormationRejected::class.java) { EquipFormation.planLineup(req("slot" to 6, "hero_uid" to 12), v) }.code)
        val e = assertThrows(EquipFormation.FormationRejected::class.java) { EquipFormation.planLineup(req("slot" to 0, "hero_uid" to 12), v, leaderUids = listOf(11)) }
        assertEquals("The leader/guiding hero's slot cannot be substituted", e.message)
    }

    @Test
    fun `position swap and captain`() {
        val v = view(twelve(slot(0, 11, flag = 1), slot(1, 12, flag = 2)), emptyList())
        assertEquals(listOf("12:0101", "12:0002"), frames(EquipFormation.planPosition(req("slot" to 0, "position" to 2), v)))
        assertEquals(listOf("44:01"), frames(EquipFormation.planCaptain(req("slot" to 1), v)))
        assertEquals(1000, assertThrows(EquipFormation.FormationRejected::class.java) { EquipFormation.planCaptain(req("slot" to 7), v) }.code)
    }

    @Test
    fun `gear equip replaces the worn piece`() {
        val v = view(twelve(slot(0, 11, flag = 1, assignments = jarr(jarr(0, 21)))), emptyList(), bag = listOf(22))
        val plan = EquipFormation.planEquip(req("slot" to 0, "position" to 0, "uid" to 22), v) { 0L }
        assertEquals(listOf("100:0115000000", "104:000000000000", "102:0116000000", "104:000016000000"), frames(plan))
        assertEquals(6001, assertThrows(EquipFormation.FormationRejected::class.java) {
            EquipFormation.planEquip(req("slot" to 0, "position" to 1, "uid" to 22), v) { 0L } }.code)
    }

    @Test
    fun `jewelry equip uses the default tail and unequip keeps the block tail`() {
        val jewels = jarr(jobj("record" to jarr(31, 8805, 4, 120, 2, 0, 0), "tail" to null))
        val v = view(twelve(slot(0, 11, flag = 1)), emptyList(), jewels = jewels)
        val plan = EquipFormation.planJewel(req("slot" to 0, "position" to 2, "uid" to 31), v) { 2L }
        assertEquals(listOf("3082:00021f000000", "3074:011f000000"), frames(plan))
        val block = plan.view.formation[0].asObj.arr("blocks_40")[0].asObj.str("raw_hex")
        assertEquals("1f000000" + "65220000" + "78000000" + "04000000" + "02" + EquipFormation.DEFAULT_JEWEL_TAIL.toHexString(), block)
        val back = EquipFormation.planJewel(req("slot" to 0, "position" to 2, "uid" to 0), plan.view) { 2L }
        assertEquals(listOf("3076:011f000000652200000400000078000000020000000000", "3082:000200000000"), frames(back))
        assertEquals(EquipFormation.DEFAULT_JEWEL_TAIL.toHexString(), back.view.jewelList[0].asObj.str("tail"))
    }

    @Test
    fun `rune equip, unequip and combine`() {
        val v = view(twelve(slot(0, 11, flag = 1)), emptyList(), gems = listOf(4101L to 7L, 4102L to 1L))
        val on = EquipFormation.planRuneEquip(req("slot" to 0, "group" to 0, "gem_id" to 4101), v)
        assertEquals(listOf("1536:0510000006000000", "1538:00000105100000"), frames(on))
        val swap = EquipFormation.planRuneEquip(req("slot" to 0, "group" to 0, "gem_id" to 4102), on.view)
        assertEquals(listOf("1536:0510000007000000", "1536:0610000000000000", "1538:00000106100000"), frames(swap))
        assertEquals("""{"count":1,"entries":[{"wire_values":[4101,7]}]}""", Json.compact(swap.view.gems))
        val off = EquipFormation.planRuneUnequip(req("slot" to 0, "group" to 0, "gem_id" to 4102), swap.view)
        assertEquals(listOf("1538:000000", "1536:0610000001000000"), frames(off))
        val all = EquipFormation.planRuneCombine(req("mode" to 2, "gem_id" to 4101), v, gemRows)
        assertEquals(2L, all["times"]!!.long)
        assertEquals("1536:0510000001000000", frames(all)[0])
        assertEquals(69503, assertThrows(EquipFormation.FormationRejected::class.java) {
            EquipFormation.planRuneCombine(req("mode" to 1, "gem_id" to 4102), v, gemRows) }.code)
        assertEquals(69503, assertThrows(EquipFormation.FormationRejected::class.java) {
            EquipFormation.planRuneCombine(req("mode" to 1, "gem_id" to 4201), v, gemRows) }.code)
    }

    @Test
    fun `gem bag helpers`() {
        val section = EquipFormation.gemsSectionWith(linkedMapOf(4201L to 2L, 4101L to 0L, 4102L to 5L))
        assertEquals("""{"count":2,"entries":[{"wire_values":[4102,5]},{"wire_values":[4201,2]}]}""", Json.compact(section))
        assertThrows(EquipFormation.FormationRejected::class.java) {
            EquipFormation.gemCounts(jobj("entries" to jarr(jobj("wire_values" to jarr(1, 1)), jobj("wire_values" to jarr(1, 2)))))
        }
        assertEquals("Gem count must fit uint32", assertThrows(IllegalArgumentException::class.java) { EquipFormation.gemBagFrame(1, -1) }.message)
    }
}
