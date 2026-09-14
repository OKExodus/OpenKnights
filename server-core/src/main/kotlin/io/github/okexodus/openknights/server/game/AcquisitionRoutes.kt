package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.server.store.StateStore

/**
 * Request → planner dispatch of the acquisition routes (`acquisition_routes.py`, docs/ACQUISITION_CONTRACT.md). Every
 * plan records `now_epoch` and, when an event window or ladder was judged, the `served_time` it used.
 *
 * Every opcode of [ACTIONS] has its planner here, and the read-only replies (C1057, C89, C1253, the Lucky Shop
 * info query) are answered by [readOnlyReply].
 */
object AcquisitionRoutes {
    val ACTIONS: Map<Int, String> = linkedMapOf(73 to "acquire_item_use", 4099 to "acquire_choose_box", 803 to "acquire_merge",
        801 to "acquire_merge", 321 to "acquire_summon", 1251 to "acquire_decompose", 75 to "acquire_shop_buy",
        2725 to "acquire_lucky_exchange", 2723 to "acquire_lucky_exchange", 641 to "acquire_roulette",
        83 to "acquire_sell", 85 to "acquire_buyback", 2051 to "acquire_refine", 2633 to "acquire_refine",
        3137 to "acquire_refine", 1249 to "acquire_fuse",
        // Claims (docs/CLAIMS_CONTRACT.md): event rows, VIP daily reward, monthly cards.
        1121 to "claim_activity_row", 1025 to "claim_vip_daily", 1669 to "claim_month_card", 1125 to "claim_vip_quest",
        1027 to "vip_buy", 1029 to "vip_buy",
        // Rebirth Evolve / Fortify (docs/REBIRTH_CONTRACT.md).
        101 to "rebirth_evolve", 99 to "rebirth_fortify",
        // Built from the client code (never captured): gear / jewelry Combine, buy-back Delete, Reborn; they reuse the
        // audited action of their sibling, the plan's "operation" and the history row's opcode tell them apart.
        2055 to "acquire_fuse", 2631 to "acquire_fuse", 87 to "acquire_buyback", 95 to "rebirth_evolve")
    val OPCODES: List<Int> = ACTIONS.keys.toList() + listOf(1057, 89, 1253)
    val CATALOG_OPCODES = listOf(1057, 75, 2725, 2723, 641)

    /** `assigned_hero_uids(current, deployment_policy)`: (deployed: lineup + secondary team, excluded: exploration / mining). */
    fun assignedHeroUids(current: StateStore.Current, deploymentPolicy: FreshProfile.DeploymentPolicy): Pair<Set<Long>, Set<Long>> {
        val deployed = LinkedHashSet<Long>()
        for (e in current.state.arr("formation")) {
            val uid = e.asObj["hero_uid"]
            if (Py.truthy(uid)) deployed.add(uid!!.long)
        }
        val secondary = current.secondaryTeam
        if (secondary != null && Py.truthy(secondary)) {
            for (e in secondary.obj("document").arr("references")) deployed.add(e.asObj.long("hero_uid"))
        }
        val excluded = LinkedHashSet<Long>(deploymentPolicy.explorationUids).also { it.addAll(deploymentPolicy.miningUids) }
        return deployed to excluded
    }

    /** C2725 as LuckyStoresSystem::sendMessage sends it: `u8 store type 2, u32 mode`; mode 0 is the info query. */
    val LUCKY_INFO_QUERY = byteArrayOf(2, 0, 0, 0, 0)

    /**
     * `is_read_only(opcode, payload)`: the queries that change nothing (buy-back list, fuse luck, shop list, Lucky info).
     * C2725 is a query only in its exact mode-0 form; every other C2725 takes the committed path and its validation.
     */
    fun isReadOnly(opcode: Int, payload: ByteArray): Boolean =
        opcode in setOf(1057, 89, 1253) || (opcode == 2725 && payload.contentEquals(LUCKY_INFO_QUERY))

    /** The fuse luck query C1253 (`read_only_reply`, compose). */
    fun fuseLuckReply(payload: ByteArray, current: StateStore.Current): Pair<List<Frame>, JObj> {
        if (payload.isNotEmpty()) throw Acquisition.Rejected("C1253 has no payload")
        val stored = current.document("fuse_luck_state")
        val document = if (Py.truthy(stored)) stored!!.asObj else Compose.initialLuckDocument(current)
        return listOf(Compose.S_LUCK to Compose.luckPayload(document.obj("luck"))) to io.github.okexodus.openknights.exact.jobj("luck" to document["luck"])
    }

