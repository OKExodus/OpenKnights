package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JBool
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.jobj
import java.math.BigInteger

/** Set a VIP level and its exact cumulative threshold without inventing a purchase or granting Diamonds. */
object AdminVip {
    fun plan(args: List<String>, owned: Owned, inputs: DailyInputs, now: Long): Plan {
        require(args.size == 1 && args[0].isNotEmpty() && args[0].all { it in '0'..'9' }) { "/setvip Level" }
        val level = args[0].toLongOrNull() ?: throw Acquisition.Rejected("VIP level is out of range.")
        val levels = inputs.vipLevels()
        require(level == 0L || level in levels) { "VIP level must be between 0 and ${levels.keys.max()}." }
        // viplv EXP values are thresholds crossed, not necessarily keyed by the level they award.
        val thresholds = levels.values.map { it.int("exp_103") }.filter { it > BigInteger.ZERO }.sorted()
        val points = if (level == 0L) BigInteger.ZERO else thresholds.getOrNull((level - 1).toInt())
            ?: throw Acquisition.Rejected("VIP threshold data is unavailable.")
        require(Recharge.vipLevelFor(points, inputs) == level) { "VIP threshold data is inconsistent." }
        val before = owned.roleBits(Recharge.VIP_LEVEL).longValueExact()
        val frames = arrayListOf(owned.roleAdd(Recharge.VIP_LEVEL, BigInteger.valueOf(level) - owned.roleBits(Recharge.VIP_LEVEL)),
            owned.roleAdd(Recharge.VIP_EXP, points - owned.roleBits(Recharge.VIP_EXP)))
        frames.addAll(ActivityProgress.setVipLevel(owned.state, level, now))
        val current = owned.current
        val today = Shops.dayOf(now)
        val doc = (current.document("vip_state") as? JObj)?.deepCopy() ?: jobj("profile" to Claims.VIP_PROFILE)
        val block = owned.state.obj("subsystems").obj("vip").arr("wire_values")
        val sameDay = doc["buy_day"] == JStr(today)
        // Preserve today's use through a downgrade even if it exceeds the new daily allowance.
        for ((field, index) in listOf("admin_ap_bought" to 1, "admin_energy_bought" to 3)) {
            val bought = if (sameDay) maxOf(block[index + 1].long - block[index].long, doc.longOrNull(field) ?: 0) else 0
            doc[field] = JInt(bought)
        }
        doc["buy_day"] = JStr(today)
        val after = Claims.vipBlockFor(doc, level, inputs, today, block.toList())
        block.clear(); after.forEach { block.add(JInt(it)) }
        frames.addAll(Claims.buyCountFrames(block, inputs))
        frames.addAll(SweepFeatures.tmpVipEndFrames(current, before, level))
        val plan = Plan(jobj("admin_reply" to "VIP set to $level with $points total VIP points.", "vip_state_after" to doc), frames)
        // A positive admin VIP grant satisfies the refill qualification but never resets the quest row or its counters.
        if (level > 0) {
            val ledger = (current.document("recharge_ledger") as? JObj)?.deepCopy()
                ?: jobj("profile" to Recharge.LEDGER_PROFILE, "diamonds_total" to 0, "transactions" to 0, "per_pack" to JObj())
            ledger["admin_vip_qualification"] = JBool(true)
            plan.data["recharge_ledger_after"] = ledger
        }
        val (quest, questFrames) = VipQuest.afterTransaction(owned, current, plan.data, "admin_command", inputs)
        if (quest != null) plan.data["vip_quest_after"] = quest
        plan.packets = plan.packets + questFrames
        return plan
    }
}
