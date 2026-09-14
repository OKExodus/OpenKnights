package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.toHexString
import io.github.okexodus.openknights.server.DeviceClock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The daily counters' table-free rules on made-up values (expected values produced by the reference). */
class G2CountersRulesTest {
    @AfterEach
    fun reset() { DeviceClock.active = null }

    private fun events(action: String, plan: JObj, packets: List<Frame> = emptyList()): String =
        (DailyHooks.eventsFor(action, plan, packets).map { it.json() } to DailyHooks.achievementEvents(action, packets).map { it.json() }).toString()

    private fun expected(events: List<JArr>, achievements: List<JArr> = emptyList()): String = (events to achievements).toString()

    @Test
    fun `counted actions give the reference's events`() {
        assertEquals(expected(listOf(jarr("fortify_hero", 3, null))), events("fortify_hero", jobj("material_uids" to jarr(1, 2, 3))))
        assertEquals(expected(listOf(jarr("fortify_hero_items", 270, null))),
            events("fortify_items_hero", jobj("consumed" to jarr(jobj("taken" to 270), jobj()))))
        assertEquals(expected(listOf(jarr("summon_lot3", 11, null), jarr("summon_supreme", 11, null))),
            events("acquire_summon", jobj("lot" to 3, "count" to 11)))
        assertEquals(expected(listOf(jarr("stage_win_2", 2, 20105), jarr("stage_count", 2, 20105), jarr("battle_won", 2, null),
            jarr("defeat_monster", 2, null), jarr("owned_state", 1, null))),
            events("campaign_win", jobj("stage" to 20105, "count" to 2, "near_level" to 1)))
        assertEquals(expected(listOf(jarr("castle_collect_gold", 1, null), jarr("castle_collect_runes", 1, null), jarr("castle_collect", 2, null))),
            events("castle_collect", jobj("types" to jarr(1, 4))))
        assertEquals(expected(listOf(jarr("owned_state", 1, null)), listOf(jarr("hero_evolve", 1, null))), events("evolve_hero", jobj()))
        assertEquals(expected(listOf(jarr("gear_refine", 2, null))),
            events("acquire_refine", jobj("uids" to jarr(5, 6)), listOf(1574 to ByteArray(0))))
        assertEquals(expected(emptyList(), listOf(jarr("achievements", 1, null))), events("vip_buy", jobj(), listOf(578 to ByteArray(0))))
    }

    @Test
    fun `guild task and board row frames`() {
        DeviceClock.active = DeviceClock(null, timeSource = { 0L }, offsetSource = { 0 })
        val tasks = jobj("tasks" to jarr(jarr(1001, 2, 5, 1), jarr(1002, 0, 0, 0)), "star" to 3, "refreshes" to 1)
        assertEquals("02e9030000020000000500000001ea030000000000000000000000030100404b0100",
            Guild.tasksPayload(tasks, 1_789_000_000).toHexString())
        val board = jobj("rows" to JArr(), "used" to 2, "limit" to 25, "auto_id" to 700103, "auto_until" to 1_789_000_100, "free" to 9)
        assertEquals("c5ae0a000204000000030200000019000000c7ae0a006400000009000000",
            Quests.rowPayload(board, jarr(700101, 2, 4, 3), 1_789_000_000).toHexString())
    }
}
