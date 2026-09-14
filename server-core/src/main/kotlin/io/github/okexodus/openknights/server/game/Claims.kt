package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.hexBytes
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.protocol.WireWriter
import io.github.okexodus.openknights.server.store.StateStore

/**
 * The login parts of `claims.py`: the S18 VIP block (daily claim flag, AP / Energy buys left and their maxima) and
 * its login reset, the S1026 / S1028 buy counts with the next cost, and the monthly card state (S1760 type 10).
 */
object Claims {
    const val S_VIP_AP = 1026
    const val S_VIP_ENERGY = 1028
    const val S_EVENT_UPDATE = 1760
    const val YKHD = 10
    const val VIP_LEVEL = 27L
    const val BUY_COST = 20L
    const val VIP_PROFILE = "vip_state_v1"
    const val CARD_PROFILE = "month_cards_v1"
    const val ERROR_CARD_INACTIVE = 53000
    const val ERROR_CARD_CLAIMED = 53001
    val YKHD_EVENT_CARDS = "00000000002c01000000b80b0000".hexBytes()
    val YKHD_TAIL = "002c01000000b80b0000".hexBytes()

    /**
     * The S18 `vip` block `[claimed today, AP buys left, AP max, Energy buys left, Energy max]`: maxima = viplv
     * 107 / 108 of the level; buys made today (`buy_day` of the document) stay counted.
     */
    fun vipBlockFor(document: JValue?, vipLevel: Long, inputs: AcquisitionInputs, today: String?, block: List<JValue>? = null): List<Long> {
        val row = inputs.vipRow(vipLevel)
        val doc = if (Py.truthy(document)) document as JObj else JObj()
        val claimed = if (PyDocs.get(doc, "claim_day") == (today?.let { JStr(it) })) 1L else 0L
        val apMax = row.long("ap_buys_107")
        val enMax = row.long("energy_buys_108")
        var apBought = 0L
        var enBought = 0L
        if (block != null && today != null && PyDocs.get(doc, "buy_day") == JStr(today)) {
            apBought = maxOf(PyDocs.long(block[2]) - PyDocs.long(block[1]), 0L)
            enBought = maxOf(PyDocs.long(block[4]) - PyDocs.long(block[3]), 0L)
        }
        return listOf(claimed, maxOf(apMax - apBought, 0L), apMax, maxOf(enMax - enBought, 0L), enMax)
    }

    /** VIP level-up: the new level's maxima, keeping today's buys (edits the S18 block in place). */
    fun raiseMaxima(block: JArr, vipLevel: Long, inputs: AcquisitionInputs) {
        val row = inputs.vipRow(vipLevel)
        for ((at, maximum) in listOf(1 to row.long("ap_buys_107"), 3 to row.long("energy_buys_108"))) {
            val bought = maxOf(PyDocs.long(block[at + 1]) - PyDocs.long(block[at]), 0L)
            block[at] = JInt(maxOf(maximum - bought, 0L))
            block[at + 1] = JInt(maximum)
        }
    }

    /** Diamonds of the next buy: property 97 (20) + bought × property 108 (AP) / 109 (Energy). */
    fun buyCost(kind: String, bought: Long, inputs: AcquisitionInputs? = null): Long {
        if (inputs == null) return BUY_COST + bought * (if (kind == "ap") 5 else 10)
        val stepText = inputs.property(if (kind == "ap") 108 else 109)
        val step = if (stepText.isNullOrEmpty()) 0L else PyValues.parseLong(stepText)
        val baseText = inputs.property(97)
        val base = if (baseText.isNullOrEmpty()) BUY_COST else PyValues.parseLong(baseText)
        return base + bought * step
    }

