package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.*
import io.github.okexodus.openknights.gamedata.GameTables
import io.github.okexodus.openknights.gamedata.TableSource
import io.github.okexodus.openknights.server.store.StateStore
import io.github.okexodus.openknights.server.store.StoreTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AdminVipTest {
    private val csv = mapOf(
        "viplv.csv" to "101,102,103,107,108\n1,0,100,0,0\n2,1,300,2,2\n3,2,1000,5,5\n",
        "property.csv" to "101,102\n97,20\n108,5\n109,10\n",
        "vipachieve.csv" to "101,108,109,112,113,114\n1,1,0,0,0,0\n2,2,2,4,999,3\n",
    )
    private val inputs = DailyInputs(GameTables(object : TableSource {
        override fun names() = csv.keys.toList()
        override fun raw(name: String) = csv.getValue(name).toByteArray()
    }))
    private val now = 1_800_000_000L

    private fun current(row: Long, level: Long = 0, points: Long = 0, docs: Map<String, JValue?> = emptyMap()): StateStore.Current {
        val state = StoreTest.minimalState()
        state["role_properties"] = jarr(jobj("id" to 27, "value" to jobj("tag" to 5, "bits" to level)),
            jobj("id" to 28, "value" to jobj("tag" to 5, "bits" to points)))
        VipQuest.setTail(state, row, false)
        val quest = jobj("profile" to VipQuest.PROFILE, "row" to row, "items" to jobj("999" to 3), "supreme_tens" to 7)
        return StateStore.Current(1, "", "", state, ByteArray(0), 0, null, JArr(), emptyList(), JArr(), emptyList(),
            null, null, null, null, null, mapOf("vip_quest" to quest) + docs)
    }

    @Test fun `setting VIP uses the crossed threshold and preserves quest progress`() {
        val current = current(2)
        val plan = AdminVip.plan(listOf("2"), Owned(current, inputs), inputs, now)
        assertEquals(300L, PyDocs.long(PyDocs.roleStrict(current.state, 28)))
        assertEquals(2L to 1L, VipQuest.tail(current.state))
        val quest = plan.data.obj("vip_quest_after")
        assertEquals(2L, quest.long("row"))
        assertEquals(3L, quest.obj("items").long("999"))
        assertEquals(7L, quest.long("supreme_tens"))
        assertEquals(0L, plan.data.obj("recharge_ledger_after").long("transactions"))
        assertTrue(plan.packets.any { it.first == VipQuest.S_STATE })
    }

    @Test fun `lowering VIP disables an unmet level quest without rewinding its row`() {
        val current = current(2, 2, 500)
        VipQuest.setTail(current.state, 2, true)
        val plan = AdminVip.plan(listOf("0"), Owned(current, inputs), inputs, now)
        assertEquals(0L, PyDocs.long(PyDocs.roleStrict(current.state, 28)))
        assertEquals(2L to 0L, VipQuest.tail(current.state))
        assertEquals(2L, plan.data.obj("vip_quest_after").long("row"))
    }

    @Test fun `positive VIP qualifies for refill and does not complete collection requirements`() {
        val current = current(1)
        val plan = AdminVip.plan(listOf("1"), Owned(current, inputs), inputs, now)
        assertEquals(1L to 1L, VipQuest.tail(current.state))
        val quest = plan.data.obj("vip_quest_after")
        quest["row"] = JInt(2); quest["items"] = JObj()
        assertFalse(VipQuest.claimable(inputs.vipAchieve(2), VipQuest.View(current.state, plan.data["recharge_ledger_after"]), quest, inputs))
    }

    @Test fun `claims and purchases stay counted through downgrade and later restoration`() {
        val today = Shops.dayOf(now)
        val current = current(2, 2, 500, mapOf("vip_state" to jobj("claim_day" to today, "buy_day" to today)))
        current.state.obj("subsystems").obj("vip")["wire_values"] = jarr(1, 1, 5, 2, 5)
        val plan = AdminVip.plan(listOf("0"), Owned(current, inputs), inputs, now)
        val doc = plan.data.obj("vip_state_after")
        val block = current.state.obj("subsystems").obj("vip").arr("wire_values")
        assertEquals(listOf(1L, 1L, 5L, 2L, 5L), Claims.vipBlockFor(doc, 2, inputs, today, block.toList()))
        Claims.raiseMaxima(block, 2, inputs, doc, today)
        assertEquals(listOf(1L, 1L, 5L, 2L, 5L), block.map { it.long })
    }
}