    /**
     * `read_only_reply(opcode, payload, current, inputs, catalog, rng_policy, now)`: the replies of the queries that change
     * nothing — buy-back list C89, fuse luck C1253, shop list C1057, Lucky Shop info C2725 — as (packets, log fields).
     */
    @Suppress("UNUSED_PARAMETER")
    fun readOnlyReply(opcode: Int, payload: ByteArray, current: StateStore.Current, inputs: DailyInputs, catalog: JObj?, rngPolicy: JObj?,
                      now: Long): Pair<List<Frame>, JObj> {
        if (opcode == 1253) return fuseLuckReply(payload, current)
        if (opcode == 89) {
            if (payload.isNotEmpty()) throw Acquisition.Rejected("C89 has no payload")
            return Warehouse.planList(current.document("buyback_state")).packets to JObj()
        }
        if (opcode == 1057) {
            val request = Shops.decodeListRequest(payload)
            val plan = Shops.planList(request, catalog!!, current.document("shop_state"), now)
            return plan.packets to io.github.okexodus.openknights.exact.jobj("shop_type" to request["type"], "records" to plan["records"], "served" to plan["served"])
        }
        val request = Shops.decodeLuckyRequest(payload)
        val plan = Shops.planLuckyInfo(request, Owned(current, inputs), inputs, catalog!!, current.document("lucky_state"), now,
            poolPolicy = Acquisition.policyAllows(rngPolicy, "lucky_refresh"))
        return plan.packets to io.github.okexodus.openknights.exact.jobj("remaining" to plan["remaining"])
    }

    /** `served(current)` of `planner_for`: the served clock, recorded as the plan's `served_time`. */
    private fun served(used: JObj, servedTime: (StateStore.Current) -> Long, current: StateStore.Current): Long {
        val value = servedTime(current)
        used["served_time"] = io.github.okexodus.openknights.exact.JInt(value)
        return value
    }

    /** One committed acquisition request's (action, request, planner) — the reference's `planner_for` result. */
    class Routed(val action: String, val request: io.github.okexodus.openknights.exact.JValue, val planner: (Owned, StateStore.Current) -> Plan)