    /** S1026 / S1028 `u8 left, u8 max, u32 next cost`. */
    fun buyCountFrames(block: List<JValue>, inputs: AcquisitionInputs? = null): List<Frame> = listOf(
        S_VIP_AP to WireWriter().number('B', block[1]).number('B', block[2])
            .number('I', buyCost("ap", maxOf(PyDocs.long(block[2]) - PyDocs.long(block[1]), 0L), inputs)).bytes(),
        S_VIP_ENERGY to WireWriter().number('B', block[3]).number('B', block[4])
            .number('I', buyCost("energy", maxOf(PyDocs.long(block[4]) - PyDocs.long(block[3]), 0L), inputs)).bytes())

    /** The block the S18 must carry now (a new day clears the claim; a new level raises the buy counts), or null. */
    fun vipResetNeeded(current: StateStore.Current, inputs: AcquisitionInputs, now: Long): List<Long>? {
        val state = current.state
        val vip = PyDocs.long(PyDocs.roleStrict(state, VIP_LEVEL))
        val block = state.obj("subsystems").obj("vip").arr("wire_values").toList()
        val desired = vipBlockFor(PyDocs.get(current, "vip_state"), vip, inputs, Shops.dayOf(now), block)
        return if (desired.map { JInt(it) } != block) desired else null
    }

    /** Login-time refresh of the S18 vip block (the planner of `vip_daily_reset`). */
    fun planVipReset(owned: Owned, inputs: AcquisitionInputs, current: StateStore.Current, now: Long): Plan {
        val desired = vipResetNeeded(current, inputs, now)
        val wire = owned.state.obj("subsystems").obj("vip").arr("wire_values")
        val before = JArr(wire.toMutableList())
        if (desired != null) {
            wire.clear()
            desired.forEach { wire.add(JInt(it)) }
        }
        return Plan(jobj("vip_block_before" to before, "vip_block_after" to (desired ?: before), "day" to Shops.dayOf(now),
            "evidence_class" to "preservation_policy_daily_reset"), emptyList())
    }

    /** {card: {owned, days, claimable}} of the recharge monthly cards 3 and 4. */
    fun cardView(document: JValue?, today: String): Map<String, JObj> {
        val cards = LinkedHashMap<String, JObj>()
        val doc = if (Py.truthy(document)) document as JObj else JObj()
        for (card in listOf("3", "4")) {
            val raw = doc[card]
            val entry = if (Py.truthy(raw)) raw as JObj else JObj()
            val owned = Py.truthy(entry["owned"])
            cards[card] = jobj("owned" to owned, "days" to (entry["days"] ?: JInt(0)),
                "claimable" to (owned && PyDocs.get(entry, "claim_day") != JStr(today)))
        }
        return cards
    }

    /** S1760 type 10: the captured event cards, then per recharge card `u8 owned` [+ `u8 days, u8 claimable`], tail. */
    fun cardStatePayload(document: JValue?, today: String): ByteArray {
        val body = ArrayList<Byte>()
        body.add(YKHD.toByte())
        YKHD_EVENT_CARDS.forEach { body.add(it) }
        for ((_, view) in cardView(document, today).toSortedMap()) {
            val bytes = if (Py.truthy(view["owned"])) PyDocs.bytes(listOf(1L, PyDocs.long(view["days"]), if (view.bool("claimable")) 1L else 0L))
                else byteArrayOf(0)
            bytes.forEach { body.add(it) }
        }
        YKHD_TAIL.forEach { body.add(it) }
        return body.toByteArray()
    }

    /** A recharge card bought: the card owned from `today`, 0 days claimed (refused while it is still active). */
    fun activateCard(document: JValue?, cardType: Long, today: String): JObj {
        val doc = (if (Py.truthy(document)) document!! else jobj("profile" to CARD_PROFILE)).deepCopy() as JObj
        val raw = doc[cardType.toString()]
        val entry = if (Py.truthy(raw)) raw as JObj else JObj()
        if (Py.truthy(entry["owned"])) throw Acquisition.Rejected("This monthly card is still active", ERROR_CARD_CLAIMED)
        doc[cardType.toString()] = jobj("owned" to true, "days" to 0, "claim_day" to null, "activated_day" to today)
        return doc
    }
}
