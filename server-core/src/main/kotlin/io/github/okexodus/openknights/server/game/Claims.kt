package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.hexBytes
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.protocol.BattleReport
import io.github.okexodus.openknights.protocol.PlayerSections
import io.github.okexodus.openknights.protocol.WireReader
import io.github.okexodus.openknights.protocol.WireWriter
import io.github.okexodus.openknights.server.store.StateStore

/**
 * Claims (`claims.py`): event-row rewards (C1121), the VIP daily reward (C1025), the VIP AP / Energy buys (C1027 /
 * C1029) and the monthly cards (S1760 type 10 / C1669); the S18 VIP block (daily claim flag, AP / Energy buys left and
 * their maxima) and its login reset, the S1026 / S1028 buy counts with the next cost. Daily claims turn at the device's
 * local midnight; the served game clock only judges event windows (C1121).
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
    const val C_ACTIVITY_CLAIM = 1121
    const val C_VIP_DAILY = 1025
    const val C_MONTH_CARD = 1669
    const val S_ACTIVITY_CLAIM = 1186
    const val S_ACTIVITY_UPDATE = 1188
    const val S_VIP_DAILY = 1024
    const val S_EVENT_REWARD = 1762
    const val ERROR_EVENT_REWARD = 30000        // "The Daily Event Reward is invalid"
    const val ERROR_VIP_CLAIMED = 22000         // "You've already claimed today's VIP Gift Pack"
    const val ERROR_VIP_LEVEL = 22004           // "Insufficient VIP level"
    const val ERROR_AP_LIMIT = 22002            // "You've exceeded the purchase limit"
    const val ERROR_ENERGY_LIMIT = 22003        // "You've exceeded the purchase limit"
    const val ERROR_RESOURCES = 4000            // "Not enough Resources"
    const val MAX_ENERGY = 24L
    const val ERROR_CARD_INACTIVE = 53000       // "Not activated yet. Cannot claim."
    const val ERROR_CARD_CLAIMED = 53001        // "You've claim the bonus today."
    const val CARD_DAYS = 30L
    /** Reward pairs name bag items; the currencies among them go to role properties (reward key, role field). */
    val PAIR_CURRENCY: Map<Long, Pair<String?, Long>> = linkedMapOf(20001L to ("gold" to Acquisition.GOLD), 20002L to (null to 7L),
        20003L to ("diamond" to Acquisition.DIAMOND), 20004L to (null to 11L), 20005L to (null to 12L),
        20009L to ("stamina" to Acquisition.STAMINA), 20010L to ("energy" to Acquisition.ENERGY))

    private fun findActivity(state: JObj, activityId: JValue): JObj? =
        state.obj("subsystems").obj("game_activities").obj("first_list").arr("entries").map { it.asObj }
            .firstOrNull { PyDocs.at(it, "wire_u32_1") == activityId }

    /** Items (S68 / S64 per template) first, then one S128 with every changed currency (as the live S128 of C1025). */
    fun grantPairs(owned: Owned, pairs: List<List<JValue>>, reward: JObj): List<Frame> {
        val frames = ArrayList<Frame>()
        val roles = java.util.TreeSet<Long>()
        for (pair in pairs) {
            if (pair.size != 2) throw PyValues.ValueError(if (pair.size > 2) "too many values to unpack (expected 2)"
                else "not enough values to unpack (expected 2, got ${pair.size})")
            val template = PyDocs.int(pair[0])
            val quantity = PyDocs.int(pair[1])
            val currency = if (template.bitLength() < 63) PAIR_CURRENCY[template.toLong()] else null
            if (currency != null) {
                val (key, field) = currency
                owned.roleAdd(field, quantity)
                roles.add(field)
                if (key != null) reward[key] = JInt(PyDocs.int(reward[key] ?: JInt(0)) + quantity)
            } else {
                frames.add(owned.grantItem(template.longValueExact(), quantity.longValueExact()))
                reward.arr("items").add(jarr(pair[0], pair[1]))
            }
        }
        if (roles.isNotEmpty()) {
            frames.add(Acquisition.S_ROLE to Acquisition.roleUpdatePayload(roles.map { f -> Triple(f, owned.role(f).long("tag"), owned.roleBits(f)) }))
        }
        return frames
    }

    // --- C1121 event-row claim ---------------------------------------------------------------------------------------

    fun decodeActivityClaim(payload: ByteArray): JObj {
        if (payload.size != 4) throw Acquisition.Rejected("C1121 is u32 activity id")
        return jobj("activity" to WireReader(payload).u32())
    }

    /**
     * C1121 (live order): grant S68 (+ S128), S1186 (empty string + Reward), S1188 (the activity's rows); before them the
     * S1188 of every other activity whose VIP-level ladder the current VIP level moved.
     */
    fun planActivityClaim(request: JObj, owned: Owned, serverTime: Long?): Plan {
        val activity = findActivity(owned.state, PyDocs.at(request, "activity"))
        if (activity == null || serverTime == null || PyDocs.compare(JInt(serverTime), PyDocs.at(activity, "wire_u32_after_strings")) >= 0) {
            throw Acquisition.Rejected("No such active event", ERROR_EVENT_REWARD)
        }
        // the S1188 of every OTHER activity whose VIP-level ladder moved goes first (the free top-up's order); the claimed
        // activity's own rows go out once, in its final S1188
        val claimedId = PyDocs.long(PyDocs.at(activity, "wire_u32_1"))
        val levelFrames = ActivityProgress.setVipLevel(owned.state, owned.roleBits(VIP_LEVEL), serverTime)
            .filter { (_, data) -> WireReader(data).u32() != claimedId }
        val definition = Events.defined(PyDocs.at(activity, "wire_u32_1"))
        // Local event ladders regenerate their rows from the definition file.
        val claimed = (if (definition != null) Events.claim(definition, activity) else ActivityProgress.claimRow(activity))
            ?: throw Acquisition.Rejected("No claimable row in this event", ERROR_EVENT_REWARD)
        val (index, pairs) = claimed
        val reward = BattleReport.emptyReward()
        val frames = ArrayList(levelFrames)
        frames.addAll(grantPairs(owned, pairs.map { (it as JArr).toList() }, reward))
        frames.add(S_ACTIVITY_CLAIM to (byteArrayOf(0) + BattleReport.encodeReward(reward)))
        frames.add(S_ACTIVITY_UPDATE to PlayerSections.encodeActivityUpdate(PyDocs.long(PyDocs.at(activity, "wire_u32_1")), activity.obj("rows")))
        return Plan(jobj("activity" to request["activity"], "row" to index, "pairs" to pairs, "reward" to reward,
            "evidence_class" to "capture_observed_row_rule"), frames)
    }

    // --- VIP daily reward (C1025) and the AP / Energy buys (C1027 / C1029) --------------------------------------------

    private fun vipBlock(owned: Owned): JArr = owned.state.obj("subsystems").obj("vip").arr("wire_values")

    private fun docOrProfile(document: JValue?): JObj = (if (Py.truthy(document)) document!! else jobj("profile" to VIP_PROFILE)).deepCopy() as JObj

    /**
     * C1027 (AP) / C1029 (Energy), both empty: S578 (Diamonds spent) + S1188 (Diamond-spending ladders), S128 (Diamond,
     * Stamina | Energy), S1026 | S1028 (left − 1, max, next cost). AP + property 98 per buy; Energy + the character's
     * Energy maximum (role 24). Neither is capped.
     */
    fun planVipBuy(kind: String, owned: Owned, inputs: AcquisitionInputs, document: JValue?, now: Long, serverTime: Long?): Plan {
        val today = Shops.dayOf(now)
        val doc = docOrProfile(document)
        val block = vipBlock(owned)
        if (PyDocs.get(doc, "buy_day") != JStr(today)) {         // first buy of the day: the counts start full
            val fresh = vipBlockFor(doc, owned.role(VIP_LEVEL).bits, inputs, today).drop(1).map { JInt(it) }
            val head = block.take(1)
            block.clear()
            block.addAll(head)
            block.addAll(fresh)
            doc["buy_day"] = JStr(today)
        }
        val at = if (kind == "ap") 1 else 3
        val left = PyDocs.long(block[at])
        val maximum = PyDocs.long(block[at + 1])
        if (left <= 0) throw Acquisition.Rejected("No buys left today", if (kind == "ap") ERROR_AP_LIMIT else ERROR_ENERGY_LIMIT)
        val cost = buyCost(kind, maximum - left, inputs)
        if (owned.roleBits(Acquisition.DIAMOND) < java.math.BigInteger.valueOf(cost)) throw Acquisition.Rejected("Not enough Diamonds", ERROR_RESOURCES)
        val frames = ArrayList(Shops.diamondAchievement(owned, cost, serverTime))
        owned.roleAdd(Acquisition.DIAMOND, -cost)
        val field: Long
        val amount: java.math.BigInteger
        if (kind == "ap") {
            field = Acquisition.STAMINA
            val text = inputs.property(98)
            amount = if (text.isNullOrEmpty()) java.math.BigInteger.ZERO else PyValues.parseInt(text)
        } else {
            field = Acquisition.ENERGY
            amount = owned.roleBits(MAX_ENERGY)
        }
        owned.roleAdd(field, amount)
        frames.add(Acquisition.S_ROLE to Acquisition.roleUpdatePayload(listOf(Acquisition.DIAMOND, field).map { f -> Triple(f, owned.role(f).long("tag"), owned.roleBits(f)) }))
        block[at] = JInt(left - 1)
        frames.add(buyCountFrames(block, inputs)[if (kind == "ap") 0 else 1])
        return Plan(jobj("kind" to kind, "cost" to cost, "gained" to amount, "left_after" to left - 1, "day" to today,
            "vip_state_after" to doc, "evidence_class" to "capture_observed_bounded_cost"), frames)
    }

    /** C1025 → S68 / S64 (item col 202), S128 (Gold col 104 + Diamond col 105), S1024 Reward (capture observed). */
    fun planVipDaily(owned: Owned, inputs: AcquisitionInputs, document: JValue?, now: Long): Plan {
        val vip = owned.role(VIP_LEVEL).bits
        val today = Shops.dayOf(now)
        val doc = docOrProfile(document)
        if (vip < 1) throw Acquisition.Rejected("Insufficient VIP level", ERROR_VIP_LEVEL)
        if (PyDocs.get(doc, "claim_day") == JStr(today)) throw Acquisition.Rejected("Today's VIP gift pack was already claimed", ERROR_VIP_CLAIMED)
        val row = inputs.vipDaily(vip) ?: throw Acquisition.Rejected("No viplv row for this VIP level", ERROR_VIP_LEVEL)
        val reward = BattleReport.emptyReward()
        val pairs = ArrayList<List<JValue>>()
        if (row.long("item_202") != 0L) pairs.add(listOf(JInt(row.long("item_202")), JInt(1)))
        pairs.add(listOf(JInt(20001), JInt(row.long("gold_104"))))
        pairs.add(listOf(JInt(20003), JInt(row.long("diamond_105"))))
        val frames = ArrayList(grantPairs(owned, pairs.filter { Py.truthy(it[1]) }, reward))
        frames.add(S_VIP_DAILY to BattleReport.encodeReward(reward))
        doc["claim_day"] = JStr(today)
        val block = vipBlock(owned)
        val desired = vipBlockFor(doc, vip, inputs, today, block.toList())
        block.clear()
        desired.forEach { block.add(JInt(it)) }
        return Plan(jobj("vip_level" to vip, "day" to today, "reward" to reward, "vip_state_after" to doc,
            "evidence_class" to "capture_observed"), frames)
    }
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
            apBought = maxOf(PyDocs.long(block[2]) - PyDocs.long(block[1]), doc.longOrNull("admin_ap_bought") ?: 0L, 0L)
            enBought = maxOf(PyDocs.long(block[4]) - PyDocs.long(block[3]), doc.longOrNull("admin_energy_bought") ?: 0L, 0L)
        }
        return listOf(claimed, maxOf(apMax - apBought, 0L), apMax, maxOf(enMax - enBought, 0L), enMax)
    }

    /** VIP level-up: the new level's maxima, keeping today's buys (edits the S18 block in place). */
    fun raiseMaxima(block: JArr, vipLevel: Long, inputs: AcquisitionInputs, document: JValue? = null, today: String? = null) {
        val row = inputs.vipRow(vipLevel)
        for ((at, maximum) in listOf(1 to row.long("ap_buys_107"), 3 to row.long("energy_buys_108"))) {
            val doc = document as? JObj
            val floor = if (today != null && doc?.get("buy_day") == JStr(today))
                doc.longOrNull(if (at == 1) "admin_ap_bought" else "admin_energy_bought") ?: 0L else 0L
            val bought = maxOf(PyDocs.long(block[at + 1]) - PyDocs.long(block[at]), floor, 0L)
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

    fun decodeCardClaim(payload: ByteArray): JObj {
        if (payload.size != 1) throw Acquisition.Rejected("C1669 is u8 card type")
        return jobj("card" to (payload[0].toInt() and 0xFF))
    }

    /** C1669 → S1762 type 10 + Reward, the grants, S1760 type 10 (structural candidate). */
    fun planCardClaim(request: JObj, owned: Owned, inputs: AcquisitionInputs, document: JValue?, now: Long): Plan {
        val card = PyDocs.long(PyDocs.at(request, "card"))
        val today = Shops.dayOf(now)
        if (card != 3L && card != 4L) throw Acquisition.Rejected("Only the recharge monthly cards are supported", ERROR_CARD_INACTIVE)
        val view = cardView(document, today).getValue(card.toString())
        if (!view.bool("owned")) throw Acquisition.Rejected("Monthly card not activated", ERROR_CARD_INACTIVE)
        if (!view.bool("claimable")) throw Acquisition.Rejected("Today's card reward was already claimed", ERROR_CARD_CLAIMED)
        val triples = inputs.monthCardRewards(card, PyDocs.long(view["days"]) + 1)
        if (triples == null || triples.isEmpty()) throw Acquisition.Rejected("No yueka row for this day", ERROR_CARD_INACTIVE)
        val reward = BattleReport.emptyReward()
        val grants = grantPairs(owned, triples.map { t -> val a = t as JArr; listOf(a[1], a[2]) }, reward)
        val doc = document!!.deepCopy() as JObj
        val entry = PyDocs.at(doc, card.toString()) as JObj
        entry["days"] = JInt(PyDocs.int(PyDocs.at(entry, "days")) + java.math.BigInteger.ONE)
        entry["claim_day"] = JStr(today)
        if (PyDocs.compare(PyDocs.at(entry, "days"), JInt(CARD_DAYS)) >= 0) entry["owned"] = io.github.okexodus.openknights.exact.JBool(false)
        val frames = ArrayList<Frame>()
        frames.add(S_EVENT_REWARD to (byteArrayOf(YKHD.toByte()) + BattleReport.encodeReward(reward)))
        frames.addAll(grants)
        frames.add(S_EVENT_UPDATE to cardStatePayload(doc, today))
        return Plan(jobj("card" to card, "day" to entry["days"], "reward" to reward, "month_cards_after" to doc,
            "evidence_class" to "native_use_structural_candidate"), frames)
    }
}