    /**
     * `planner_for(opcode, payload, inputs, catalog, rng_policy, deployment_policy, now, served_time)`: the action, the
     * decoded request and the planner of one committed acquisition request; the planner records `now_epoch` (and the
     * `served_time` it used).
     */
    @Suppress("UNUSED_PARAMETER")   // rng_policy / served_time are read by the other opcodes' planners
    fun plannerFor(opcode: Int, payload: ByteArray, inputs: DailyInputs, catalog: JObj?, rngPolicy: JObj?,
                   deploymentPolicy: FreshProfile.DeploymentPolicy, now: Long, servedTime: (StateStore.Current) -> Long): Routed {
        if (opcode in CATALOG_OPCODES && catalog == null) throw Acquisition.Rejected("Shops need the captured server catalog (--acquisition-catalog)")
        val used = JObj()
        val request: io.github.okexodus.openknights.exact.JValue
        val planner: (Owned, StateStore.Current) -> Plan
        when (opcode) {
            // item use, choose box, merge, summons, hero refine, compose / refine / fuse (acquisition, summon, compose)
            73 -> {
                val decoded = Acquisition.decodeUseRequest(payload)
                request = decoded
                planner = { owned, current ->
                    val props = LinkedHashMap<Long, io.github.okexodus.openknights.exact.JValue?>()
                    for (f in owned.state.arr("role_properties")) props[f.asObj.long("id")] = f.asObj.obj("value")["bits"]
                    // item use field 204 reads GetTmpVipLevel
                    val vipBits = props[Acquisition.VIP_LEVEL]
                    val vip = SweepFeatures.tmpVipLevel(current, if (Py.truthy(vipBits)) vipBits!!.long else 0L, now)
                    Acquisition.planUse(decoded, owned, inputs, boxPolicy = rngPolicy,
                        seed = Acquisition.seedFor(payload, current.revision, "box"),
                        roleLevel = (props[Acquisition.ROLE_LEVEL] as? io.github.okexodus.openknights.exact.JInt)?.value?.longValueExact(),
                        vipLevel = vip, now = now)
                }
            }
            4099 -> {
                val decoded = Acquisition.decodeChooseRequest(payload)
                request = decoded
                planner = { owned, _ -> Acquisition.planChoose(decoded, owned, inputs) }
            }
            321 -> {
                val decoded = Summon.decodeSummonRequest(payload)
                request = decoded
                if (rngPolicy == null) throw Acquisition.Rejected("Summons need the labeled local acquisition policy", Acquisition.ERROR_WRONG_TYPE)
                planner = { owned, current ->
                    val stored = current.document(StateStore.SUMMON_STATE)
                    val document = if (Py.truthy(stored)) stored!!.asObj else Summon.initialDocument(current, now)
                    val plan = Summon.planSummon(decoded, owned, inputs, document = document, now = now,
                        seed = Acquisition.seedFor(payload, current.revision, "summon:$now"))
                    val after = plan.data.remove("document_after")
                    plan["summon_state_after"] = after
                    plan
                }
            }
            1251 -> {
                val decoded = Summon.decodeRefineRequest(payload)
                request = decoded
                planner = { owned, current ->
                    val (deployed, excluded) = assignedHeroUids(current, deploymentPolicy)
                    Summon.planRefine(decoded, owned, inputs, excludedUids = excluded, deployedUids = deployed)
                }
            }
            1249 -> {
                val decoded = Compose.decodeFuseRequest(payload)
                request = decoded
                if (!Acquisition.policyAllows(rngPolicy, "fuse_roll")) {
                    throw Acquisition.Rejected("Hero fusion needs the labeled fuse_roll policy", Acquisition.ERROR_WRONG_TYPE)
                }
                planner = { owned, current ->
                    val (deployed, excluded) = assignedHeroUids(current, deploymentPolicy)
                    val stored = current.document("fuse_luck_state")
                    val document = if (Py.truthy(stored)) stored!!.asObj else Compose.initialLuckDocument(current)
                    val plan = Compose.planFuse(decoded, owned, inputs, luck = document.obj("luck"),
                        seed = Acquisition.seedFor(payload, current.revision, "fuse"), deployedUids = deployed, excludedUids = excluded)
                    plan["fuse_luck_state_after"] = JObj(LinkedHashMap(document.map)).also { it["luck"] = plan["luck_after"]!! }
                    plan
                }
            }
            2051 -> {
                val uids = Compose.decodeUidList(payload, "C2051")
                request = io.github.okexodus.openknights.exact.jobj("uids" to uids)
                planner = { owned, _ -> Compose.planGearRefine(uids, owned, inputs) }
            }
            2633 -> {
                val uids = Compose.decodeUidList(payload, "C2633")
                request = io.github.okexodus.openknights.exact.jobj("uids" to uids)
                planner = { owned, current ->
                    val entries = current.jewelEntriesView ?: throw Acquisition.Rejected("No jewelry list to refine from")
                    Compose.planJewelRefine(uids, owned, inputs, entries)
                }
            }
            3137 -> {
                val decoded = Compose.decodeItemRefineRequest(payload)
                request = decoded
                planner = { owned, _ -> Compose.planItemRefine(decoded.arr("entries"), owned, inputs) }
            }
            803, 801 -> {
                val decoded = if (opcode == 803) Acquisition.decodeMergeRequest(payload) else Acquisition.decodeSingleMergeRequest(payload)
                request = decoded
                planner = { owned, _ -> Acquisition.planMerge(decoded, owned, inputs) }
            }
            2055 -> {
                val decoded = Compose.decodeComposeRequest(payload, "C2055")
                request = decoded
                planner = { owned, _ -> Compose.planGearCompose(decoded, owned, inputs) }
            }
            2631 -> {
                val decoded = Compose.decodeComposeRequest(payload, "C2631")
                request = decoded
                planner = { owned, current ->
                    val entries = current.jewelEntriesView ?: throw Acquisition.Rejected("No jewelry list to combine from", Compose.ERROR_JEWEL_TARGET)
                    Compose.planJewelCompose(decoded, owned, inputs, entries)
                }
            }
            101, 99 -> {
                if (opcode == 101) {
                    request = Rebirth.decodeEvolveRequest(payload)
                    planner = { owned, _ -> Rebirth.planEvolve(request, owned, inputs) }
                } else {
                    request = Rebirth.decodeFortifyRequest(payload)
                    planner = { owned, _ -> Rebirth.planFortify(request, owned, inputs) }
                }
            }
            95 -> {
                request = Reborn.decodeRequest(payload)
                planner = { owned, current ->
                    val (_, excluded) = assignedHeroUids(current, deploymentPolicy)
                    Reborn.planReborn(request, owned, inputs, excluded)
                }
            }
            75 -> {
                val req = Shops.decodeBuyRequest(payload)
                request = req
                planner = { owned, current ->
                    Shops.planBuy(req, owned, inputs, catalog!!, current.document("shop_state"), now, serverTime = served(used, servedTime, current))
                }
            }
            2725, 2723 -> {
                val req = Shops.decodeLuckyRequest(payload)
                request = req
                planner = { owned, current ->
                    val seed = Acquisition.seedFor(payload, current.revision, "lucky:$now")
                    val serverTime = served(used, servedTime, current)
                    if (opcode == 2725) Shops.planLuckyInfo(req, owned, inputs, catalog!!, current.document("lucky_state"), now, seed = seed,
                        poolPolicy = Acquisition.policyAllows(rngPolicy, "lucky_refresh"), serverTime = serverTime)
                    else Shops.planLuckyExchange(req, owned, inputs, catalog!!, current.document("lucky_state"), now,
                        poolPolicy = Acquisition.policyAllows(rngPolicy, "lucky_refresh"), seed = seed, serverTime = serverTime)
                }
            }
            641 -> {
                val req = Shops.decodeRouletteRequest(payload)
                request = req
                if (rngPolicy == null) throw Acquisition.Rejected("The roulette needs the labeled local acquisition policy", Acquisition.ERROR_WRONG_TYPE)
                planner = { owned, current ->
                    Shops.planSpin(req, owned, catalog!!, seed = Acquisition.seedFor(payload, current.revision, "roulette:$now"),
                        now = served(used, servedTime, current))
                }
            }
            83, 85 -> {
                val req = Warehouse.decodePair(payload, "C$opcode")
                request = req
                planner = { owned, current ->
                    val plan = if (opcode == 83) Warehouse.planSell(req, owned, inputs, current.document("buyback_state"))
                        else Warehouse.planBuyback(req, owned, inputs, current.document("buyback_state"))
                    plan["buyback_state_after"] = plan.data.remove("buyback_after")
                    plan
                }
            }
            1121 -> {
                val req = Claims.decodeActivityClaim(payload)
                request = req
                planner = { owned, current -> Claims.planActivityClaim(req, owned, serverTime = served(used, servedTime, current)) }
            }
            1025 -> {
                if (payload.isNotEmpty()) throw Acquisition.Rejected("C1025 has no payload")
                request = JObj()
                planner = { owned, current -> Claims.planVipDaily(owned, inputs, current.document("vip_state"), now = now) }
            }
            1027, 1029 -> {
                if (payload.isNotEmpty()) throw Acquisition.Rejected("C$opcode has no payload")
                val kind = if (opcode == 1027) "ap" else "energy"
                request = io.github.okexodus.openknights.exact.jobj("kind" to kind)
                planner = { owned, current ->
                    Claims.planVipBuy(kind, owned, inputs, current.document("vip_state"), now = now, serverTime = served(used, servedTime, current))
                }
            }
            1669 -> {
                val req = Claims.decodeCardClaim(payload)
                request = req
                planner = { owned, current -> Claims.planCardClaim(req, owned, inputs, current.document("month_cards"), now = now) }
            }
            1125 -> {
                val req = VipQuest.decodeClaim(payload)
                request = req
                planner = { owned, current -> VipQuest.planClaim(req, owned, inputs, current) }
            }
            87 -> {
                val req = Warehouse.decodePair(payload, "C87")
                request = req
                planner = { _, current ->
                    val plan = Warehouse.planBuybackDelete(req, current.document("buyback_state"))
                    plan["buyback_state_after"] = plan.data.remove("buyback_after")
                    plan
                }
            }
            else -> throw Acquisition.Rejected("Not an acquisition opcode")
        }
        val recorded = { owned: Owned, current: StateStore.Current ->
            val plan = planner(owned, current)
            plan["now_epoch"] = now
            for ((k, v) in used) plan[k] = v
            plan
        }
        return Routed(ACTIONS.getValue(opcode), request, recorded)
    }
}
