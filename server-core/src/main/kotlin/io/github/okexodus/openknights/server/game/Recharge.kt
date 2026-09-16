package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.protocol.BattleReport
import io.github.okexodus.openknights.protocol.WireReader
import java.math.BigInteger

/**
 * The free top-up (`recharge.py`; operator decision 2026-09-11, preservation policy): tapping a recharge pack grants
 * it without payment, with its VIP EXP and its contribution to the recharge events. The patched client posts the pack's
 * GoodsId / ProductId with its session token to the loopback gateway; the server grants on the login's game session and
 * pushes the frames: S1188 recharge ladders, S1088 Reward (Diamonds), S128 Diamond / VipLevel / VipExp, the level-up
 * buy counts, S1248 `00`. Monthly cards activate their 30 daily claims instead. The first top-up ever is doubled.
 */
object Recharge {
    const val S_CHARGED = 1248
    const val S_BUY_REWARD = 1088
    const val VIP_EXP = 28L
    const val VIP_LEVEL = 27L
    const val DIAMOND = 8L
    const val RECHARGE_TYPE = 3
    const val CLIENT_RECHARGE_ID = 100L
    const val DIAMOND_ITEM = 20003L
    const val VIP_EXP_PER_DIAMOND = 10L
    const val FIRST_PURCHASE_MULTIPLIER = 2L
    const val ERROR_NO_PACK = 7001
    const val LEDGER_PROFILE = "recharge_ledger_v1"

    /** True once the character has topped up (a recorded top-up, or VIP EXP from an earlier one). */
    fun hasCharged(state: JObj?, ledger: JValue?): Boolean {
        val doc = if (Py.truthy(ledger)) ledger as JObj else JObj()
        if (PyDocs.compare(doc["transactions"] ?: JInt(0), JInt(0)) > 0) return true
        val props = (if (Py.truthy(state)) state!! else JObj())["role_properties"]
        for (f in (props ?: JArr()) as JArr) {
            val field = f.asObj
            if (field["id"] == JInt(VIP_EXP)) {
                val value = field.obj("value")
                val bits = value["bits"] ?: JInt(0)
                return PyDocs.compare(if (Py.truthy(bits)) bits else JInt(0), JInt(0)) > 0
            }
        }
        return false
    }

    /** The recharge packs of the catalog: shop type 3 records of the client's recharge channel. */
    fun packs(catalog: JObj?): List<JObj> {
        val shop = ((catalog?.get("shops") as? JObj)?.get(RECHARGE_TYPE.toString())) as? JObj ?: return emptyList()
        return shop.arr("records").map { it.asObj }.filter { it["channel"] == JInt(CLIENT_RECHARGE_ID) }
    }

    /** VIP level = the number of viplv rows whose EXP (col 103) is met, at most the top level. */
    fun vipLevelFor(exp: BigInteger, inputs: AcquisitionInputs): Long {
        val levels = inputs.vipLevels()
        val top = levels.keys.max()
        val met = levels.values.count { Py.truthy(it["exp_103"]) && PyDocs.int(it["exp_103"]) <= exp }.toLong()
        return minOf(top, met)
    }

    /** `decode_request(body)`: exactly {token, goods_id, product_id}; goods_id a u32 > 0, product_id text of ≤ 128. */
    fun decodeRequest(body: JValue): JObj {
        if (body !is JObj || body.keys != setOf("token", "goods_id", "product_id")) {
            throw Acquisition.Rejected("Recharge request is token, goods_id, product_id")
        }
        val goods = body["goods_id"] as? JInt
        val product = body["product_id"] as? JStr
        if (goods == null || goods.value <= BigInteger.ZERO || goods.value >= BigInteger.ONE.shiftLeft(32) || product == null ||
            product.value.codePointCount(0, product.value.length) > 128) {
            throw Acquisition.Rejected("Recharge goods_id / product_id malformed")
        }
        return jobj("goods_id" to goods, "product_id" to product)
    }

    private fun ledgerCopy(ledger: JValue?): JObj =
        (if (Py.truthy(ledger)) ledger!! else jobj("profile" to LEDGER_PROFILE, "diamonds_total" to 0, "transactions" to 0,
            "per_pack" to JObj())).deepCopy() as JObj

    private fun countPack(ledger: JObj, packId: JValue, now: Long) {
        ledger["transactions"] = JInt(PyDocs.int(ledger["transactions"]) + BigInteger.ONE)
        val perPack = ledger.obj("per_pack")
        val key = PyDocs.str(packId)
        perPack[key] = JInt(PyDocs.int(perPack[key] ?: JInt(0)) + BigInteger.ONE)
        ledger["last_at_epoch"] = JInt(now)
    }

    /**
     * `plan_card_purchase`: a monthly card (f08 = 3, item 3 / 4) activates its 30 daily claims and pushes the S1760 type-10
     * card state and S1248; no up-front grant or VIP EXP.
     */
    fun planCardPurchase(record: JObj, owned: Owned, cards: JValue?, ledger: JValue?, serverTime: Long, now: Long): Plan {
        val today = Shops.dayOf(now)
        val cardsAfter = Claims.activateCard(cards, record.long("item"), today)
        val book = ledgerCopy(ledger)
        countPack(book, record["id"]!!, now)
        val packets = listOf(Claims.S_EVENT_UPDATE to Claims.cardStatePayload(cardsAfter, today), S_CHARGED to byteArrayOf(0))
        return Plan(jobj("goods_id" to record["id"], "product_id" to record["f90"], "price_cents" to record["price"], "diamonds" to 0,
            "card" to record["item"], "vip_level_before" to owned.role(VIP_LEVEL)["bits"],
            "vip_level_after" to owned.role(VIP_LEVEL)["bits"], "activities_updated" to JArr(),
            "month_cards_after" to cardsAfter, "recharge_ledger_after" to book, "reward" to BattleReport.emptyReward(),
            "now_epoch" to now, "served_time" to serverTime, "evidence_class" to "preservation_policy_free_top_up_card"), packets)
    }

