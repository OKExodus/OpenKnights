package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JBool
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JNull
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.asArr
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.protocol.WireWriter
import io.github.okexodus.openknights.server.store.StateStore

/**
 * The VIP gift-pack quest ("Refill Reward", `vip_quest.py`, vipachieve.csv): the current row id and its "claimable"
 * byte live in the `game_activities` tail of S18 / S1184 (`update_u32`, `update_conditional_u8`); every transaction
 * re-evaluates the row (labeled policy counters) and pushes S1196 when the tail changes.
 */
object VipQuest {
    const val C_CLAIM = 1125
    const val S_CLAIM_REWARD = 1192
    const val S_STATE = 1196
    const val ERROR_NOT_CLAIMABLE = 30000
    const val PROFILE = "vip_achieve_v1"
    const val VIP_LEVEL = 27L

    fun statePayload(rowId: Long, claimable: Boolean): ByteArray {
        val w = WireWriter().u32(rowId)
        if (rowId != 0L) w.u8(if (claimable) 1 else 0)
        return w.bytes()
    }

    fun seedDocument(state: JObj): JObj {
        val activities = state.obj("subsystems").obj("game_activities")
        return jobj("profile" to PROFILE, "row" to (activities.longOrNull("update_u32") ?: 0L), "items" to JObj(), "supreme_tens" to 0,
            "seeded_from" to "game_activities.update_u32")
    }

    /** The view `claimable` reads: the state, the recharge ledger. */
    class View(val state: JObj, val rechargeLedger: JValue?)

    fun claimable(row: JObj?, view: View, document: JObj, inputs: AcquisitionInputs): Boolean {
        if (row == null) return false
        val vip = view.state.arr("role_properties").map { it.asObj }.firstOrNull { it.long("id") == VIP_LEVEL }?.obj("value")?.bits ?: 0L
        val kind1 = row.long("mission1_kind")
        val kind2 = row.long("mission2_kind")
        val ok1 = when (kind1) {
            // `bool((recharge_ledger or {}).get("transactions"))`: the ledger keeps a count
            1L -> Py.truthy((view.rechargeLedger.takeIf { Py.truthy(it) } as? JObj)?.get("transactions"))
            2L -> vip >= row.long("mission1_value")
            else -> kind1 == 0L
        }
        val ok2 = when (kind2) {
            0L -> true
            4L -> (document.obj("items")[row.long("mission2_a").toString()]?.let { (it as JInt).value.toLong() } ?: 0L) >= row.long("mission2_b")
            6L -> document.long("supreme_tens") >= row.long("mission2_a")
            3L -> view.state.arr("heroes").any { h ->
                val template = Acquisition.heroValues(h.asArr)[1L]!!.long
                (inputs.heroStar(template) ?: 0L) >= row.long("mission2_a") && Math.floorMod(template, 100L) >= row.long("mission2_b")
            }
            else -> false
        }
        return ok1 && ok2
    }

    /** (row id, claimable byte or null) of the S18 tail. */
    fun tail(state: JObj): Pair<Long, Long?> {
        val activities = state.obj("subsystems").obj("game_activities")
        return (activities.longOrNull("update_u32") ?: 0L) to activities.longOrNull("update_conditional_u8")
    }

    fun setTail(state: JObj, rowId: Long, isClaimable: Boolean) {
        val activities = state.obj("subsystems").obj("game_activities")
        activities["update_u32"] = JInt(rowId)
        activities["update_conditional_u8"] = if (rowId != 0L) JInt(if (isClaimable) 1 else 0) else JNull
    }

    /** Count what the transaction granted / did for the current row, then re-evaluate: (document or null, frames). */
    fun afterTransaction(owned: Owned, current: StateStore.Current, plan: JObj, action: String, inputs: AcquisitionInputs): Pair<JObj?, List<Frame>> {
        val document = ((current.document("vip_quest") as? JObj) ?: seedDocument(current.state)).deepCopy()
        val rowId = document.long("row")
        val row = if (rowId != 0L) inputs.vipAchieve(rowId) else null
        if (row == null) return null to emptyList()
        var changed = false
        if (row.long("mission2_kind") == 4L) {
            val gained = owned.granted[row.long("mission2_a")] ?: 0L
            if (gained != 0L) {
                val key = row.long("mission2_a").toString()
                val items = document.obj("items")
                items[key] = JInt((items[key]?.let { (it as JInt).value.toLong() } ?: 0L) + gained)
                changed = true
            }
        }
        if (row.long("mission2_kind") == 6L && action == "acquire_summon" && plan.longOrNull("lot") == 3L && plan.longOrNull("mode") == 1L) {
            document["supreme_tens"] = JInt(document.long("supreme_tens") + 1)
            changed = true
        }
        val ledger = plan["recharge_ledger_after"]?.takeIf { it != JNull && !(it is JObj && it.isEmpty()) } ?: current.document("recharge_ledger")
        val view = View(owned.state, ledger)
        val nowClaimable = claimable(row, view, document, inputs)
        val frames = ArrayList<Frame>()
        val (tailRow, tailByte) = tail(owned.state)
        if (rowId != tailRow || nowClaimable != ((tailByte ?: 0L) != 0L)) {
            setTail(owned.state, rowId, nowClaimable)
            frames.add(S_STATE to statePayload(rowId, nowClaimable))
            changed = true
        }
        return (if (changed) document else null) to frames
    }

    /** At login: does the stored tail differ from the evaluated state? */
    fun tailNeedsUpdate(current: StateStore.Current, inputs: AcquisitionInputs): Boolean {
        val document = (current.document("vip_quest") as? JObj) ?: seedDocument(current.state)
        val rowId = document.long("row")
        val row = if (rowId != 0L) inputs.vipAchieve(rowId) else null ?: return false
        if (row == null) return false
        val (tailRow, tailByte) = tail(current.state)
        return rowId != tailRow || claimable(row, View(current.state, current.document("recharge_ledger")), document, inputs) != ((tailByte ?: 0L) != 0L)
    }

    @Suppress("unused")
    private val unused: JBool? = null
}