    /** `plan_recharge(request, owned, inputs, catalog, ledger, server_time, now, cards)`. */
    fun planRecharge(request: JObj, owned: Owned, inputs: AcquisitionInputs, catalog: JObj?, ledger: JValue?, serverTime: Long,
                     now: Long, cards: JValue? = null): Plan {
        val record = packs(catalog).firstOrNull { it["id"] == request["goods_id"] }
        if (record == null || !Py.truthy(record["on_sale"])) throw Acquisition.Rejected("Unknown recharge pack", ERROR_NO_PACK)
        val product = request.str("product_id")
        if (product.isNotEmpty() && JStr(product) != record["f90"]) throw Acquisition.Rejected("Recharge product id differs from the pack")
        if (record["f08"] == JInt(3) && (record["item"] == JInt(3) || record["item"] == JInt(4))) {
            return planCardPurchase(record, owned, cards, ledger, serverTime, now)
        }
        if (record["f08"] != JInt(2) || record["item"] != JInt(DIAMOND_ITEM) || record.long("count") <= 0) {
            throw Acquisition.Rejected("This recharge record is outside the supported profile", Acquisition.ERROR_WRONG_TYPE)
        }
        val diamonds = record.long("count")
        val first = !hasCharged(owned.state, ledger)
        val granted = if (first) diamonds * FIRST_PURCHASE_MULTIPLIER else diamonds
        val book = ledgerCopy(ledger)
        val activityFrames = ArrayList<Frame>(ActivityProgress.advance(owned.state, "diamond_recharge", diamonds, serverTime) +
            ActivityProgress.countTransaction(owned.state, record.long("price"), serverTime))
        owned.roleAdd(DIAMOND, granted)
        val expBefore = owned.roleBits(VIP_EXP)
        owned.roleAdd(VIP_EXP, diamonds * VIP_EXP_PER_DIAMOND)
        val expAfter = owned.roleBits(VIP_EXP)
        val levelBefore = owned.roleBits(VIP_LEVEL)
        val levelAfter = levelBefore.max(BigInteger.valueOf(vipLevelFor(expAfter, inputs)))
        fun tag(field: Long) = PyDocs.long(owned.role(field)["tag"])
        var fields = listOf(Triple(DIAMOND, tag(DIAMOND), owned.roleBits(DIAMOND)), Triple(VIP_LEVEL, tag(VIP_LEVEL), levelAfter),
            Triple(VIP_EXP, tag(VIP_EXP), expAfter))
        if (levelAfter != levelBefore) owned.roleAdd(VIP_LEVEL, levelAfter - levelBefore)
        else fields = fields.filter { it.first != VIP_LEVEL }
        val reward = BattleReport.emptyReward()
        reward["diamond"] = JInt(granted)
        if (first) {
            reward["double_charge_raw"] = JInt(1)
            book["first_purchase"] = jobj("goods_id" to record["id"], "bonus_diamonds" to granted - diamonds, "at_epoch" to now)
        }
        book["diamonds_total"] = JInt(PyDocs.int(book["diamonds_total"]) + BigInteger.valueOf(granted))
        countPack(book, record["id"]!!, now)
        val levelFrames = ArrayList<Frame>()
        if (levelAfter != levelBefore) {
            // A new VIP level: the VIP Rewards ladder counter and the AP / Energy buy counts follow it.
            activityFrames += ActivityProgress.setVipLevel(owned.state, levelAfter, serverTime)
            val block = owned.state.obj("subsystems").obj("vip").arr("wire_values")
            Claims.raiseMaxima(block, levelAfter.toLong(), inputs, owned.current.document("vip_state"), Shops.dayOf(now))
            levelFrames += Claims.buyCountFrames(block, inputs)
            // reaching VIP 4 by purchases ends the temporary VIP4 at once
            levelFrames += SweepFeatures.tmpVipEndFrames(owned.current, levelBefore.toLong(), levelAfter.toLong())
        }
        val packets = activityFrames + listOf(S_BUY_REWARD to BattleReport.encodeReward(reward),
            Acquisition.S_ROLE to Acquisition.roleUpdatePayload(fields)) + levelFrames + listOf(S_CHARGED to byteArrayOf(0))
        return Plan(jobj("goods_id" to record["id"], "product_id" to record["f90"], "price_cents" to record["price"],
            "diamonds" to granted, "pack_diamonds" to diamonds, "first_purchase_double" to first,
            "vip_exp_before" to expBefore, "vip_exp_after" to expAfter, "vip_level_before" to levelBefore, "vip_level_after" to levelAfter,
            "activities_updated" to JArr(activityFrames.mapTo(ArrayList()) { JInt(WireReader(it.second).u32()) }),
            "recharge_ledger_after" to book, "reward" to reward, "now_epoch" to now, "served_time" to serverTime,
            "evidence_class" to "preservation_policy_free_top_up"), packets)
    }
}
